package javax.bluetooth;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.microedition.io.Connection;

/** Session-scoped JSR-82 transport over an admitted IP room. No Android Bluetooth is used. */
public final class VirtualBluetooth implements AutoCloseable {
    private static final int MAGIC = 0x4a324254, VERSION = 2;
    private static final int SESSION = 1, DEVICES = 2, SERVICES = 3, REGISTER = 4,
            UPDATE = 5, REMOVE = 6, VISIBLE = 7, OPEN = 8, ACCEPT = 9;
    static final int MAX_RECORD = 65536;
    private static volatile VirtualBluetooth active;
    private final String host, secret, address, name;
    private final int port;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<VirtualNotifier> notifiers = ConcurrentHashMap.newKeySet();
    private final Set<VirtualConnection.Packets> packetConnections = ConcurrentHashMap.newKeySet();
    private final Server server;
    private volatile boolean closed;
    private volatile int discoverable = DiscoveryAgent.GIAC;

    public VirtualBluetooth(String host, int port, String secret, String address,
                            String name, boolean owner) throws IOException {
        if (host == null || host.isEmpty() || port < 1 || port > 65535 || name == null || name.length() > 128 ||
                secret == null || secret.length() < 32 || address == null ||
                !address.matches("[0-9a-fA-F]{12}")) throw new IllegalArgumentException("Invalid virtual Bluetooth identity");
        this.host = host; this.port = port; this.secret = secret;
        this.address = address.toUpperCase(Locale.ROOT); this.name = name;
        server = owner ? new Server(port, secret) : null;
        try {
            Socket session = request(SESSION);
            ok(session);
            session.setSoTimeout(0);
            Thread monitor = new Thread(() -> {
                try { session.getInputStream().read(); } catch (IOException ignored) { }
                close();
            }, "j2me-bt-session");
            monitor.setDaemon(true); monitor.start();
        } catch (IOException error) { close(); throw error; }
    }

    public static VirtualBluetooth current() { return active; }
    public static void install(VirtualBluetooth value) {
        active = value;
        LocalDevice.resetLocalDevice();
    }
    public String address() { return address; }
    public String name() { return name; }
    int discoverable() { return discoverable; }

    boolean setDiscoverable(int mode) throws IOException {
        try (Socket socket = request(VISIBLE)) {
            out(socket).writeBoolean(mode != DiscoveryAgent.NOT_DISCOVERABLE);
            ok(socket); discoverable = mode; return true;
        }
    }

    List<RemoteDevice> devices() throws IOException {
        try (Socket socket = request(DEVICES)) {
            ok(socket); DataInputStream in = in(socket);
            int count = count(in.readInt(), 32);
            List<RemoteDevice> result = new ArrayList<>();
            for (int i = 0; i < count; i++) result.add(new RemoteDevice(in.readUTF(), in.readUTF()));
            return result;
        }
    }

    List<ServiceRecord> services(RemoteDevice device, UUID[] uuids) throws IOException {
        try (Socket socket = request(SERVICES)) {
            out(socket).writeUTF(device.getBluetoothAddress()); ok(socket);
            DataInputStream in = in(socket); int count = count(in.readInt(), 32);
            List<ServiceRecord> result = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int id = in.readInt(); boolean packets = in.readBoolean();
                VirtualServiceRecord record = new VirtualServiceRecord(this, device, id, packets, readBlob(in));
                if (record.matches(uuids)) result.add(record);
            }
            return result;
        }
    }

    public Connection open(String url) throws IOException {
        boolean packets = url.startsWith("btl2cap://");
        int start = url.indexOf("://") + 3, colon = url.indexOf(':', start);
        if (colon < start) throw new IllegalArgumentException("Missing Bluetooth endpoint");
        String remote = url.substring(start, colon).toUpperCase(Locale.ROOT);
        String[] parts = url.substring(colon + 1).split(";");
        for (String part : parts) if (part.regionMatches(true, 0, "encrypt=", 0, 8)) {
            String value = part.substring(8);
            if (value.equalsIgnoreCase("true"))
                throw new BluetoothConnectionException(BluetoothConnectionException.SECURITY_BLOCK,
                        "Virtual Bluetooth does not support link encryption");
            if (!value.equalsIgnoreCase("false")) throw new IllegalArgumentException("Invalid encrypt option");
        }
        int receiveMtu = mtuOption(parts, "ReceiveMTU", L2CAPConnection.DEFAULT_MTU);
        int transmitMtu = mtuOption(parts, "TransmitMTU", 0);
        if (remote.equals("LOCALHOST")) {
            UUID uuid = new UUID(parts[0], false);
            String serviceName = "";
            for (String part : parts) {
                if (part.startsWith("name=")) serviceName = part.substring(5);
            }
            // Every valid channel has the same encoded size; validate the full SDP before allocation.
            new VirtualServiceRecord(this, null, 1, packets, uuid, serviceName).encode();
            int id = 0;
            boolean registrationPending = false;
            VirtualNotifier notifier = null;
            try (Socket socket = request(REGISTER)) {
                registrationPending = true;
                out(socket).writeBoolean(packets);
                if (!in(socket).readBoolean()) {
                    registrationPending = false;
                    throw new BluetoothConnectionException(BluetoothConnectionException.NO_RESOURCES,
                            "Virtual Bluetooth service registration rejected");
                }
                id = in(socket).readInt();
                registrationPending = false;
                VirtualServiceRecord record = new VirtualServiceRecord(this, null, id, packets, uuid, serviceName);
                notifier = packets ? new VirtualNotifier.Packets(this, record, receiveMtu, transmitMtu)
                        : new VirtualNotifier.Streams(this, record);
                update(record); notifiers.add(notifier);
                if (closed) throw new IOException("Bluetooth room closed");
                System.out.println("VirtualBluetooth registered " + parts[0] + " channel=" + id);
                return notifier;
            } catch (IOException | RuntimeException error) {
                if (notifier != null) notifiers.remove(notifier);
                if (registrationPending) close(); // An allocated channel may exist without a received ID.
                else if (id != 0) try { removeService(id); }
                catch (IOException | RuntimeException cleanupError) {
                    error.addSuppressed(cleanupError);
                    close(); // Closing the session removes any registration whose rollback is uncertain.
                }
                throw error;
            }
        }
        int id = Integer.parseInt(parts[0], packets ? 16 : 10);
        Socket socket = request(OPEN);
        try {
            out(socket).writeUTF(remote); out(socket).writeInt(id); out(socket).writeBoolean(packets);
            ok(socket);
            out(socket).writeBoolean(true); // Acknowledge this pairing offer before accept is delivered.
            ok(socket); socket.setSoTimeout(0);
            int mtu = packets ? VirtualConnection.negotiateMtu(socket, receiveMtu, transmitMtu) : 0;
            System.out.println("VirtualBluetooth connected " + remote + " channel=" + id);
            return packets ? new VirtualConnection.Packets(this, socket, remote, mtu, receiveMtu)
                    : new VirtualConnection.Streams(this, socket, remote);
        } catch (IOException | RuntimeException error) { release(socket); throw error; }
    }

    private static int mtuOption(String[] parts, String key, int fallback) {
        int value = fallback;
        for (String part : parts) if (part.startsWith(key + "=")) {
            value = Integer.parseInt(part.substring(key.length() + 1));
            if (value < L2CAPConnection.MINIMUM_MTU || value > 65535) throw new IllegalArgumentException("Invalid MTU");
        }
        return value;
    }

    void update(VirtualServiceRecord record) throws IOException {
        byte[] data = record.encode();
        try (Socket socket = request(UPDATE)) {
            out(socket).writeInt(record.id); writeBlob(out(socket), data); ok(socket);
        }
    }

    Socket accept(VirtualNotifier notifier) throws IOException {
        if (notifier.closed) throw new IOException("Bluetooth service closed");
        update(notifier.record);
        Socket socket = request(ACCEPT);
        try {
            notifier.trackAccept(socket);
            out(socket).writeInt(notifier.record.id);
            socket.setSoTimeout(0); ok(socket);
            if (notifier.closed) throw new IOException("Bluetooth service closed");
            return socket;
        } catch (IOException error) {
            notifier.accepts.remove(socket); release(socket); throw error;
        }
    }

    void unregister(VirtualNotifier notifier) throws IOException {
        notifiers.remove(notifier);
        removeService(notifier.record.id);
    }

    private void removeService(int id) throws IOException {
        if (!closed) try (Socket socket = request(REMOVE)) {
            out(socket).writeInt(id); ok(socket);
        }
    }

    private Socket request(int command) throws IOException {
        Socket socket = new Socket() {
            @Override public synchronized void close() throws IOException {
                sockets.remove(this); super.close();
            }
        };
        sockets.add(socket);
        try {
            if (closed) throw new IOException("Bluetooth room closed");
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true); socket.setSoTimeout(15000);
            DataOutputStream out = out(socket);
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(secret);
            out.writeUTF(address); out.writeUTF(name); out.writeInt(command);
            return socket;
        } catch (IOException error) { release(socket); throw error; }
    }

    void release(Socket socket) { sockets.remove(socket); closeSocket(socket); }
    void trackPackets(VirtualConnection.Packets connection) {
        packetConnections.add(connection);
        if (closed) connection.close();
    }
    void releasePackets(VirtualConnection.Packets connection) { packetConnections.remove(connection); }
    static DataInputStream in(Socket socket) throws IOException { return new DataInputStream(socket.getInputStream()); }
    static DataOutputStream out(Socket socket) throws IOException { return new DataOutputStream(socket.getOutputStream()); }
    private static void ok(Socket socket) throws IOException {
        if (!in(socket).readBoolean()) throw new IOException("Bluetooth peer or service unavailable");
    }
    static int count(int value, int max) throws IOException {
        if (value < 0 || value > max) throw new IOException("Invalid Bluetooth payload size");
        return value;
    }
    static byte[] readBlob(DataInputStream in) throws IOException {
        byte[] result = new byte[count(in.readInt(), MAX_RECORD)]; in.readFully(result); return result;
    }
    static void writeBlob(DataOutputStream out, byte[] data) throws IOException {
        count(data.length, MAX_RECORD); out.writeInt(data.length); out.write(data);
    }
    static void closeSocket(Closeable socket) { try { socket.close(); } catch (IOException ignored) { } }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (VirtualConnection.Packets connection : packetConnections) connection.close();
        for (Socket socket : sockets) release(socket);
        notifiers.clear();
        if (server != null) server.close();
    }

    /** Directory and pairing broker. Registry locks never cover network I/O or blocking waits. */
    private static final class Server implements AutoCloseable {
        final ServerSocket listener;
        final String secret;
        final Map<String, Peer> peers = new HashMap<>();
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final ExecutorService workers = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "j2me-bt-broker"); t.setDaemon(true); return t;
        });
        final Semaphore slots = new Semaphore(96);
        volatile boolean closed;

        Server(int port, String secret) throws IOException {
            this.secret = secret;
            listener = new ServerSocket(); listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(port));
            workers.execute(() -> {
                while (!closed) try {
                    Socket socket = listener.accept();
                    if (!slots.tryAcquire()) { closeSocket(socket); continue; }
                    sockets.add(socket);
                    workers.execute(() -> { try { handle(socket); } finally { slots.release(); } });
                } catch (IOException error) { if (!closed) close(); }
            });
        }

        void handle(Socket socket) {
            Peer peer = null;
            boolean session = false, transferred = false;
            try {
                socket.setTcpNoDelay(true); socket.setSoTimeout(5000);
                DataInputStream in = in(socket); DataOutputStream out = out(socket);
                if (in.readInt() != MAGIC || in.readInt() != VERSION || !secret.equals(in.readUTF())) return;
                String address = in.readUTF(), name = in.readUTF(); int op = in.readInt();
                if (!address.matches("[0-9A-F]{12}") || name.length() > 128) return;
                synchronized (peers) {
                    peer = peers.get(address);
                    if (op == SESSION) {
                        if (peer != null || peers.size() >= 32) return;
                        peer = new Peer(address, name); peers.put(address, peer); session = true;
                    }
                    if (peer == null) return;
                    peer.sockets.add(socket);
                }
                switch (op) {
                    case SESSION:
                        out.writeBoolean(true); socket.setSoTimeout(0);
                        while (in.read() != -1) { }
                        break;
                    case DEVICES: {
                        List<Peer> visible = new ArrayList<>();
                        synchronized (peers) { for (Peer p : peers.values()) if (p != peer && p.visible) visible.add(p); }
                        out.writeBoolean(true); out.writeInt(visible.size());
                        for (Peer p : visible) { out.writeUTF(p.address); out.writeUTF(p.name); }
                        break;
                    }
                    case VISIBLE: peer.visible = in.readBoolean(); out.writeBoolean(true); break;
                    case REGISTER: {
                        boolean packets = in.readBoolean(); int id = 0;
                        synchronized (peers) {
                            if (!peer.closed && peer.services.size() < 30) {
                                // At most 30 services are active, so the one-byte channel space has a free slot.
                                do { peer.nextService = peer.nextService % 255 + 1; }
                                while (peer.services.containsKey(peer.nextService));
                                id = peer.nextService;
                                peer.services.put(id, new Service(peer, id, packets));
                            }
                        }
                        out.writeBoolean(id != 0);
                        if (id != 0) out.writeInt(id);
                        break;
                    }
                    case UPDATE: {
                        int id = in.readInt(); byte[] record = readBlob(in); Service service;
                        synchronized (peers) { service = peer.services.get(id); if (service != null) service.record = record; }
                        out.writeBoolean(service != null); break;
                    }
                    case REMOVE: {
                        Service removed; int id = in.readInt();
                        synchronized (peers) { removed = peer.services.remove(id); }
                        if (removed != null) removed.close(); out.writeBoolean(true); break;
                    }
                    case SERVICES: {
                        String addressQuery = in.readUTF(); List<Service> found;
                        synchronized (peers) { Peer p = peers.get(addressQuery); found = p == null ?
                                Collections.emptyList() : new ArrayList<>(p.services.values()); }
                        if (!found.isEmpty()) found.removeIf(s -> s.record.length == 0);
                        out.writeBoolean(true); out.writeInt(found.size());
                        for (Service s : found) { out.writeInt(s.id); out.writeBoolean(s.packets); writeBlob(out, s.record); }
                        break;
                    }
                    case OPEN: {
                        String target = in.readUTF(); int id = in.readInt(); boolean packets = in.readBoolean();
                        Service service;
                        synchronized (peers) { Peer p = peers.get(target); service = p == null ? null : p.services.get(id); }
                        if (service == null || service.packets != packets) { out.writeBoolean(false); break; }
                        Pending pending = new Pending(socket, peer);
                        synchronized (service) {
                            if (service.closed || !service.pending.offer(pending)) { out.writeBoolean(false); break; }
                            service.openings.add(pending);
                        }
                        socket.setSoTimeout(60000);
                        try {
                            // The client sends nothing until offered a pair. EOF cancels a queued OPEN.
                            if (in.read() != 1) throw new IOException("Bluetooth pairing cancelled");
                            pending.acknowledged.complete(true);
                            transferred = pending.paired.get(5, TimeUnit.SECONDS);
                        } finally {
                            if (!transferred) pending.cancel();
                            service.openings.remove(pending); service.pending.remove(pending);
                        }
                        break;
                    }
                    case ACCEPT: {
                        Service service; int id = in.readInt();
                        synchronized (peers) { service = peer.services.get(id); }
                        if (service == null) { out.writeBoolean(false); break; }
                        service.accepts.add(socket); socket.setSoTimeout(0);
                        try {
                            Pending pending = null;
                            while (!closed && !service.closed && !socket.isClosed()) {
                                Pending candidate = service.pending.poll(500, TimeUnit.MILLISECONDS);
                                if (candidate == null) continue;
                                try {
                                    out(candidate.socket).writeBoolean(true);
                                    if (!candidate.acknowledged.get(5, TimeUnit.SECONDS)) {
                                        candidate.cancel(); continue;
                                    }
                                } catch (IOException | ExecutionException | TimeoutException error) {
                                    candidate.cancel(); continue;
                                }
                                synchronized (service) {
                                    if (service.closed) throw new IOException("Bluetooth service closed");
                                    if (!candidate.paired.complete(true)) continue;
                                    service.accepts.remove(socket);
                                    service.openings.remove(candidate);
                                    pending = candidate;
                                }
                                break;
                            }
                            if (pending == null) throw new IOException("Bluetooth service closed");
                            Socket remote = pending.socket;
                            Peer opener = pending.peer, accepter = peer;
                            try {
                                remote.setSoTimeout(0);
                                out.writeBoolean(true); out.writeUTF(opener.address);
                                out(remote).writeBoolean(true);
                                workers.execute(() -> relay(remote, opener, socket, accepter));
                                relay(socket, accepter, remote, opener);
                            } finally { release(opener, remote); }
                        } finally { service.accepts.remove(socket); }
                        break;
                    }
                    default: throw new IOException("Unknown Bluetooth operation");
                }
            } catch (IOException | InterruptedException | ExecutionException | TimeoutException ignored) {
                // Closing the room or service cancels outstanding discovery/accept/connect operations.
            } finally {
                if (session && peer != null) {
                    synchronized (peers) { peers.remove(peer.address, peer); }
                    peer.close();
                }
                if (!transferred) {
                    release(peer, socket);
                }
            }
        }

        void release(Peer peer, Socket socket) {
            if (peer != null) peer.sockets.remove(socket);
            sockets.remove(socket); closeSocket(socket);
        }

        void relay(Socket source, Peer sourcePeer, Socket target, Peer targetPeer) {
            try {
                byte[] buffer = new byte[8192]; int count;
                InputStream in = source.getInputStream(); OutputStream out = target.getOutputStream();
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            } catch (IOException ignored) { }
            finally { release(sourcePeer, source); release(targetPeer, target); }
        }

        @Override public void close() {
            closed = true; closeSocket(listener);
            List<Peer> copy; synchronized (peers) { copy = new ArrayList<>(peers.values()); peers.clear(); }
            for (Peer p : copy) p.close();
            for (Socket socket : sockets) closeSocket(socket);
            workers.shutdownNow();
        }
    }

    private static final class Peer {
        final String address, name;
        final Map<Integer, Service> services = new ConcurrentHashMap<>();
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        volatile boolean visible = true;
        volatile boolean closed;
        int nextService;
        Peer(String address, String name) { this.address = address; this.name = name; }
        void close() {
            closed = true;
            for (Socket socket : sockets) closeSocket(socket);
            for (Service service : services.values()) service.close();
        }
    }
    private static final class Service {
        final Peer owner;
        final int id;
        final boolean packets;
        volatile byte[] record = new byte[0];
        volatile boolean closed;
        final BlockingQueue<Pending> pending = new ArrayBlockingQueue<>(8);
        final Set<Pending> openings = ConcurrentHashMap.newKeySet();
        final Set<Socket> accepts = ConcurrentHashMap.newKeySet();
        Service(Peer owner, int id, boolean packets) { this.owner = owner; this.id = id; this.packets = packets; }
        synchronized void close() {
            closed = true;
            for (Pending p : openings) p.cancel();
            for (Socket socket : accepts) closeSocket(socket);
        }
    }
    private static final class Pending {
        final Socket socket;
        final Peer peer;
        final CompletableFuture<Boolean> acknowledged = new CompletableFuture<>();
        final CompletableFuture<Boolean> paired = new CompletableFuture<>();
        Pending(Socket socket, Peer peer) { this.socket = socket; this.peer = peer; }
        void cancel() { acknowledged.complete(false); paired.complete(false); closeSocket(socket); }
    }
}
