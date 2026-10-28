package javax.bluetooth;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.microedition.io.*;

abstract class VirtualNotifier implements Connection {
    final VirtualBluetooth network;
    final VirtualServiceRecord record;
    final Set<Socket> accepts = ConcurrentHashMap.newKeySet();
    volatile boolean closed;
    VirtualNotifier(VirtualBluetooth network, VirtualServiceRecord record) { this.network = network; this.record = record; }
    synchronized void trackAccept(Socket socket) throws IOException {
        if (closed) throw new IOException("Bluetooth service closed");
        accepts.add(socket);
    }
    synchronized void finishAccept(Socket socket) throws IOException {
        if (closed) throw new IOException("Bluetooth service closed");
        // The handshake is complete; subsequent notifier close preserves this connection.
        accepts.remove(socket);
    }
    @Override public void close() throws IOException {
        List<Socket> pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = new ArrayList<>(accepts);
            accepts.clear();
        }
        for (Socket socket : pending) network.release(socket);
        network.unregister(this);
    }
    static final class Streams extends VirtualNotifier implements StreamConnectionNotifier {
        Streams(VirtualBluetooth network, VirtualServiceRecord record) { super(network, record); }
        @Override public StreamConnection acceptAndOpen() throws IOException {
            Socket socket = network.accept(this);
            try {
                String remote = VirtualBluetooth.in(socket).readUTF();
                finishAccept(socket);
                return new VirtualConnection.Streams(network, socket, remote);
            }
            catch (IOException error) { network.release(socket); throw error; }
            finally { accepts.remove(socket); }
        }
    }
    static final class Packets extends VirtualNotifier implements L2CAPConnectionNotifier {
        private final int receiveMtu, transmitMtu;
        Packets(VirtualBluetooth network, VirtualServiceRecord record, int receiveMtu, int transmitMtu) {
            super(network, record); this.receiveMtu = receiveMtu; this.transmitMtu = transmitMtu;
        }
        @Override public L2CAPConnection acceptAndOpen() throws IOException {
            while (!closed) {
                Socket socket = network.accept(this);
                try {
                    String remote = VirtualBluetooth.in(socket).readUTF();
                    int transmit = VirtualConnection.negotiateMtu(socket, receiveMtu, transmitMtu);
                    finishAccept(socket);
                    return new VirtualConnection.Packets(network, socket, remote, transmit, receiveMtu);
                } catch (BluetoothConnectionException error) {
                    network.release(socket);
                    if (error.getStatus() != BluetoothConnectionException.UNACCEPTABLE_PARAMS) throw error;
                    // A rejected configuration must not consume the server's next valid connection.
                } catch (IOException error) { network.release(socket); throw error; }
                finally { accepts.remove(socket); }
            }
            throw new IOException("Bluetooth service closed");
        }
    }
}
