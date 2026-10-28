package javax.bluetooth;

import java.io.*;
import java.net.Socket;
import java.util.Objects;
import javax.microedition.io.*;

abstract class VirtualConnection implements Connection {
    final VirtualBluetooth network;
    final Socket socket;
    final String address;
    VirtualConnection(VirtualBluetooth network, Socket socket, String address) {
        this.network = network; this.socket = socket; this.address = address;
    }
    @Override public void close() { network.release(socket); }

    static int negotiateMtu(Socket socket, int receiveMtu, int transmitMtu) throws IOException {
        DataOutputStream out = VirtualBluetooth.out(socket);
        out.writeInt(receiveMtu); out.writeInt(transmitMtu);
        DataInputStream in = VirtualBluetooth.in(socket);
        int remoteReceive = in.readInt(), remoteTransmit = in.readInt();
        if (remoteReceive < L2CAPConnection.MINIMUM_MTU || remoteReceive > 65535 ||
                remoteTransmit != 0 && (remoteTransmit < L2CAPConnection.MINIMUM_MTU || remoteTransmit > 65535))
            throw new IOException("Invalid peer MTU");
        if (transmitMtu > remoteReceive || remoteTransmit > receiveMtu)
            throw new BluetoothConnectionException(BluetoothConnectionException.UNACCEPTABLE_PARAMS,
                    "Requested transmit MTU exceeds peer receive MTU");
        return transmitMtu == 0 ? remoteReceive : transmitMtu;
    }

    static final class Streams extends VirtualConnection implements StreamConnection {
        private boolean inputOpened, outputOpened;
        Streams(VirtualBluetooth network, Socket socket, String address) { super(network, socket, address); }
        @Override public synchronized InputStream openInputStream() throws IOException {
            if (inputOpened) throw new IOException("Input stream already open");
            inputOpened = true;
            return new FilterInputStream(socket.getInputStream()) {
                @Override public void close() { /* The connection owns the socket, as in the physical adapter. */ }
            };
        }
        @Override public DataInputStream openDataInputStream() throws IOException { return new DataInputStream(openInputStream()); }
        @Override public synchronized OutputStream openOutputStream() throws IOException {
            if (outputOpened) throw new IOException("Output stream already open");
            outputOpened = true;
            return new FilterOutputStream(socket.getOutputStream()) {
                @Override public void write(byte[] data, int off, int len) throws IOException { out.write(data, off, len); }
                @Override public void close() throws IOException { flush(); }
            };
        }
        @Override public DataOutputStream openDataOutputStream() throws IOException { return new DataOutputStream(openOutputStream()); }
    }

    static final class Packets extends VirtualConnection implements L2CAPConnection {
        private final DataInputStream in;
        private final DataOutputStream out;
        private final int transmitMtu, receiveMtu;
        private final Object packetsLock = new Object(), writeLock = new Object();
        private final java.util.ArrayDeque<byte[]> received = new java.util.ArrayDeque<>();
        final Thread reader;
        private IOException failure;
        Packets(VirtualBluetooth network, Socket socket, String address, int transmitMtu) throws IOException {
            this(network, socket, address, transmitMtu, DEFAULT_MTU);
        }
        Packets(VirtualBluetooth network, Socket socket, String address, int transmitMtu, int receiveMtu) throws IOException {
            super(network, socket, address); this.transmitMtu = transmitMtu; this.receiveMtu = receiveMtu;
            in = VirtualBluetooth.in(socket); out = VirtualBluetooth.out(socket);
            reader = new Thread(this::readPackets, "j2me-bt-packets");
            network.trackPackets(this);
            reader.setDaemon(true); reader.start();
        }
        @Override public int getTransmitMTU() { return transmitMtu; }
        @Override public int getReceiveMTU() { return receiveMtu; }
        @Override public void send(byte[] data) throws IOException {
            Objects.requireNonNull(data);
            synchronized (writeLock) {
                int count = Math.min(data.length, transmitMtu);
                out.writeInt(count); out.write(data, 0, count);
            }
        }
        @Override public int receive(byte[] buffer) throws IOException {
            Objects.requireNonNull(buffer);
            synchronized (packetsLock) {
                while (received.isEmpty() && failure == null) try { packetsLock.wait(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                if (received.isEmpty()) throw failure;
                byte[] packet = received.removeFirst(); packetsLock.notifyAll();
                int copied = Math.min(buffer.length, packet.length);
                System.arraycopy(packet, 0, buffer, 0, copied);
                return copied;
            }
        }
        @Override public boolean ready() throws IOException {
            synchronized (packetsLock) {
                if (!received.isEmpty()) return true;
                if (failure != null) throw failure;
                return false;
            }
        }
        private void readPackets() {
            try {
                while (!socket.isClosed()) {
                    byte[] packet = new byte[VirtualBluetooth.count(in.readInt(), receiveMtu)];
                    in.readFully(packet);
                    synchronized (packetsLock) {
                        while (received.size() >= 64 && failure == null) packetsLock.wait();
                        if (failure != null) return;
                        received.addLast(packet); packetsLock.notifyAll();
                    }
                }
            } catch (IOException | InterruptedException e) {
                synchronized (packetsLock) {
                    if (failure == null) failure = e instanceof IOException ? (IOException) e : new InterruptedIOException();
                    packetsLock.notifyAll();
                }
            } finally {
                synchronized (packetsLock) {
                    if (failure == null) failure = new IOException("Bluetooth connection closed");
                    packetsLock.notifyAll();
                }
                network.releasePackets(this);
                super.close();
            }
        }
        @Override public void close() {
            network.releasePackets(this);
            synchronized (packetsLock) {
                failure = new IOException("Bluetooth connection closed"); received.clear(); packetsLock.notifyAll();
            }
            super.close();
        }
    }
}
