package dev.organism.cell;

import java.util.*;

/** Детерминированный selftest эволюции и иммунитета на стабах без сети. */
public final class CellSelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-62s %s%n", ok ? "OK " : "FAIL", name, detail);
    }

    static final class Stub implements CellCtl {
        final String id; final ArrayDeque<Double> script; final double drop; final long served;
        boolean q;
        Stub(String id, double[] p99s, double drop, long served) {
            this.id = id; this.drop = drop; this.served = served;
            this.script = new ArrayDeque<>();
            for (double v : p99s) script.add(v);
        }
        @Override public String id() { return id; }
        @Override public double p99() { if (script.size() > 1) return script.poll(); return script.peek(); }
        @Override public double dropRate() { return drop; }
        @Override public long served() { return served; }
        @Override public boolean quarantined() { return q; }
        @Override public void quarantine(boolean v) { q = v; }
    }

    static final class Fx implements Evolver.Factory, LocalImmune.Factory {
        Stub canaryStub; Cell.Genes applied; int kills, respawns;
        @Override public CellCtl spawn(Cell.Genes g, boolean canary) { return canaryStub; }
        @Override public void kill(CellCtl c) { kills++; }
        @Override public void applyAll(Cell.Genes g) { applied = g; }
        @Override public CellCtl respawn() { respawns++; return new Stub("respawned", new double[]{10}, 0, 0); }
    }

    static Cell.Genes runEvolution(long seed, double canaryP99, Fx fx) {
        var ev = new Evolver(new Cell.Genes(2, 512, 64, 500), seed, fx, 120);
        fx.canaryStub = new Stub("canary", new double[]{canaryP99}, 0.0, 500);
        var ctrl = new Stub("ctrl", new double[]{100}, 0.0, 500);
        for (long t = 1; t <= Evolver.WINDOW + 2; t++) ev.tick(t, List.of((CellCtl) ctrl));
        return ev.current();
    }

    public static void main(String[] args) {
        System.out.println("\n  ORGANISM cell selftest (evolution + immune, deterministic)\n");

        var fx1 = new Fx();
        var g1 = runEvolution(42, 40, fx1);
        check("good mutation adopted (canary p99 40 vs 100)",
                fx1.applied != null && !fx1.applied.equals(new Cell.Genes(2, 512, 64, 500)),
                "genes=" + fx1.applied);

        var fx2 = new Fx();
        var g2 = runEvolution(42, 300, fx2);
        check("bad mutation reverted (canary p99 300 vs 100)",
                fx2.applied != null && fx2.applied.equals(new Cell.Genes(2, 512, 64, 500)),
                "genes=" + fx2.applied);

        var fx3 = new Fx();
        var ev3 = new Evolver(new Cell.Genes(2, 512, 64, 500), 42, fx3, 120);
        var fx4 = new Fx();
        var ev4 = new Evolver(new Cell.Genes(2, 512, 64, 500), 42, fx4, 120);
        fx3.canaryStub = new Stub("c", new double[]{40}, 0, 500);
        fx4.canaryStub = new Stub("c", new double[]{40}, 0, 500);
        var c3 = new Stub("k", new double[]{100}, 0, 500);
        var c4 = new Stub("k", new double[]{100}, 0, 500);
        for (long t = 1; t <= 12; t++) {
            ev3.tick(t, List.of((CellCtl) c3));
            ev4.tick(t, List.of((CellCtl) c4));
        }
        check("determinism: two runs identical",
                ev3.decisions().equals(ev4.decisions()), "decisions=" + ev3.decisions().size());

        var fxi = new Fx();
        var im = new LocalImmune(fxi);
        var sick = new Stub("sick", new double[]{1000}, 0.0, 500);
        var cells = new ArrayList<CellCtl>(); cells.add(sick);
        long t = 0;
        while (t < 20 && !sick.quarantined()) im.tick(++t, cells, 100);
        check("immune: sick cell quarantined in <= 4 ticks",
                sick.quarantined() && t <= 4, "tick=" + t);
        while (t < 40 && fxi.respawns == 0) im.tick(++t, cells, 100);
        check("immune: unrecovered cell killed and replaced",
                fxi.kills >= 1 && fxi.respawns >= 1,
                "kills=" + fxi.kills + " respawns=" + fxi.respawns);

        var healthy = new Stub("h", new double[]{50}, 0.0, 500);
        var im2 = new LocalImmune(new Fx());
        var cs2 = new ArrayList<CellCtl>(); cs2.add(healthy);
        for (long i = 1; i <= 30; i++) im2.tick(i, cs2, 100);
        check("immune: healthy cell untouched in 30 ticks",
                !healthy.quarantined() && im2.events().isEmpty(), "");

        System.out.println(failed == 0 ? "\n  ALL GREEN\n" : "\n  " + failed + " FAILED\n");
        System.exit(failed == 0 ? 0 : 1);
    }
}
