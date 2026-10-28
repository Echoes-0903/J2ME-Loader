package javax.bluetooth;

import org.junit.Test;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import javax.microedition.io.*;
import static org.junit.Assert.*;

public class VirtualBluetoothTest {
    private static final String KEY = "0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String UUID_TEXT = "102030405060708090A0B0C0D0E0F001";

    private static Object field(Object value, String name) throws Exception {
        java.lang.reflect.Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(value);
    }
    private static int pendingOpens(Room r) throws Exception {
        Map<?, ?> peers = (Map<?, ?>) field(field(r.host, "server"), "peers");
        Object host;
        synchronized (peers) { host = peers.get(r.host.address()); }
        Map<?, ?> services = (Map<?, ?>) field(host, "services");
        int count = 0;
        for (Object service : services.values()) count += ((BlockingQueue<?>) field(service, "pending")).size();
        return count;
    }
    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.call() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("condition timed out", condition.call());
    }
    private static Socket queuedOpen(Room r, VirtualNotifier notifier) throws Exception {
        Socket socket = new Socket("127.0.0.1", r.port);
        socket.setSoTimeout(3000);
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeInt(0x4a324254); out.writeInt(2); out.writeUTF(KEY);
        out.writeUTF(r.guest.address()); out.writeUTF("Guest"); out.writeInt(8);
        out.writeUTF(r.host.address()); out.writeInt(notifier.record.id); out.writeBoolean(notifier.record.packets);
        await(() -> pendingOpens(r) == 1);
        return socket;
    }

    private static final class Room implements AutoCloseable {
        final int port;
        final VirtualBluetooth host, guest;
        final ExecutorService worker = Executors.newCachedThreadPool();
        Room() throws IOException {
            try (ServerSocket free = new ServerSocket(0)) { port = free.getLocalPort(); }
            host = new VirtualBluetooth("127.0.0.1", port, KEY, "020000000001", "Host", true);
            guest = new VirtualBluetooth("127.0.0.1", port, KEY, "020000000002", "Guest", false);
        }
        @Override public void close() { guest.close(); host.close(); worker.shutdownNow(); }
    }

    private static int peerSocketCount(Room r, String address) throws Exception {
        Map<?, ?> peers = (Map<?, ?>) field(field(r.host, "server"), "peers");
        synchronized (peers) { return ((Set<?>) field(peers.get(address), "sockets")).size(); }
    }

    @Test public void encryptedListenersAreRejectedBeforeRegistration() throws Exception {
        try (Room r = new Room()) {
            for (String protocol : new String[]{"btspp", "btl2cap"}) {
                for (String option : new String[]{"encrypt=true", "encrypt=TRUE", "Encrypt=true", "encrypt=true;encrypt=false"}) {
                    try {
                        Connection unexpected = r.host.open(protocol + "://localhost:" + UUID_TEXT + ";" + option);
                        unexpected.close(); fail("unsupported encrypted listener accepted");
                    } catch (BluetoothConnectionException expected) {
                        assertEquals(BluetoothConnectionException.SECURITY_BLOCK, expected.getStatus());
                    }
                }
                for (String value : new String[]{"", "1", "yes"}) {
                    try {
                        Connection unexpected = r.host.open(protocol + "://localhost:" + UUID_TEXT + ";encrypt=" + value);
                        unexpected.close(); fail("invalid encryption boolean accepted");
                    } catch (IllegalArgumentException expected) { }
                }
            }
            assertEquals(0, ((Set<?>) field(r.host, "notifiers")).size());
        }
    }

    @Test public void encryptedClientsDoNotConsumeThePendingAccept() throws Exception {
        try (Room r = new Room()) {
            for (boolean packets : new boolean[]{false, true}) {
                VirtualNotifier notifier = (VirtualNotifier) r.host.open((packets ? "btl2cap" : "btspp") +
                        "://localhost:" + UUID_TEXT + ";authenticate=true;encrypt=false");
                Future<? extends Connection> accept = r.worker.submit(() -> packets ?
                        ((L2CAPConnectionNotifier) notifier).acceptAndOpen() : ((StreamConnectionNotifier) notifier).acceptAndOpen());
                try {
                    Connection unexpected = r.guest.open(notifier.record.getConnectionURL(ServiceRecord.AUTHENTICATE_ENCRYPT, false));
                    unexpected.close(); fail("unsupported encrypted client accepted");
                } catch (BluetoothConnectionException expected) {
                    assertEquals(BluetoothConnectionException.SECURITY_BLOCK, expected.getStatus());
                }
                assertFalse("rejected client consumed accept", accept.isDone());
                assertEquals(0, pendingOpens(r));
                Connection client = r.guest.open(notifier.record.getConnectionURL(ServiceRecord.AUTHENTICATE_NOENCRYPT, false));
                Connection server = accept.get(3, TimeUnit.SECONDS);
                if (packets) {
                    ((L2CAPConnection) client).send(new byte[]{7});
                    byte[] data = new byte[1]; assertEquals(1, ((L2CAPConnection) server).receive(data)); assertEquals(7, data[0]);
                } else {
                    ((StreamConnection) client).openDataOutputStream().writeInt(2011);
                    assertEquals(2011, ((StreamConnection) server).openDataInputStream().readInt());
                }
                client.close(); server.close(); notifier.close();
            }
        }
    }

    @Test public void unencodableServiceNamesDoNotAllocateServices() throws Exception {
        try (Room r = new Room()) {
            char[] ascii = new char[65536], unicode = new char[22000];
            Arrays.fill(ascii, 'x'); Arrays.fill(unicode, '\u4e2d');
            String[] names = {new String(ascii), new String(ascii, 0, 65535), new String(unicode)};
            for (int i = 0; i < 36; i++) {
                try {
                    r.host.open((i % 2 == 0 ? "btspp" : "btl2cap") + "://localhost:" + UUID_TEXT + ";name=" + names[i % names.length]);
                    fail("oversized SDP record accepted");
                } catch (IOException expected) { }
            }
            Map<?, ?> peers = (Map<?, ?>) field(field(r.host, "server"), "peers");
            Object host;
            synchronized (peers) { host = peers.get(r.host.address()); }
            assertEquals(0, ((Map<?, ?>) field(host, "services")).size());
            assertEquals(0, field(host, "nextService"));
            assertTrue(((Set<?>) field(r.host, "notifiers")).isEmpty());
            r.host.open("btspp://localhost:" + UUID_TEXT).close();
        }
    }

    /** A healthy session whose UPDATE reply is lost after the broker has published the service. */
    private static final class PublicationFailureBroker implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0);
        final ExecutorService workers = Executors.newCachedThreadPool();
        final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final Set<Integer> services = ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicInteger nextId = new java.util.concurrent.atomic.AtomicInteger();
        final CountDownLatch sessionClosed = new CountDownLatch(1);
        volatile boolean failUpdate = true, failRemove, failRegister, failRegisterId;
        PublicationFailureBroker() throws IOException {
            workers.submit(() -> {
                try {
                    while (!listener.isClosed()) {
                        Socket socket = listener.accept(); sockets.add(socket);
                        workers.submit(() -> handle(socket));
                    }
                } catch (IOException expectedOnClose) { }
            });
        }
        void handle(Socket socket) {
            try (Socket connection = socket) {
                connection.setSoTimeout(3000);
                DataInputStream in = VirtualBluetooth.in(connection); DataOutputStream out = VirtualBluetooth.out(connection);
                if (in.readInt() != 0x4a324254 || in.readInt() != 2 || !KEY.equals(in.readUTF())) return;
                in.readUTF(); in.readUTF();
                switch (in.readInt()) {
                    case 1:
                        out.writeBoolean(true); connection.setSoTimeout(0);
                        try { while (in.read() != -1) { } }
                        finally { services.clear(); sessionClosed.countDown(); }
                        break;
                    case 4:
                        in.readBoolean(); int id = nextId.incrementAndGet(); services.add(id);
                        if (!failRegister) { out.writeBoolean(true); if (!failRegisterId) out.writeInt(id); }
                        break;
                    case 5:
                        in.readInt(); VirtualBluetooth.readBlob(in);
                        if (!failUpdate) out.writeBoolean(true);
                        break; // Simulate a lost reply, leaving the registered service allocated.
                    case 6:
                        int removed = in.readInt();
                        if (!failRemove) { services.remove(removed); out.writeBoolean(true); }
                        break;
                    default: throw new AssertionError("Unexpected broker operation");
                }
            } catch (IOException expectedOnClose) { }
            finally { sockets.remove(socket); }
        }
        @Override public void close() throws IOException {
            listener.close();
            for (Socket socket : sockets) VirtualBluetooth.closeSocket(socket);
            workers.shutdownNow();
        }
    }

    @Test public void publicationFailureRollsBackTheRegisteredService() throws Exception {
        try (PublicationFailureBroker broker = new PublicationFailureBroker();
             VirtualBluetooth peer = new VirtualBluetooth("127.0.0.1", broker.listener.getLocalPort(), KEY, "020000000001", "Host", false)) {
            for (int i = 0; i < 36; i++) {
                try { peer.open((i % 2 == 0 ? "btspp" : "btl2cap") + "://localhost:" + UUID_TEXT); fail("publication succeeded without reply"); }
                catch (EOFException expected) { }
                assertTrue("failed publication retained a service", broker.services.isEmpty());
                assertTrue(((Set<?>) field(peer, "notifiers")).isEmpty());
            }
            assertEquals(1, broker.sessionClosed.getCount());
            broker.failUpdate = false;
            Connection valid = peer.open("btspp://localhost:" + UUID_TEXT);
            assertEquals(1, broker.services.size()); valid.close();
            assertTrue(broker.services.isEmpty());
        }
    }

    @Test public void rollbackFailureClosesThePeerAndPreservesThePublicationError() throws Exception {
        try (PublicationFailureBroker broker = new PublicationFailureBroker();
             VirtualBluetooth peer = new VirtualBluetooth("127.0.0.1", broker.listener.getLocalPort(), KEY, "020000000001", "Host", false)) {
            broker.failRemove = true;
            try { peer.open("btspp://localhost:" + UUID_TEXT); fail("publication succeeded without reply"); }
            catch (EOFException expected) { assertEquals(1, expected.getSuppressed().length); }
            assertTrue("uncertain rollback left the peer registered", broker.sessionClosed.await(3, TimeUnit.SECONDS));
            assertTrue(broker.services.isEmpty());
            assertTrue(((Set<?>) field(peer, "notifiers")).isEmpty());
            try { peer.open("btspp://localhost:" + UUID_TEXT); fail("closed peer accepted another registration"); }
            catch (IOException expected) { }
        }
    }

    @Test public void lostRegistrationReplyClosesThePeerAndClearsUnaddressableServices() throws Exception {
        for (boolean acknowledge : new boolean[]{false, true}) {
            try (PublicationFailureBroker broker = new PublicationFailureBroker();
                 VirtualBluetooth peer = new VirtualBluetooth("127.0.0.1", broker.listener.getLocalPort(), KEY, "020000000001", "Host", false)) {
                broker.failRegister = !acknowledge; broker.failRegisterId = acknowledge;
                try { peer.open("btspp://localhost:" + UUID_TEXT); fail("registration succeeded without a channel reply"); }
                catch (EOFException expected) { }
                assertTrue("lost allocation reply left the peer registered", broker.sessionClosed.await(3, TimeUnit.SECONDS));
                assertTrue(broker.services.isEmpty());
                assertTrue(((Set<?>) field(peer, "notifiers")).isEmpty());
            }
        }
    }

    @Test public void closingNotifierCancelsMtuNegotiation() throws Exception {
        try (Room r = new Room()) {
            VirtualNotifier notifier = (VirtualNotifier) r.host.open("btl2cap://localhost:" + UUID_TEXT);
            try (Socket raw = queuedOpen(r, notifier)) {
                Future<?> accept = r.worker.submit(() -> {
                    try { ((L2CAPConnectionNotifier) notifier).acceptAndOpen(); fail("accept survived notifier close"); }
                    catch (IOException expected) { }
                });
                DataInputStream in = new DataInputStream(raw.getInputStream());
                assertTrue(in.readBoolean());
                new DataOutputStream(raw.getOutputStream()).writeBoolean(true);
                assertTrue(in.readBoolean());
                assertEquals(L2CAPConnection.DEFAULT_MTU, in.readInt());
                assertEquals(0, in.readInt()); // Peer deliberately withholds its MTUs.
                notifier.close();
                accept.get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test public void completedRelaysReleaseBothPeerSocketRegistries() throws Exception {
        try (Room r = new Room()) {
            for (boolean packets : new boolean[]{false, true}) {
                VirtualNotifier notifier = (VirtualNotifier) r.host.open((packets ? "btl2cap" : "btspp") + "://localhost:" + UUID_TEXT);
                for (int i = 0; i < 5; i++) {
                    Future<? extends Connection> accept = r.worker.submit(() -> packets ?
                            ((L2CAPConnectionNotifier) notifier).acceptAndOpen() : ((StreamConnectionNotifier) notifier).acceptAndOpen());
                    Connection client = r.guest.open(notifier.record.getConnectionURL(0, false));
                    Connection server = accept.get(3, TimeUnit.SECONDS);
                    await(() -> peerSocketCount(r, r.host.address()) == 2 && peerSocketCount(r, r.guest.address()) == 2);
                    client.close(); server.close();
                    await(() -> peerSocketCount(r, r.host.address()) == 1 && peerSocketCount(r, r.guest.address()) == 1 &&
                            ((Set<?>) field(field(r.host, "server"), "sockets")).size() == 2);
                }
                notifier.close();
            }
        }
    }

    @Test public void releasedChannelsAreReusedWithoutReplacingAnActiveService() throws Exception {
        try (Room r = new Room()) {
            VirtualNotifier active = (VirtualNotifier) r.host.open("btspp://localhost:" + UUID_TEXT);
            Set<Integer> used = new HashSet<>();
            boolean reused = false;
            for (int i = 0; i < 300; i++) {
                VirtualNotifier next = (VirtualNotifier) r.host.open((i % 2 == 0 ? "btspp" : "btl2cap") + "://localhost:" + UUID_TEXT);
                assertTrue(next.record.id >= 1 && next.record.id <= 255);
                assertNotEquals(active.record.id, next.record.id);
                if (!used.add(next.record.id)) reused = true;
                next.close();
            }
            assertTrue("released channel was never reused", reused);
            Future<StreamConnection> accept = r.worker.submit(((StreamConnectionNotifier) active)::acceptAndOpen);
            StreamConnection client = (StreamConnection) r.guest.open(active.record.getConnectionURL(0, false));
            StreamConnection server = accept.get(3, TimeUnit.SECONDS);
            client.openDataOutputStream().writeInt(2011);
            assertEquals(2011, server.openDataInputStream().readInt());
            client.close(); server.close(); active.close();
        }
    }

    @Test public void channelReusePreservesTheActiveServiceLimit() throws Exception {
        try (Room r = new Room()) {
            List<VirtualNotifier> services = new ArrayList<>();
            Set<Integer> ids = new HashSet<>();
            for (int i = 0; i < 30; i++) {
                VirtualNotifier notifier = (VirtualNotifier) r.host.open("btspp://localhost:" + UUID_TEXT);
                services.add(notifier); assertTrue(ids.add(notifier.record.id));
            }
            try { r.host.open("btspp://localhost:" + UUID_TEXT); fail("active service limit ignored"); }
            catch (BluetoothConnectionException expected) {
                assertEquals(BluetoothConnectionException.NO_RESOURCES, expected.getStatus());
            }
            VirtualNotifier removed = services.remove(0);
            removed.close(); ids.remove(removed.record.id);
            VirtualNotifier replacement = (VirtualNotifier) r.host.open("btl2cap://localhost:" + UUID_TEXT);
            assertFalse(ids.contains(replacement.record.id));
            replacement.close();
            for (VirtualNotifier notifier : services) notifier.close();
        }
    }

    @Test public void discoverabilityAndServiceAttributesStayInRoom() throws Exception {
        try (Room r = new Room()) {
            r.host.setDiscoverable(DiscoveryAgent.NOT_DISCOVERABLE);
            assertTrue(r.guest.devices().isEmpty());
            r.host.setDiscoverable(DiscoveryAgent.GIAC);
            RemoteDevice remote = r.guest.devices().get(0);
            assertEquals("Host", remote.getFriendlyName(false));
            VirtualNotifier notifier = (VirtualNotifier) r.host.open("btspp://localhost:" + UUID_TEXT + ";name=PES");
            notifier.record.setAttributeValue(0x101, new DataElement(DataElement.STRING, "test-value"));
            r.host.update(notifier.record);
            List<ServiceRecord> services = r.guest.services(remote, new UUID[]{new UUID(UUID_TEXT, false)});
            assertEquals(1, services.size());
            assertEquals("test-value", services.get(0).getAttributeValue(0x101).getValue());
            assertTrue(r.guest.services(remote, new UUID[]{new UUID(0x9999)}).isEmpty());
            notifier.close();
            assertTrue(r.guest.services(remote, new UUID[]{new UUID(UUID_TEXT, false)}).isEmpty());
        }
    }

    @Test public void streamConnectionTransfersBothDirections() throws Exception {
        try (Room r = new Room()) {
            StreamConnectionNotifier notifier = (StreamConnectionNotifier) r.host.open("btspp://localhost:" + UUID_TEXT + ";name=PES");
            Future<StreamConnection> pending = r.worker.submit(notifier::acceptAndOpen);
            StreamConnection client = (StreamConnection) r.guest.open(((VirtualNotifier) notifier).record.getConnectionURL(0, false));
            StreamConnection server = pending.get(5, TimeUnit.SECONDS);
            notifier.close(); // Closing a listener must preserve an accepted connection.
            DataOutputStream clientOut = client.openDataOutputStream(), serverOut = server.openDataOutputStream();
            DataInputStream clientIn = client.openDataInputStream(), serverIn = server.openDataInputStream();
            clientOut.writeInt(2011); assertEquals(2011, serverIn.readInt());
            serverOut.writeUTF("goal"); assertEquals("goal", clientIn.readUTF());
            client.close(); server.close(); notifier.close();
        }
    }

    @Test public void l2capPreservesEmptyPacketsAndDiscardsTruncatedRemainder() throws Exception {
        try (Room r = new Room()) {
            L2CAPConnectionNotifier notifier = (L2CAPConnectionNotifier) r.host.open("btl2cap://localhost:" + UUID_TEXT + ";ReceiveMTU=100;name=PES");
            Future<L2CAPConnection> pending = r.worker.submit(notifier::acceptAndOpen);
            L2CAPConnection client = (L2CAPConnection) r.guest.open(((VirtualNotifier) notifier).record.getConnectionURL(0, false));
            L2CAPConnection server = pending.get(5, TimeUnit.SECONDS);
            notifier.close(); // PES2011 closes its notifier immediately after accepting.
            assertEquals(100, client.getTransmitMTU());
            client.send(new byte[]{1, 2, 3, 4}); client.send(new byte[0]); client.send(new byte[]{9});
            byte[] small = new byte[2];
            assertEquals(2, server.receive(small)); assertArrayEquals(new byte[]{1, 2}, small);
            assertEquals(0, server.receive(small));
            assertEquals(1, server.receive(small)); assertEquals(9, small[0]);
            server.send(new byte[]{7}); assertEquals(1, client.receive(small)); assertEquals(7, small[0]);
            client.close(); server.close(); notifier.close();
        }
    }

    @Test public void closingNotifierCancelsBlockedAccept() throws Exception {
        try (Room r = new Room()) {
            VirtualNotifier notifier = (VirtualNotifier) r.host.open("btspp://localhost:" + UUID_TEXT + ";name=PES");
            Future<?> pending = r.worker.submit(() -> {
                try { ((StreamConnectionNotifier) notifier).acceptAndOpen(); fail("accept should be cancelled"); }
                catch (IOException expected) { }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (notifier.accepts.isEmpty() && System.nanoTime() < deadline) Thread.yield();
            assertFalse(notifier.accepts.isEmpty());
            notifier.close(); pending.get(3, TimeUnit.SECONDS);
        }
    }

    @Test public void wrongRoomCredentialCannotJoin() throws Exception {
        try (Room r = new Room()) {
            try {
                new VirtualBluetooth("127.0.0.1", r.port, "00000000000000000000000000000000", "020000000003", "Intruder", false);
                fail("wrong room key accepted");
            } catch (IOException expected) { }
            r.host.setDiscoverable(DiscoveryAgent.GIAC);
            assertEquals(1, r.guest.devices().size());
        }
    }

    @Test public void packetReadyRequiresTheCompletePayload() throws Exception {
        try (Room r = new Room(); ServerSocket listener = new ServerSocket(0);
             java.net.Socket writer = new java.net.Socket("127.0.0.1", listener.getLocalPort());
             java.net.Socket reader = listener.accept()) {
            VirtualConnection.Packets packets = new VirtualConnection.Packets(r.guest, reader, r.host.address(), 48, 48);
            DataOutputStream raw = new DataOutputStream(writer.getOutputStream());
            raw.writeInt(3); raw.write(new byte[]{1, 2});
            assertFalse(packets.ready());
            raw.writeByte(3);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!packets.ready() && System.nanoTime() < deadline) Thread.yield();
            assertTrue(packets.ready());
            byte[] data = new byte[3]; assertEquals(3, packets.receive(data));
            assertArrayEquals(new byte[]{1, 2, 3}, data);
            Future<?> blocked = r.worker.submit(() -> {
                try { packets.receive(data); fail("receive should be cancelled"); }
                catch (IOException expected) { }
            });
            packets.close(); blocked.get(3, TimeUnit.SECONDS);
        }
    }

    @Test public void closingRoomCancelsReaderWaitingForQueueSpace() throws Exception {
        try (Room r = new Room(); ServerSocket listener = new ServerSocket(0);
             java.net.Socket writer = new java.net.Socket("127.0.0.1", listener.getLocalPort());
             java.net.Socket reader = listener.accept()) {
            VirtualConnection.Packets packets = new VirtualConnection.Packets(r.guest, reader, r.host.address(), 48, 48);
            DataOutputStream raw = new DataOutputStream(writer.getOutputStream());
            for (int i = 0; i < 65; i++) { raw.writeInt(1); raw.writeByte(i); }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (packets.reader.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield();
            assertEquals(Thread.State.WAITING, packets.reader.getState());
            r.guest.close();
            packets.reader.join(3000);
            assertFalse("packet reader survived room shutdown", packets.reader.isAlive());
            assertTrue(reader.isClosed());
        }
    }

    @Test public void cancelledOpenIsRemovedBeforeTheNextAccept() throws Exception {
        try (Room r = new Room()) {
            VirtualNotifier notifier = (VirtualNotifier) r.host.open("btspp://localhost:" + UUID_TEXT);
            Socket cancelled = queuedOpen(r, notifier);
            cancelled.close();
            await(() -> pendingOpens(r) == 0);
            Future<StreamConnection> accept = r.worker.submit(((StreamConnectionNotifier) notifier)::acceptAndOpen);
            StreamConnection client = (StreamConnection) r.guest.open(notifier.record.getConnectionURL(0, false));
            StreamConnection server = accept.get(3, TimeUnit.SECONDS);
            client.openDataOutputStream().writeInt(2011);
            assertEquals(2011, server.openDataInputStream().readInt());
            client.close(); server.close(); notifier.close();
        }
    }

    @Test public void cancellingAPairingOfferDoesNotConsumeTheAccept() throws Exception {
        try (Room r = new Room()) {
            VirtualNotifier notifier = (VirtualNotifier) r.host.open("btspp://localhost:" + UUID_TEXT);
            Socket cancelled = queuedOpen(r, notifier);
            Future<StreamConnection> accept = r.worker.submit(((StreamConnectionNotifier) notifier)::acceptAndOpen);
            assertTrue(new DataInputStream(cancelled.getInputStream()).readBoolean());
            cancelled.close();
            StreamConnection client = (StreamConnection) r.guest.open(notifier.record.getConnectionURL(0, false));
            StreamConnection server = accept.get(3, TimeUnit.SECONDS);
            client.openDataOutputStream().writeInt(82);
            assertEquals(82, server.openDataInputStream().readInt());
            client.close(); server.close(); notifier.close();
        }
    }

    @Test public void closingNotifierCancelsAnUnacknowledgedOffer() throws Exception {
        try (Room r = new Room()) {
            VirtualNotifier notifier = (VirtualNotifier) r.host.open("btspp://localhost:" + UUID_TEXT);
            try (Socket waiting = queuedOpen(r, notifier)) {
                Future<?> accept = r.worker.submit(() -> {
                    try { ((StreamConnectionNotifier) notifier).acceptAndOpen(); fail("accept survived close"); }
                    catch (IOException expected) { }
                });
                assertTrue(new DataInputStream(waiting.getInputStream()).readBoolean());
                notifier.close();
                accept.get(2, TimeUnit.SECONDS);
            }
        }
    }

    @Test public void explicitTransmitMtuControlsBothDirectionsAndTruncation() throws Exception {
        try (Room r = new Room()) {
            VirtualNotifier notifier = (VirtualNotifier) r.host.open("btl2cap://localhost:" + UUID_TEXT +
                    ";ReceiveMTU=100;TransmitMTU=48");
            Future<L2CAPConnection> accept = r.worker.submit(((L2CAPConnectionNotifier) notifier)::acceptAndOpen);
            L2CAPConnection client = (L2CAPConnection) r.guest.open(notifier.record.getConnectionURL(0, false) +
                    ";ReceiveMTU=200;TransmitMTU=80");
            L2CAPConnection server = accept.get(3, TimeUnit.SECONDS);
            assertEquals(80, client.getTransmitMTU()); assertEquals(48, server.getTransmitMTU());
            assertEquals(200, client.getReceiveMTU()); assertEquals(100, server.getReceiveMTU());
            client.send(new byte[90]); assertEquals(80, server.receive(new byte[100]));
            server.send(new byte[90]); assertEquals(48, client.receive(new byte[200]));
            client.close(); server.close(); notifier.close();
        }
    }

    @Test public void incompatibleTransmitMtuRejectsTheClientAndKeepsAccepting() throws Exception {
        try (Room r = new Room()) {
            for (boolean serverTooLarge : new boolean[]{false, true}) {
                VirtualNotifier notifier = (VirtualNotifier) r.host.open("btl2cap://localhost:" + UUID_TEXT +
                        ";ReceiveMTU=100;TransmitMTU=" + (serverTooLarge ? 200 : 48));
                Future<L2CAPConnection> accept = r.worker.submit(((L2CAPConnectionNotifier) notifier)::acceptAndOpen);
                try {
                    r.guest.open(notifier.record.getConnectionURL(0, false) + ";ReceiveMTU=100;TransmitMTU=" +
                            (serverTooLarge ? 80 : 101));
                    fail("incompatible client MTU accepted");
                } catch (BluetoothConnectionException expected) {
                    assertEquals(BluetoothConnectionException.UNACCEPTABLE_PARAMS, expected.getStatus());
                }
                L2CAPConnection client = (L2CAPConnection) r.guest.open(notifier.record.getConnectionURL(0, false) +
                        ";ReceiveMTU=200;TransmitMTU=80");
                L2CAPConnection server = accept.get(3, TimeUnit.SECONDS);
                assertEquals(serverTooLarge ? 200 : 48, server.getTransmitMTU());
                client.send(new byte[]{7}); byte[] data = new byte[1];
                assertEquals(1, server.receive(data)); assertEquals(7, data[0]);
                client.close(); server.close(); notifier.close();
            }
        }
    }

    @Test public void explicitTransmitMtuMustBeWithinTheSupportedRange() throws Exception {
        try (Room r = new Room()) {
            for (int mtu : new int[]{0, 47, 65536}) try {
                r.host.open("btl2cap://localhost:" + UUID_TEXT + ";TransmitMTU=" + mtu);
                fail("invalid transmit MTU accepted: " + mtu);
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void previousWireVersionIsRejectedWithoutJoiningTheRoom() throws Exception {
        try (Room r = new Room(); Socket old = new Socket("127.0.0.1", r.port)) {
            old.setSoTimeout(3000);
            DataOutputStream out = new DataOutputStream(old.getOutputStream());
            out.writeInt(0x4a324254); out.writeInt(1);
            assertEquals(-1, old.getInputStream().read());
            assertEquals(1, r.host.devices().size());
        }
    }

    @Test public void reusingTheInquiryListenerDoesNotReuseTheCancelledTask() throws Exception {
        CountDownLatch releaseOld = new CountDownLatch(1), releaseNew = new CountDownLatch(1);
        try (Room r = new Room()) {
            VirtualBluetooth.install(r.host);
            try {
                DiscoveryAgent agent = new DiscoveryAgent();
                CountDownLatch oldEntered = new CountDownLatch(1), newEntered = new CountDownLatch(1),
                        completed = new CountDownLatch(1);
                java.util.concurrent.atomic.AtomicInteger discoveries = new java.util.concurrent.atomic.AtomicInteger(),
                        completions = new java.util.concurrent.atomic.AtomicInteger(), terminated = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicReference<Thread> oldThread = new java.util.concurrent.atomic.AtomicReference<>();
                DiscoveryListener listener = new DiscoveryListener() {
                    @Override public void deviceDiscovered(RemoteDevice remote, DeviceClass type) {
                        boolean first = discoveries.incrementAndGet() == 1;
                        if (first) { oldThread.set(Thread.currentThread()); oldEntered.countDown(); }
                        else newEntered.countDown();
                        try { (first ? releaseOld : releaseNew).await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    @Override public void inquiryCompleted(int reason) {
                        if (reason == INQUIRY_TERMINATED) terminated.incrementAndGet();
                        else { completions.incrementAndGet(); completed.countDown(); }
                    }
                    @Override public void servicesDiscovered(int id, ServiceRecord[] records) { }
                    @Override public void serviceSearchCompleted(int id, int reason) { }
                };
                assertTrue(agent.startInquiry(DiscoveryAgent.GIAC, listener));
                assertTrue(oldEntered.await(3, TimeUnit.SECONDS));
                assertTrue(agent.cancelInquiry(listener));
                assertTrue(agent.startInquiry(DiscoveryAgent.GIAC, listener));
                assertTrue(newEntered.await(3, TimeUnit.SECONDS));
                releaseOld.countDown(); oldThread.get().join(3000);
                assertFalse(oldThread.get().isAlive());
                assertEquals("old inquiry completed the restarted task", 0, completions.get());
                releaseNew.countDown();
                assertTrue(completed.await(3, TimeUnit.SECONDS));
                assertEquals(1, completions.get()); assertEquals(1, terminated.get());
            } finally { releaseOld.countDown(); releaseNew.countDown(); VirtualBluetooth.install(null); }
        }
    }
}
