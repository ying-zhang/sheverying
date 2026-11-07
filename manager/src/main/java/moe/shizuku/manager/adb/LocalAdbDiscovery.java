package moe.shizuku.manager.adb;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;

/** Queries only this phone's mDNS responder, without NsdManager's resolve queue. */
public final class LocalAdbDiscovery {
    private static final String SERVICE = "_adb-tls-connect._tcp.local";
    private static final int QUERY_ID = 0x1417;

    private LocalAdbDiscovery() {}

    public static int findPort() {
        return findPort(new int[0]);
    }

    public static int findPort(int[] excluded) {
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (InetAddress address : Collections.list(iface.getInetAddresses())) {
                    if (!(address instanceof Inet4Address)) continue;
                    int port = query(address, false, excluded);
                    if (port <= 0) port = query(address, true, excluded);
                    if (port > 0) return port;
                }
            }
        } catch (IOException | SecurityException ignored) {
            // Standard NSD discovery remains available when local UDP is blocked.
        }
        return -1;
    }

    private static int query(InetAddress localAddress, boolean multicast, int[] excluded) {
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(localAddress, 0))) {
            byte[] query = queryBytes();
            InetAddress destination = multicast ? InetAddress.getByName("224.0.0.251") : localAddress;
            socket.send(new DatagramPacket(query, query.length, destination, 5353));
            long deadline = System.nanoTime() + 500_000_000L;
            while (System.nanoTime() < deadline) {
                socket.setSoTimeout(Math.max(1, (int) ((deadline - System.nanoTime()) / 1_000_000L)));
                byte[] buffer = new byte[9000];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                socket.receive(response);
                if (!localAddress.equals(response.getAddress()) || response.getPort() != 5353) continue;
                // Some ROMs return an old SRV record and a renamed live record
                // together. A positive TTL does not prove the listener exists.
                int port = firstReachablePort(parseResponsePorts(buffer, response.getLength()), excluded);
                if (port > 0) return port;
            }
        } catch (IOException | SecurityException ignored) {
        }
        return -1;
    }

    static byte[] queryBytes() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeShort(QUERY_ID);
        out.writeShort(0);
        out.writeShort(1);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        for (String label : SERVICE.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.UTF_8);
            out.writeByte(bytes.length);
            out.write(bytes);
        }
        out.writeByte(0);
        out.writeShort(12); // PTR; legacy query requests a unicast response to our ephemeral port.
        out.writeShort(1);
        return buffer.toByteArray();
    }

    static int firstReachablePort(int[] ports) {
        return firstReachablePort(ports, new int[0]);
    }

    static int firstReachablePort(int[] ports, int[] excluded) {
        for (int port : ports) {
            if (java.util.Arrays.stream(excluded).anyMatch(p -> p == port)) continue;
            try (Socket socket = new Socket()) {
                // AdbStarter connects to loopback too. Never accept a cached
                // advertisement unless the same local endpoint is reachable.
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return port;
            } catch (IOException | SecurityException ignored) {
            }
        }
        return -1;
    }

    static int[] parseResponsePorts(byte[] data, int length) {
        Set<Integer> ports = new LinkedHashSet<>();
        try {
            if (length < 12 || length > data.length || u16(data, 0) != QUERY_ID
                    || (u16(data, 2) & 0x800f) != 0x8000) return new int[0];
            Cursor cursor = new Cursor(data, length);
            cursor.position = 12;
            int questions = u16(data, 4);
            int records = u16(data, 6) + u16(data, 8) + u16(data, 10);
            for (int i = 0; i < questions; i++) {
                cursor.name();
                cursor.skip(4);
            }
            for (int i = 0; i < records; i++) {
                String owner = cursor.name();
                int start = cursor.position;
                cursor.skip(10);
                int type = u16(data, start);
                int dnsClass = u16(data, start + 2) & 0x7fff;
                int size = u16(data, start + 8);
                int payload = cursor.position;
                cursor.skip(size);
                boolean live = data[start + 4] != 0 || data[start + 5] != 0
                        || data[start + 6] != 0 || data[start + 7] != 0;
                if (type == 33 && dnsClass == 1 && live && size >= 7
                        && owner.toLowerCase(Locale.ROOT).endsWith("." + SERVICE)) {
                    int port = u16(data, payload + 4);
                    // query() accepts packets only from this phone's address:5353,
                    // never a neighbor's service records.
                    if (port > 0) ports.add(port);
                }
            }
        } catch (IOException | IndexOutOfBoundsException ignored) {
            // Malformed/truncated replies must not abort the normal NSD fallback.
            return new int[0];
        }
        int[] result = new int[ports.size()];
        int i = 0;
        for (int port : ports) result[i++] = port;
        return result;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static final class Cursor {
        final byte[] data;
        final int limit;
        int position;

        Cursor(byte[] data, int limit) { this.data = data; this.limit = limit; }

        void skip(int count) throws IOException {
            if (count < 0 || position + count > limit) throw new IOException("Truncated DNS record");
            position += count;
        }

        String name() throws IOException {
            StringBuilder name = new StringBuilder();
            int offset = position;
            boolean jumped = false;
            for (int hops = 0; hops < 128; hops++) {
                if (offset >= limit) throw new IOException("Truncated DNS name");
                int size = data[offset++] & 0xff;
                if (size == 0) {
                    if (!jumped) position = offset;
                    return name.toString();
                }
                if ((size & 0xc0) == 0xc0) {
                    if (offset >= limit) throw new IOException("Truncated DNS pointer");
                    if (!jumped) position = offset + 1;
                    offset = ((size & 0x3f) << 8) | (data[offset] & 0xff);
                    jumped = true;
                } else {
                    if (size > 63 || offset + size > limit || name.length() + size > 253) throw new IOException("Invalid DNS label");
                    if (name.length() > 0) name.append('.');
                    name.append(new String(data, offset, size, StandardCharsets.UTF_8));
                    offset += size;
                }
            }
            throw new IOException("DNS pointer loop");
        }
    }
}
