package dev.organism.net;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Бинарный gossip-протокол v2. Sealed, ноль рефлексии, Native Image-safe. */
public sealed interface Wire {
    short MAGIC = 0x0A6E;

    enum Status { ALIVE, SUSPECT, DEAD }

    record Member(String node, int incarnation, Status status) {}

    byte[] encode();

    record NodeFacts(String node, String zone, int incarnation, long seq,
                     int cpuCores, int memFreeMb, int diskFreeMb,
                     double usdPerHour, int cells, double p99Ms) implements Wire {
        @Override public byte[] encode() {
            var b = buf((byte) 1, 512);
            str(b, node); str(b, zone);
            b.putInt(incarnation).putLong(seq)
             .putInt(cpuCores).putInt(memFreeMb).putInt(diskFreeMb)
             .putDouble(usdPerHour).putInt(cells).putDouble(p99Ms);
            return trim(b);
        }
    }

    record Ping(String from, int incarnation, long seq) implements Wire {
        @Override public byte[] encode() {
            var b = buf((byte) 2, 128); str(b, from); b.putInt(incarnation).putLong(seq); return trim(b);
        }
    }

    record Ack(String from, int incarnation, long seq) implements Wire {
        @Override public byte[] encode() {
            var b = buf((byte) 3, 128); str(b, from); b.putInt(incarnation).putLong(seq); return trim(b);
        }
    }

    record PingReq(String from, String target, int incarnation, long seq) implements Wire {
        @Override public byte[] encode() {
            var b = buf((byte) 4, 256); str(b, from); str(b, target); b.putInt(incarnation).putLong(seq); return trim(b);
        }
    }

    record Sync(String from, boolean pull, List<Member> members, List<NodeFacts> facts) implements Wire {
        public Sync(String from, boolean pull, List<Member> members) { this(from, pull, members, List.of()); }
        @Override public byte[] encode() {
            var b = buf((byte) 5, 64 + from.length() * 3 + members.size() * 96 + facts.size() * 192);
            str(b, from); b.put((byte) (pull ? 1 : 0)).putShort((short) members.size());
            for (var m : members) { str(b, m.node()); b.putInt(m.incarnation()).put((byte) m.status().ordinal()); }
            b.putShort((short) facts.size());
            for (var f : facts) {
                str(b, f.node()); str(b, f.zone());
                b.putInt(f.incarnation()).putLong(f.seq())
                 .putInt(f.cpuCores()).putInt(f.memFreeMb()).putInt(f.diskFreeMb())
                 .putDouble(f.usdPerHour()).putInt(f.cells()).putDouble(f.p99Ms());
            }
            return trim(b);
        }
    }

    /** M2: запрос почкования клетки на удалённом узле. */
    record BudRequest(String from, double load) implements Wire {
        @Override public byte[] encode() {
            var b = buf((byte) 6, 128);
            str(b, from);
            b.putDouble(load);
            return trim(b);
        }
    }

    static Wire decode(byte[] raw, int len) {
        var b = ByteBuffer.wrap(raw, 0, len);
        if (b.remaining() < 3 || b.getShort() != MAGIC) throw new IllegalArgumentException("bad magic");
        return switch (b.get()) {
            case 1 -> new NodeFacts(str(b), str(b), b.getInt(), b.getLong(),
                    b.getInt(), b.getInt(), b.getInt(), b.getDouble(), b.getInt(), b.getDouble());
            case 2 -> new Ping(str(b), b.getInt(), b.getLong());
            case 3 -> new Ack(str(b), b.getInt(), b.getLong());
            case 4 -> new PingReq(str(b), str(b), b.getInt(), b.getLong());
            case 5 -> {
                String from = str(b); boolean pull = b.get() != 0;
                int n = b.getShort() & 0xFFFF;
                if (n > 4096) throw new IllegalArgumentException("sync too big: " + n);
                var ms = new ArrayList<Member>(n);
                for (int i = 0; i < n; i++) {
                    String node = str(b); int inc = b.getInt(); int st = b.get();
                    if (st < 0 || st >= Status.values().length) throw new IllegalArgumentException("bad status " + st);
                    ms.add(new Member(node, inc, Status.values()[st]));
                }
                int fn = b.getShort() & 0xFFFF;
                if (fn > 4096) throw new IllegalArgumentException("facts too big: " + fn);
                var fs = new ArrayList<NodeFacts>(fn);
                for (int i = 0; i < fn; i++) {
                    fs.add(new NodeFacts(str(b), str(b), b.getInt(), b.getLong(),
                            b.getInt(), b.getInt(), b.getInt(), b.getDouble(), b.getInt(), b.getDouble()));
                }
                yield new Sync(from, pull, ms, fs);
            }
            case 6 -> new BudRequest(str(b), b.getDouble());
            default -> throw new IllegalArgumentException("unknown wire type");
        };
    }

    private static ByteBuffer buf(byte type, int cap) { return ByteBuffer.allocate(cap).putShort(MAGIC).put(type); }
    private static byte[] trim(ByteBuffer b) { return Arrays.copyOf(b.array(), b.position()); }
    private static void str(ByteBuffer b, String s) {
        byte[] u = s.getBytes(StandardCharsets.UTF_8);
        b.putShort((short) u.length).put(u);
    }
    private static String str(ByteBuffer b) {
        int n = b.getShort() & 0xFFFF;
        byte[] u = new byte[n]; b.get(u);
        return new String(u, StandardCharsets.UTF_8);
    }
}
