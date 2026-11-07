package moe.shizuku.manager.adb;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

public class LocalAdbDiscoveryTest {
    @Test public void skipsReachableButRejectedProtocolEndpoint() throws Exception {
        try (ServerSocket wrong = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             ServerSocket adb = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int[] candidates = {wrong.getLocalPort(), adb.getLocalPort()};
            assertEquals(adb.getLocalPort(), LocalAdbDiscovery.firstReachablePort(candidates,
                    new int[]{wrong.getLocalPort()}));
        }
    }
    @Test public void readsCompressedSrvInLegacyUnicastReply() throws Exception {
        byte[] packet = reply(39477, 120);
        assertArrayEquals(new int[]{39477}, LocalAdbDiscovery.parseResponsePorts(packet, packet.length));
    }

    @Test public void ignoresGoodbyeAndTruncatedReplies() throws Exception {
        byte[] goodbye = reply(39477, 0);
        assertArrayEquals(new int[0], LocalAdbDiscovery.parseResponsePorts(goodbye, goodbye.length));
        byte[] packet = reply(39477, 120);
        for (int i = 0; i < packet.length - 1; i++) {
            assertArrayEquals(new int[0], LocalAdbDiscovery.parseResponsePorts(packet, i));
        }
    }

    @Test public void rejectsCompressionPointerLoop() {
        byte[] packet = {0x14,0x17,(byte)0x84,0,0,1,0,0,0,0,0,0,(byte)0xc0,12,0,12,0,1};
        assertArrayEquals(new int[0], LocalAdbDiscovery.parseResponsePorts(packet, packet.length));
    }

    @Test public void skipsStaleFirstRecordAndUsesLiveRenamedService() throws Exception {
        try (ServerSocket live = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int stalePort;
            try (ServerSocket stale = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                stalePort = stale.getLocalPort();
            }
            byte[] packet = reply(stalePort, 10);
            packet[7] = 2;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            buffer.write(packet);
            writeSrv(new DataOutputStream(buffer), "adb-self (2)", live.getLocalPort(), 10);
            byte[] combined = buffer.toByteArray();
            int[] ports = LocalAdbDiscovery.parseResponsePorts(combined, combined.length);
            assertArrayEquals(new int[]{stalePort, live.getLocalPort()}, ports);
            assertEquals(live.getLocalPort(), LocalAdbDiscovery.firstReachablePort(ports));
        }
    }

    private static byte[] reply(int port, int ttl) throws Exception {
        byte[] question = LocalAdbDiscovery.queryBytes();
        question[2] = (byte) 0x84;
        question[7] = 1;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(question);
        writeSrv(new DataOutputStream(buffer), "adb-self", port, ttl);
        return buffer.toByteArray();
    }

    private static void writeSrv(DataOutputStream out, String name, int port, int ttl) throws Exception {
        out.writeByte(name.length());
        out.writeBytes(name);
        out.writeShort(0xc00c); // Service type references the question.
        out.writeShort(33);
        out.writeShort(1);
        out.writeInt(ttl);
        out.writeShort(7);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(port);
        out.writeByte(0);
    }
}
