package dev.organism;

import java.util.*;

/** Самопроверка без внешних зависимостей: ./run.sh --selftest */
final class SelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-58s %s%n", ok ? Main.G + "✔" + Main.R : Main.X + "✘" + Main.R, name, Main.D + detail + Main.R);
    }

    static boolean run() throws Exception {
        System.out.println(Main.C + "\n  ORGANISM selftest" + Main.R + "\n");
        int T = 740;   // покрывает полный цикл хаоса (2 падения узла, яд, воскрешения) и начало следующего

        // 1. инварианты на каждом тике
        var g = Genomes.shopApi();
        String violation = null; int minCells = 999, maxCells = 0, lastCells = 0; double maxCost = 0;
        try (var o = new Organism(g, 42, new Gate.Policy(), true, true, null)) {
            for (int t = 0; t < T && violation == null; t++) {
                o.tick(t);
                minCells = Math.min(minCells, o.cells.size()); maxCells = Math.max(maxCells, o.cells.size()); lastCells = o.cells.size(); maxCost = Math.max(maxCost, o.sub.activeCost());
                for (var c : o.cells.values()) {
                    var n = o.sub.nodes.get(c.node);
                    if (!g.zoneAllowed(n.spec.zone())) violation = "t=%d клетка %s в запрещённой зоне %s".formatted(t, c.id, n.spec.zone());
                    else if (!n.alive) violation = "t=%d клетка %s на мёртвом узле".formatted(t, c.id);
                    else if (g.violation(c.genes).isPresent()) violation = "t=%d клетка %s нарушает ограничение: %s".formatted(t, c.id, g.violation(c.genes).get());
                    else if (o.sub.freeMem(n) < 0) violation = "t=%d узел %s: память превышена".formatted(t, n.spec.id());
                }
                if (o.sub.activeCost() > g.maxCost() + 1e-9) violation = "t=%d стоимость %.2f > %.2f".formatted(t, o.sub.activeCost(), g.maxCost());
                if (o.cells.size() > g.maxReplicas()) violation = "t=%d клеток больше максимума".formatted(t);
            }
        }
        check("инварианты генома соблюдены на всех тиках", violation == null,
              violation != null ? violation : "клеток %d..%d, макс. стоимость $%.2f/ч (лимит $%.2f)".formatted(minCells, maxCells, maxCost, g.maxCost()));
        check("популяция восстанавливается после катастроф до минимума", lastCells >= g.minReplicas(), "провал до %d при падении узла, сейчас %d".formatted(minCells, lastCells));

        // 2. детерминизм: тот же seed => тот же прогон
        double[] a, b; Map<String, Integer> ca, cb;
        try (var o1 = new Organism(g, 7, new Gate.Policy(), true, true, null); var o2 = new Organism(g, 7, new Gate.Policy(), true, true, null)) {
            for (int t = 0; t < 400; t++) { o1.tick(t); o2.tick(t); }
            a = o1.summary(0, 400); b = o2.summary(0, 400); ca = o1.counts; cb = o2.counts;
        }
        check("детерминизм: два прогона с одним seed идентичны", Arrays.equals(a, b) && ca.equals(cb), "fitness=%.4f / %.4f".formatted(a[0], b[0]));

        // 3. эволюция лучше статики на одном и том же мире
        double[] st, ev; Storage.Stats ss, es;
        try (var s0 = new Organism(g, 42, new Gate.Policy(), false, true, null); var s1 = new Organism(g, 42, new Gate.Policy(), true, true, null)) {
            for (int t = 0; t < T; t++) { s0.tick(t); s1.tick(t); }
            st = s0.summary(100, T); ev = s1.summary(100, T); ss = s0.store.stats(); es = s1.store.stats();
        }
        check("ORGANISM лучше статики по fitness", ev[0] > st[0] + 0.1, "%.3f vs %.3f".formatted(ev[0], st[0]));
        check("ORGANISM реже нарушает SLA по p99", ev[1] < st[1] - 20, "%.1f%% vs %.1f%%".formatted(ev[1], st[1]));
        check("ORGANISM дешевле за 1M запросов", ev[3] < st[3], "$%.2f vs $%.2f".formatted(ev[3], st[3]));

        // 4. данные: два падения узла — ни одного потерянного шарда, ни одной потерянной записи, всё починено
        check("данные: ни один шард не потерян при падении узлов", es.lost() == 0 && ss.lost() == 0, "lost=" + es.lost());
        check("данные: подтверждённые записи не потеряны", es.writesLost() == 0, "writesLost=" + es.writesLost());
        check("данные: все шарды снова полностью реплицированы", es.healthy() == es.shards(), "%d/%d healthy, repaired=%d".formatted(es.healthy(), es.shards(), es.repaired()));

        // 5. данные: одновременная потеря всех реплик шарда => честно фиксируется потеря (без ложных обещаний)
        try (var o = new Organism(g, 1, new Gate.Policy(), true, false, null)) {
            for (int t = 0; t < 10; t++) o.tick(t);
            var evs = new ArrayList<Event>();
            for (var r : new ArrayList<>(o.store.shards.get(0).replicas.values())) { o.sub.kill(r.node); o.store.nodeDown(r.node, 10, evs); }
            check("данные: потеря всех реплик шарда детектируется как DATA_LOST",
                  evs.stream().anyMatch(e -> e.type().equals("DATA_LOST")) && o.store.stats().lost() >= 1, "lost=" + o.store.stats().lost());
        }

        // 6. Symbiont: реальный процесс поднимается, переживает kill -9
        try (var s = new Symbiont("selftest", Symbiont.defaultCommand())) {
            s.start();
            boolean up = waitFor(() -> s.state == Symbiont.State.HEALTHY, 20_000);
            check("symbiont: внешний процесс запущен и здоров", up, "port=" + s.port);
            boolean reply = up && s.call("/work", 2000).ok();
            check("symbiont: запрос проксируется во внешний процесс", reply, "");
            s.crash();
            boolean back = waitFor(() -> s.restarts.get() >= 1 && s.state == Symbiont.State.HEALTHY, 25_000);
            check("symbiont: после kill -9 процесс перезапущен автоматически", back, "restarts=" + s.restarts.get());
        }

        System.out.println(failed == 0 ? "\n  " + Main.G + "ВСЁ ЗЕЛЁНОЕ" + Main.R + "\n" : "\n  " + Main.X + failed + " проверок провалено" + Main.R + "\n");
        return failed == 0;
    }

    private static boolean waitFor(java.util.function.BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (c.getAsBoolean()) return true; Thread.sleep(100); }
        return c.getAsBoolean();
    }
}
