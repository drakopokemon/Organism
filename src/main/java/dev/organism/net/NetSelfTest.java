package dev.organism.net;

import java.util.*;

/** Детерминированный selftest сети БЕЗ сокетов. java -cp out dev.organism.net.NetSelfTest */
public final class NetSelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-62s %s%n", ok ? "OK " : "FAIL", name, detail);
    }

    static final class FakeNet implements Membership.Transport {
        final TreeMap<String, Membership> nodes = new TreeMap<>();
        final TreeSet<String> down = new TreeSet<>();
        TreeSet<String> sideA = new TreeSet<>();
        long delivered, dropped;
        @Override public void send(String to, Wire msg) {
            String from = switch (msg) {
                case Wire.Ping p -> p.from();
                case Wire.Ack a -> a.from();
                case Wire.PingReq r -> r.from();
                case Wire.Sync s -> s.from();
                case Wire.NodeFacts f -> f.node();
            };
            if (down.contains(from) || down.contains(to) || blocked(from, to) || !nodes.containsKey(to)) { dropped++; return; }
            delivered++;
            nodes.get(to).onMessage(msg);
        }
        boolean blocked(String a, String b) {
            if (sideA.isEmpty()) return false;
            return sideA.contains(a) != sideA.contains(b);
        }
    }

    static List<String> build(FakeNet net, int n, long seed) {
        var log = new ArrayList<String>();
        for (int i = 1; i <= n; i++) {
            String id = "10.0.0." + i + ":7100";
            net.nodes.put(id, new Membership(id, seed + i, net,
                    (t, node, f, to, inc) -> log.add(t + "|" + node + "|" + f + ">" + to + "|" + inc)));
        }
        for (var m : net.nodes.values()) for (var id : net.nodes.keySet()) m.addSeed(id);
        return log;
    }
    static void rounds(FakeNet net, int k) {
        for (int i = 0; i < k; i++)
            for (var e : net.nodes.entrySet())
                if (!net.down.contains(e.getKey())) e.getValue().tick();
    }
    static boolean allSee(FakeNet net, String target, Wire.Status st) {
        for (var e : net.nodes.entrySet())
            if (!net.down.contains(e.getKey()) && !e.getKey().equals(target) && e.getValue().status(target) != st)
                return false;
        return true;
    }
    static boolean allAliveEverywhere(FakeNet net) {
        for (var e : net.nodes.entrySet()) {
            if (net.down.contains(e.getKey())) continue;
            for (var s : e.getValue().view().values()) if (s != Wire.Status.ALIVE) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        System.out.println("\n  ORGANISM net selftest (SWIM-lite, deterministic)\n");

        var net = new FakeNet();
        var log = build(net, 5, 42);
        rounds(net, 60);
        boolean noFalse = log.stream().noneMatch(s -> s.contains(">SUSPECT") || s.contains(">DEAD"));
        check("healthy cluster 60 ticks: zero false-positive", noFalse && allAliveEverywhere(net),
                "delivered=" + net.delivered);

        String dead = "10.0.0.3:7100";
        net.down.add(dead);
        rounds(net, 25);
        check("node kill: all alive nodes see DEAD <= 25 ticks", allSee(net, dead, Wire.Status.DEAD), "");

        net.down.remove(dead);
        rounds(net, 25);
        check("node restart: incarnation refute, all ALIVE again", allAliveEverywhere(net),
                "incarnation=" + net.nodes.get(dead).incarnation());

        var net2 = new FakeNet();
        build(net2, 5, 7);
        rounds(net2, 20);
        net2.sideA = new TreeSet<>(List.of("10.0.0.1:7100", "10.0.0.2:7100"));
        rounds(net2, 30);
        boolean split = net2.nodes.get("10.0.0.1:7100").status("10.0.0.5:7100") == Wire.Status.DEAD
                && net2.nodes.get("10.0.0.5:7100").status("10.0.0.1:7100") == Wire.Status.DEAD;
        check("partition 2|3: both sides see each other DEAD", split, "");
        net2.sideA.clear();
        rounds(net2, 40);
        check("heal: cluster fully converges to ALIVE", allAliveEverywhere(net2), "");

        var netA = new FakeNet(); var logA = build(netA, 5, 99);
        var netB = new FakeNet(); var logB = build(netB, 5, 99);
        rounds(netA, 30); rounds(netB, 30);
        netA.down.add("10.0.0.2:7100"); netB.down.add("10.0.0.2:7100");
        rounds(netA, 20); rounds(netB, 20);
        check("determinism: two runs bit-identical event log", logA.equals(logB), "events=" + logA.size());

        var net3 = new FakeNet(); build(net3, 5, 1);
        net3.nodes.get("10.0.0.1:7100").setFacts(
                new Wire.NodeFacts("10.0.0.1:7100", "eu-1", 0, 1, 8, 4096, 100000, 0.1, 3, 42.0));
        rounds(net3, 30);
        boolean facts = true;
        for (var e : net3.nodes.entrySet()) {
            var f = e.getValue().facts("10.0.0.1:7100");
            if (f == null || f.memFreeMb() != 4096 || !f.zone().equals("eu-1")) facts = false;
        }
        check("NodeFacts propagate to all nodes", facts, "");

        var msgs = List.of(
                new Wire.Ping("n1:1", 3, 77),
                new Wire.Ack("n2:2", 4, 77),
                new Wire.PingReq("n1:1", "n3:3", 3, 78),
                new Wire.Sync("n1:1", true, List.of(new Wire.Member("n2:2", 1, Wire.Status.SUSPECT))),
                new Wire.NodeFacts("n1:1", "eu-1", 2, 9, 8, 512, 1024, 0.25, 4, 12.5));
        boolean rt = true;
        for (var m : msgs) { byte[] b = m.encode(); rt &= Wire.decode(b, b.length).equals(m); }
        check("wire encode/decode roundtrip all 5 types", rt, "");

        System.out.println(failed == 0 ? "\n  ALL GREEN\n" : "\n  " + failed + " FAILED\n");
        System.exit(failed == 0 ? 0 : 1);
    }
}
