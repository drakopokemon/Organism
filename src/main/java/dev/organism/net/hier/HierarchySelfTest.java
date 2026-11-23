package dev.organism.net.hier;

import dev.organism.net.Membership;
import dev.organism.net.Wire;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Детерминированный selftest столпа 5.
 * Метрика — WAN-пересечения (цена/RTT/loss), НЕ суммарная асимптотика: SWIM и так infection-style.
 * churn 0.5%/тик; конвергенция rumor должна сохраняться, WAN-трафик — падать кратно.
 */
public final class HierarchySelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-62s %s%n", ok ? "OK " : "FAIL", name, detail);
    }

    static int churnSeq = 10000;
    static void churn(List<String> alive, Map<String, String> explicit, List<String> regions, SplittableRandom rnd) {
        for (int i = 0; i < 2; i++) {
            if (alive.size() > 50) alive.remove(rnd.nextInt(alive.size()));
            String id = "n" + (churnSeq++);
            explicit.put(id, regions.get(rnd.nextInt(regions.size())));
            alive.add(id);
        }
    }

    static List<String> pickRandom(List<String> alive, String src, int k, SplittableRandom rnd) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            String t = alive.get(rnd.nextInt(alive.size()));
            if (!t.equals(src)) out.add(t);
        }
        return out;
    }

    public static void main(String[] args) {
        int n = 400, regions = 8, ticks = 150, k = 4;
        List<String> regionNames = new ArrayList<>();
        for (int i = 0; i < regions; i++) regionNames.add("r" + i);
        Map<String, String> explicit = new HashMap<>();
        List<String> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = "node" + i;
            nodes.add(id);
            explicit.put(id, regionNames.get(i % regions));
        }
        SplittableRandom rnd = new SplittableRandom(7);

        long wanFlat = 0, msgFlat = 0;
        Set<String> knowFlat = new HashSet<>();
        knowFlat.add("node0");
        List<String> aliveFlat = new ArrayList<>(nodes);
        for (int t = 0; t < ticks; t++) {
            churn(aliveFlat, explicit, regionNames, rnd);
            Set<String> newly = new HashSet<>();
            for (String src : new ArrayList<>(aliveFlat)) {
                for (String dst : pickRandom(aliveFlat, src, k, rnd)) {
                    msgFlat++;
                    if (!explicit.get(src).equals(explicit.get(dst))) wanFlat++;
                    if (knowFlat.contains(src)) newly.add(dst);
                }
            }
            knowFlat.addAll(newly);
            knowFlat.retainAll(new HashSet<>(aliveFlat));
        }

        long wanHier = 0, msgHier = 0;
        Set<String> knowHier = new HashSet<>();
        knowHier.add("node0");
        List<String> aliveHier = new ArrayList<>(nodes);
        for (int t = 0; t < ticks; t++) {
            churn(aliveHier, explicit, regionNames, rnd);
            List<String> supers = Hierarchy.supernodes(aliveHier, explicit, regionNames, 2);
            Set<String> newly = new HashSet<>();
            for (String src : new ArrayList<>(aliveHier)) {
                for (String dst : Hierarchy.targets(src, aliveHier, explicit, regionNames, supers, rnd, k, 2)) {
                    msgHier++;
                    if (!explicit.get(src).equals(explicit.get(dst))) wanHier++;
                    if (knowHier.contains(src)) newly.add(dst);
                }
            }
            knowHier.addAll(newly);
            knowHier.retainAll(new HashSet<>(aliveHier));
        }

        double covFlat = knowFlat.size() / (double) aliveFlat.size();
        double covHier = knowHier.size() / (double) aliveHier.size();
        System.out.printf("  flat: msgs=%d wan=%d coverage=%.3f%n", msgFlat, wanFlat, covFlat);
        System.out.printf("  hier: msgs=%d wan=%d coverage=%.3f%n", msgHier, wanHier, covHier);
        check("WAN traffic cut >= 4x (churn/WAN rationale)", wanHier * 4 < wanFlat, "flat=" + wanFlat + " hier=" + wanHier);
        check("flat converges under churn", covFlat >= 0.90, "cov=" + covFlat);
        check("hierarchical converges under churn", covHier >= 0.90, "cov=" + covHier);
        check("supernodes bounded per region", Hierarchy.supernodes(nodes, explicit, regionNames, 2).size() == regions * 2, "");
        List<String> supers = Hierarchy.supernodes(nodes, explicit, regionNames, 2);
        List<String> minus = new ArrayList<>(nodes);
        for (String x : nodes) if (!supers.contains(x)) { minus.remove(x); break; }
        check("non-supernode churn keeps bridges stable", Hierarchy.supernodes(minus, explicit, regionNames, 2).equals(supers), "");

        Map<String, Membership> net = new HashMap<>();
        Membership a = new Membership("a1", 1, (to, msg) -> { Membership d = net.get(to); if (d != null) d.onMessage(msg); }, (tick, node, from, to, inc) -> { });
        Membership b = new Membership("b1", 2, (to, msg) -> { Membership d = net.get(to); if (d != null) d.onMessage(msg); }, (tick, node, from, to, inc) -> { });
        net.put("a1", a);
        net.put("b1", b);
        a.setFacts(new Wire.NodeFacts("a1", "regionA", 0, 1, 4, 1024, 10000, 0.1, 3, 42.0));
        b.setFacts(new Wire.NodeFacts("b1", "regionB", 0, 1, 8, 2048, 20000, 0.2, 5, 55.0));
        long[] wanCount = {0};
        List<Wire.Sync> inboxB = new ArrayList<>(), inboxA = new ArrayList<>();
        RegionBridge bridgeA = new RegionBridge(a, "regionA", 5, (remoteRegion, summary) -> { wanCount[0]++; inboxB.add(summary); });
        RegionBridge bridgeB = new RegionBridge(b, "regionB", 5, (remoteRegion, summary) -> { wanCount[0]++; inboxA.add(summary); });
        for (int t = 0; t < 30; t++) {
            a.tick();
            b.tick();
            bridgeA.tick();
            bridgeB.tick();
            for (Wire.Sync s : inboxB) bridgeB.onRemote(s);
            inboxB.clear();
            for (Wire.Sync s : inboxA) bridgeA.onRemote(s);
            inboxA.clear();
        }
        check("bridge propagates membership across WAN", a.view().containsKey("b1") && b.view().containsKey("a1"), "");
        check("bridge propagates facts", a.facts("b1") != null && "regionB".equals(a.facts("b1").zone()), "");
        check("WAN summaries bounded by period", wanCount[0] <= 2 * (30 / 5 + 1), "wan=" + wanCount[0]);

        System.out.println(failed == 0 ? "HIERARCHY SELFTEST PASSED" : "HIERARCHY SELFTEST FAILED: " + failed);
        if (failed > 0) System.exit(1);
    }
}
