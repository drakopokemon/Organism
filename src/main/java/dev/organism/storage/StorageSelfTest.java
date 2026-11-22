package dev.organism.storage;

import dev.organism.net.Membership;
import dev.organism.net.Wire;
import java.util.*;

/**
 * Детерминированный selftest Storage-слоя.
 * java -cp out dev.organism.storage.StorageSelfTest
 */
public final class StorageSelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-62s %s%n", ok ? "OK " : "FAIL", name, detail);
    }

    static final class FakeNet implements Membership.Transport {
        final Map<String, Membership> nodes = new TreeMap<>();
        final Map<String, StorageHost> hosts = new TreeMap<>();
        long delivered;
        @Override public void send(String to, Wire msg) {
            if (!nodes.containsKey(to)) return;
            delivered++;
            var m = nodes.get(to);
            if (msg instanceof Wire.ReplicateRequest rr) {
                hosts.get(to).onReplicateRequest(rr);
            } else {
                m.onMessage(msg);
            }
        }
    }

    static void build(FakeNet net, int n, long seed) {
        for (int i = 1; i <= n; i++) {
            String id = "10.0.0." + i + ":7100";
            var m = new Membership(id, seed + i, net, (t, node, f, to, inc) -> {});
            var h = new StorageHost(m, req -> {
                for (var to : net.nodes.keySet()) {
                    if (!to.equals(id)) net.send(to, req);
                }
            });
            net.nodes.put(id, m);
            net.hosts.put(id, h);
        }
        for (var m : net.nodes.values()) {
            for (var id : net.nodes.keySet()) m.addSeed(id);
        }
    }

    static void rounds(FakeNet net, int k) {
        for (int i = 0; i < k; i++) {
            for (var m : net.nodes.values()) m.tick();
            for (var h : net.hosts.values()) h.tick(i);
        }
    }

    public static void main(String[] args) {
        System.out.println("\n  ORGANISM storage selftest (replication, deterministic)\n");

        // 1. Запись реплицируется на все узлы
        var net = new FakeNet();
        build(net, 3, 42);
        rounds(net, 10);
        var h1 = net.hosts.get("10.0.0.1:7100");
        long v1 = h1.write(0, new byte[]{1, 2, 3});
        rounds(net, 5);
        long v2 = net.hosts.get("10.0.0.2:7100").version(0);
        long v3 = net.hosts.get("10.0.0.3:7100").version(0);
        check("write replicates to all nodes", v2 == v1 && v3 == v1,
                "v1=" + v1 + " v2=" + v2 + " v3=" + v3);

        // 2. Много записей: все версии синхронизированы
        for (int i = 0; i < 10; i++) {
            h1.write(5, new byte[]{(byte) i});
        }
        rounds(net, 10);
        long vFinal = net.hosts.get("10.0.0.1:7100").version(5);
        boolean allSync = true;
        for (var h : net.hosts.values()) {
            if (h.version(5) != vFinal) allSync = false;
        }
        check("multiple writes: all nodes synced", allSync && vFinal == 10,
                "final version=" + vFinal);

        // 3. Детерминизм: два прогона идентичны
        var net1 = new FakeNet(); build(net1, 2, 99);
        var net2 = new FakeNet(); build(net2, 2, 99);
        rounds(net1, 5); rounds(net2, 5);
        net1.hosts.get("10.0.0.1:7100").write(0, new byte[]{1});
        net2.hosts.get("10.0.0.1:7100").write(0, new byte[]{1});
        rounds(net1, 5); rounds(net2, 5);
        check("determinism: two runs identical",
                net1.hosts.get("10.0.0.1:7100").version(0) == net2.hosts.get("10.0.0.1:7100").version(0),
                "");

        // 4. 24 шарда
        check("24 shards initialized", h1.shardCount() == 24, "shards=" + h1.shardCount());

        System.out.println(failed == 0 ? "\n  ALL GREEN\n" : "\n  " + failed + " FAILED\n");
        System.exit(failed == 0 ? 0 : 1);
    }
}
