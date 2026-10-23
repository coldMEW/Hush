package io.coldmew.hush;

import java.nio.ByteBuffer;
import java.util.List;

// Minimal IPv4/UDP/DNS packet helpers for the TUN interface.
final class Dns {
    static final class Query {
        byte[] srcIp, dstIp; int srcPort; byte[] dns; String name; int qtype; int qEnd;
    }

    static Query parse(byte[] p, int len) {
        if (len < 28 || (p[0] >> 4 & 0xF) != 4 || (p[9] & 0xFF) != 17) return null;
        int ihl = (p[0] & 0xF) * 4;
        if (len < ihl + 8 + 12) return null;
        int dport = (p[ihl + 2] & 0xFF) << 8 | (p[ihl + 3] & 0xFF);
        if (dport != 53) return null;
        Query q = new Query();
        q.srcIp = new byte[4]; q.dstIp = new byte[4];
        System.arraycopy(p, 12, q.srcIp, 0, 4); System.arraycopy(p, 16, q.dstIp, 0, 4);
        q.srcPort = (p[ihl] & 0xFF) << 8 | (p[ihl + 1] & 0xFF);
        q.dns = new byte[len - ihl - 8];
        System.arraycopy(p, ihl + 8, q.dns, 0, q.dns.length);
        StringBuilder sb = new StringBuilder();
        int i = 12;
        while (i < q.dns.length) {
            int l = q.dns[i] & 0xFF;
            if (l == 0) break;
            if ((l & 0xC0) != 0 || i + 1 + l > q.dns.length) return null;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(q.dns, i + 1, l, java.nio.charset.StandardCharsets.US_ASCII));
            i += 1 + l;
        }
        if (i + 5 > q.dns.length || sb.length() > 253) return null;
        q.name = sb.toString();
        q.qtype = (q.dns[i + 1] & 0xFF) << 8 | (q.dns[i + 2] & 0xFF);
        q.qEnd = i + 5;
        return q;
    }

    // Response with rcode and the given A records (records only added when the query is type A).
    static byte[] answer(Query q, int rcode, List<byte[]> ips) {
        int n = (q.qtype == 1 && ips != null) ? ips.size() : 0;
        ByteBuffer b = ByteBuffer.allocate(q.qEnd + n * 16);
        b.put(q.dns, 0, q.qEnd);
        b.put(2, (byte) 0x81); b.put(3, (byte) (0x80 | rcode));
        b.putShort(4, (short) 1); b.putShort(6, (short) n); b.putShort(8, (short) 0); b.putShort(10, (short) 0);
        for (int k = 0; k < n; k++) {
            b.putShort((short) 0xC00C); b.putShort((short) 1); b.putShort((short) 1);
            b.putInt(30); b.putShort((short) 4); b.put(ips.get(k));
        }
        return b.array();
    }

    // Wrap a DNS payload back to the querier: swap addresses and ports.
    static byte[] wrap(Query q, byte[] payload, int plen) {
        int total = 28 + plen;
        ByteBuffer b = ByteBuffer.allocate(total);
        b.put((byte) 0x45).put((byte) 0).putShort((short) total).putShort((short) 0).putShort((short) 0x4000)
         .put((byte) 64).put((byte) 17).putShort((short) 0).put(q.dstIp).put(q.srcIp);
        byte[] a = b.array();
        int sum = 0;
        for (int i = 0; i < 20; i += 2) sum += (a[i] & 0xFF) << 8 | (a[i + 1] & 0xFF);
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        b.putShort(10, (short) ~sum);
        b.putShort((short) 53).putShort((short) q.srcPort).putShort((short) (8 + plen)).putShort((short) 0);
        b.put(payload, 0, plen);
        return a;
    }
}
