package dev.organism.net.hier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * Столп 5 на масштабе: 5000 узлов / 25 регионов, churn 0.5%/тик.
 * ЧЕСТНО: это оверлейная симуляция (детерминированная, seeded), а не 5000 живых процессов.
 * Меряем то, что и обещает иерархия: локализацию WAN-трафика, ограниченный fanout мостов,
 * стабильность супернод при churn и сохранение представительства регионов.
 * Сходимость самого SWIM проверяется отдельно (NetSelfTest / HierarchySelfTest).
 */
public final class ScaleSelfTest {
    private static int failed, total;

    private static void check(String name, boolean ok, String detail) {
        total++;
        if (!ok) failed++;
        System.out.printf("  %s %-62s %s%n", ok ? "OK " : "FAIL", name, detail);
    }

    public static void main(String[] args) {
        System.out.println("\n  ORGANISM scale selftest (hierarchical gossip, 5000 nodes / 25 regions)\n");
        long t0 = System.nanoTime();

        int regionsN = 25, perRegion = 200, superPerRegion = 3, ticks = 20, samplePerTick = 200;
        int kIntra = 3, kBridge = 2;
        List<String> regions = new ArrayList<>();
        for (int r = 0; r < regionsN; r++) regions.add("region-" + r);

        Map<String, String> explicit = new HashMap<>();
        List<String> alive = new ArrayList<>();
        for (int r = 0; r < regionsN; r++)
            for (int i = 0; i < perRegion; i++) {
                String id = "10." + r + "." + (i / 250) + ":" + (7100 + i % 250) + "#" + r + "-" + i;
                explicit.put(id, regions.get(r));
                alive.add(id);
            }
        int n0 = alive.size();
        var rnd = new SplittableRandom(2026);
        List<String> supers = Hierarchy.supernodes(alive, explicit, regions, superPerRegion);
        check("5000 nodes, 3 supernodes per region", n0 == 5000 && supers.size() == regionsN * superPerRegion,
                "nodes=" + n0 + " supers=" + supers.size());

        long hierMsgs = 0, hierWan = 0, flatMsgs = 0, flatWan = 0, fanoutSum = 0, fanoutN = 0;
        double stabilitySum = 0;
        int stabilityN = 0, churnRemoved = 0;

        for (int t = 1; t <= ticks; t++) {
            Set<String> superSet = new HashSet<>(supers);
            for (int s = 0; s < samplePerTick; s++) {
                String self = alive.get(rnd.nextInt(alive.size()));
                String myRegion = explicit.get(self);
                var targets = Hierarchy.targets(self, alive, explicit, regions, supers, rnd, kIntra, kBridge);
                fanoutSum += targets.size();
                fanoutN++;
                for (String dst : targets) {
                    hierMsgs++;
                    if (!explicit.get(dst).equals(myRegion)) hierWan++;
                }
                for (int j = 0; j < kIntra; j++) {
                    String dst = alive.get(rnd.nextInt(alive.size()));
                    flatMsgs++;
                    if (!explicit.get(dst).equals(myRegion)) flatWan++;
                }
                if (superSet.contains(self)) {
                    long bridges = targets.stream().filter(d -> !explicit.get(d).equals(myRegion)).count();
                    if (bridges > regionsN - 1) throw new IllegalStateException("bridge fanout explosion");
                }
            }
            // churn 0.5%/тик
            int kill = Math.max(1, alive.size() / 200);
            Set<String> before = new HashSet<>(supers);
            for (int c = 0; c < kill; c++) alive.remove(rnd.nextInt(alive.size()));
            churnRemoved += kill;
            var next = Hierarchy.supernodes(alive, explicit, regions, superPerRegion);
            int kept = 0;
            for (String s : next) if (before.contains(s)) kept++;
            stabilitySum += kept / (double) Math.max(1, before.size());
            stabilityN++;
            supers = next;
        }

        double hierShare = hierWan / (double) hierMsgs;
        double flatShare = flatWan / (double) flatMsgs;
        double avgFanout = fanoutSum / (double) fanoutN;
        double stability = stabilitySum / stabilityN;

        check("hierarchical WAN share < 5%", hierShare < 0.05, String.format("hier=%.4f (%d/%d)", hierShare, hierWan, hierMsgs));
        check("flat SWIM WAN share > 90% (baseline)", flatShare > 0.90, String.format("flat=%.4f (%d/%d)", flatShare, flatWan, flatMsgs));
        check("WAN traffic reduction > 10x", flatShare / Math.max(hierShare, 1e-9) > 10.0,
                String.format("x%.1f", flatShare / Math.max(hierShare, 1e-9)));
        check("bounded fanout (kIntra + bridges only on supernodes)", avgFanout <= kIntra + 0.2,
                String.format("avg=%.3f", avgFanout));
        check("supernodes stable under 0.5%/tick churn", stability > 0.90, String.format("stability=%.3f", stability));

        Map<String, Integer> byRegion = new TreeMap<>();
        for (String s : supers) byRegion.merge(explicit.get(s), 1, Integer::sum);
        check("every region keeps a bridge after churn", byRegion.size() == regionsN, "regions=" + byRegion.size());
        check("fleet still alive after churn", alive.size() == n0 - churnRemoved && alive.size() > 4400,
                "alive=" + alive.size() + " removed=" + churnRemoved);

        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("scale simulation bounded in time", ms < 60000, "elapsedMs=" + ms);

        System.out.println(failed == 0 ? "\nSCALE SELFTEST PASSED (" + total + " checks, 5000 nodes, " + ms + " ms)"
                : "\nSCALE SELFTEST FAILED: " + failed + "/" + total);
        if (failed > 0) System.exit(1);
    }
}
