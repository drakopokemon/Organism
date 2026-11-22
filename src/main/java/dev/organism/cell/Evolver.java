package dev.organism.cell;

import java.util.*;

/** (1+1)-ES с канарейкой: окно оценки, статпорог в fitness-пространстве, откат, кулдаун. */
public final class Evolver {
    public interface Factory {
        CellCtl spawn(Cell.Genes g, boolean canary);
        void kill(CellCtl c);
        void applyAll(Cell.Genes g);
    }
    public record Decision(long tick, String gene, int from, int to, boolean adopted, String why) {}

    public static final int WINDOW = 10, COOLDOWN = 15;
    private static final double ACCEPT = 0.05;
    private static final String[] GENES = {"pool", "queueLimit", "cacheMb", "timeoutMs"};
    private static final int[] STEP = {1, 16, 32, 50};
    private static final int[] MIN = {1, 16, 32, 100};
    private static final int[] MAX = {8, 2048, 1024, 2000};

    private Cell.Genes base;
    private final SplittableRandom rnd;
    private final Factory fx;
    private final double targetP99;
    private final List<Decision> log = new ArrayList<>();
    private int rr;
    private CellCtl canary;
    private int windowLeft, cooldown, samples;
    private String mutGene; private int mutFrom, mutTo;
    private double cP99, kP99, cDrop, kDrop;

    public Evolver(Cell.Genes base, long seed, Factory fx, double targetP99) {
        this.base = base; this.rnd = new SplittableRandom(seed); this.fx = fx; this.targetP99 = targetP99;
    }
    public Cell.Genes current() { return base; }
    public List<Decision> decisions() { return log; }

    public double fitness(double p99, double drops) {
        return 1 / (1 + Math.pow(p99 / targetP99, 3)) - 2 * drops;
    }

    public void tick(long t, List<CellCtl> controls) {
        if (cooldown > 0) cooldown--;
        if (canary == null) {
            if (cooldown == 0 && !controls.isEmpty()) {
                int i = rr++ % GENES.length;
                int cur = base.get(GENES[i]);
                int next = Math.max(MIN[i], Math.min(MAX[i], cur + (rnd.nextBoolean() ? STEP[i] : -STEP[i])));
                if (next != cur) {
                    mutGene = GENES[i]; mutFrom = cur; mutTo = next;
                    canary = fx.spawn(base.with(mutGene, next), true);
                    windowLeft = WINDOW; samples = 0; cP99 = kP99 = cDrop = kDrop = 0;
                }
            }
            return;
        }
        double cp = canary.p99(), cd = canary.dropRate();
        double kp = 0, kd = 0;
        for (var c : controls) { kp += c.p99(); kd += c.dropRate(); }
        if (!controls.isEmpty()) { kp /= controls.size(); kd /= controls.size(); }
        cP99 += cp; kP99 += kp; cDrop += cd; kDrop += kd; samples++;
        if (--windowLeft > 0) return;
        double cm = cP99 / samples, km = kP99 / samples;
        double cdr = cDrop / samples, kdr = kDrop / samples;
        double fc = fitness(cm, cdr), fk = fitness(km, kdr);
        boolean adopt = fc > fk + ACCEPT;
        log.add(new Decision(t, mutGene, mutFrom, mutTo, adopt,
                "canary p99=%.0f drops=%.3f vs control p99=%.0f drops=%.3f".formatted(cm, cdr, km, kdr)));
        if (adopt) base = base.with(mutGene, mutTo);
        fx.applyAll(base);
        fx.kill(canary);
        canary = null;
        cooldown = COOLDOWN;
    }
}
