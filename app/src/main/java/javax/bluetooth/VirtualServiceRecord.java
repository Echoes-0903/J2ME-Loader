package javax.bluetooth;

import java.io.*;
import java.util.*;

/** SDP attributes and endpoint identity belong to one virtual service. */
final class VirtualServiceRecord implements ServiceRecord {
    final VirtualBluetooth network;
    final RemoteDevice device;
    final int id;
    final boolean packets;
    private final Map<Integer, DataElement> attributes = new LinkedHashMap<>();

    VirtualServiceRecord(VirtualBluetooth network, RemoteDevice device, int id, boolean packets,
                         UUID uuid, String name) {
        this.network = network; this.device = device; this.id = id; this.packets = packets;
        DataElement classes = new DataElement(DataElement.DATSEQ);
        classes.addElement(new DataElement(DataElement.UUID, uuid));
        if (!packets) classes.addElement(new DataElement(DataElement.UUID, new UUID(0x1101)));
        attributes.put(1, classes);
        DataElement protocols = new DataElement(DataElement.DATSEQ);
        DataElement l2cap = new DataElement(DataElement.DATSEQ);
        l2cap.addElement(new DataElement(DataElement.UUID, new UUID(0x0100)));
        if (packets) l2cap.addElement(new DataElement(DataElement.U_INT_2, id));
        protocols.addElement(l2cap);
        if (!packets) {
            DataElement spp = new DataElement(DataElement.DATSEQ);
            spp.addElement(new DataElement(DataElement.UUID, new UUID(3)));
            spp.addElement(new DataElement(DataElement.U_INT_1, id)); protocols.addElement(spp);
        }
        attributes.put(4, protocols);
        attributes.put(0x100, new DataElement(DataElement.STRING, name));
    }

    VirtualServiceRecord(VirtualBluetooth network, RemoteDevice device, int id,
                         boolean packets, byte[] data) throws IOException {
        this.network = network; this.device = device; this.id = id; this.packets = packets;
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int count = VirtualBluetooth.count(in.readInt(), 256);
        for (int i = 0; i < count; i++) attributes.put(in.readUnsignedShort(), readElement(in, 0));
        if (in.available() != 0) throw new IOException("Trailing SDP data");
    }

    synchronized byte[] encode() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(attributes.size());
        for (Map.Entry<Integer, DataElement> e : attributes.entrySet()) {
            out.writeShort(e.getKey()); writeElement(out, e.getValue(), 0);
        }
        VirtualBluetooth.count(bytes.size(), VirtualBluetooth.MAX_RECORD);
        return bytes.toByteArray();
    }

    synchronized boolean matches(UUID[] uuids) {
        for (UUID uuid : uuids) {
            boolean found = false;
            for (DataElement e : attributes.values()) if (contains(e, uuid)) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }
    private static boolean contains(DataElement e, UUID uuid) {
        if (e.getDataType() == DataElement.UUID) return uuid.equals(e.getValue());
        if (e.getDataType() == DataElement.DATSEQ || e.getDataType() == DataElement.DATALT) {
            Enumeration values = (Enumeration) e.getValue();
            while (values.hasMoreElements()) if (contains((DataElement) values.nextElement(), uuid)) return true;
        }
        return false;
    }
    @Override public RemoteDevice getHostDevice() { return device; }
    @Override public String getConnectionURL(int security, boolean master) {
        if (security < 0 || security > 2) throw new IllegalArgumentException("Invalid security mode");
        return (packets ? "btl2cap://" : "btspp://") + (device == null ? network.address() : device.getBluetoothAddress())
                + ":" + (packets ? Integer.toHexString(id) : Integer.toString(id))
                + ";authenticate=" + (security != 0) + ";encrypt=" + (security == 2) + ";master=" + master;
    }
    @Override public synchronized DataElement getAttributeValue(int id) { return attributes.get(id); }
    @Override public synchronized int[] getAttributeIDs() {
        int[] result = new int[attributes.size()]; int i = 0;
        for (int id : attributes.keySet()) result[i++] = id;
        return result;
    }
    @Override public boolean populateRecord(int[] ids) { Objects.requireNonNull(ids); return true; }
    @Override public synchronized boolean setAttributeValue(int id, DataElement value) {
        if (device != null || id <= 0 || id > 65535) throw new IllegalArgumentException("Invalid local SDP attribute");
        if (value == null) return attributes.remove(id) != null;
        if (attributes.size() >= 256 && !attributes.containsKey(id)) throw new IllegalArgumentException("Too many SDP attributes");
        attributes.put(id, value); return true;
    }
    @Override public void setDeviceServiceClasses(int classes) { }

    private static void writeElement(DataOutputStream out, DataElement e, int depth) throws IOException {
        if (depth > 8) throw new IOException("SDP nesting too deep");
        int type = e.getDataType(); out.writeByte(type);
        switch (type) {
            case DataElement.NULL: break;
            case DataElement.BOOL: out.writeBoolean(e.getBoolean()); break;
            case DataElement.UUID: out.writeUTF(e.getValue().toString()); break;
            case DataElement.STRING: case DataElement.URL: out.writeUTF((String) e.getValue()); break;
            case DataElement.U_INT_8: case DataElement.U_INT_16: case DataElement.INT_16:
                VirtualBluetooth.writeBlob(out, (byte[]) e.getValue()); break;
            case DataElement.DATSEQ: case DataElement.DATALT:
                VirtualBluetooth.count(e.getSize(), 256); out.writeInt(e.getSize());
                Enumeration values = (Enumeration) e.getValue();
                while (values.hasMoreElements()) writeElement(out, (DataElement) values.nextElement(), depth + 1);
                break;
            default: out.writeLong(e.getLong());
        }
    }
    private static DataElement readElement(DataInputStream in, int depth) throws IOException {
        if (depth > 8) throw new IOException("SDP nesting too deep");
        int type = in.readUnsignedByte();
        switch (type) {
            case DataElement.NULL: return new DataElement(type);
            case DataElement.BOOL: return new DataElement(in.readBoolean());
            case DataElement.UUID: return new DataElement(type, new UUID(in.readUTF(), false));
            case DataElement.STRING: case DataElement.URL: return new DataElement(type, in.readUTF());
            case DataElement.U_INT_8: case DataElement.U_INT_16: case DataElement.INT_16:
                return new DataElement(type, VirtualBluetooth.readBlob(in));
            case DataElement.DATSEQ: case DataElement.DATALT:
                DataElement sequence = new DataElement(type); int count = VirtualBluetooth.count(in.readInt(), 256);
                for (int i = 0; i < count; i++) sequence.addElement(readElement(in, depth + 1));
                return sequence;
            default: return new DataElement(type, in.readLong());
        }
    }
}
