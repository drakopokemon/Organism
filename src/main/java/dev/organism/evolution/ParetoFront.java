package dev.organism.evolution;

import dev.organism.genome.Genome;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Многоцелевой отбор: направления per-objective (latency/cost = MIN, throughput = MAX).
 * front() — недоминируемое множество (Pareto). scalarize() — fitness для updateFitness:
 * mean по целям, MIN -> target/value, MAX -> value/target, clamp [0,2].
 * НЕ максимизируем всё подряд: p99 больше — хуже.
 */
public final class ParetoFront {
    public enum Direction { MIN, MAX }
    public record Point(String id, Map<String, Double> values) {}

    private final Map<String, Direction> dirs;
    private final Map<String, Double> targets;

    public ParetoFront(Map<String, Direction> dirs, Map<String, Double> targets) {
        this.dirs = new LinkedHashMap<>(dirs);
        this.targets = new LinkedHashMap<>(targets);
    }

    public static ParetoFront fromGenome(Genome g) {
        var dirs = new LinkedHashMap<String, Direction>();
        var targets = new LinkedHashMap<String, Double>();
        for (Genome.Objective o : g.objectives) {
            switch (o) {
                case Genome.LatencyObjective l -> { dirs.put("p99", Direction.MIN); targets.put("p99", l.targetP99Ms()); }
                case Genome.CostObjective c -> { dirs.put("usd", Direction.MIN); targets.put("usd", c.maxUsdPerMonth()); }
                default -> { }
            }
        }
        return new ParetoFront(dirs, targets);
    }

    private double worst(String k) {
        return dirs.get(k) == Direction.MAX ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
    }

    public boolean dominates(Point a, Point b) {
        boolean strictly = false;
        for (var k : dirs.keySet()) {
            double va = a.values().getOrDefault(k, worst(k));
            double vb = b.values().getOrDefault(k, worst(k));
            int cmp = Double.compare(va, vb);
            if (dirs.get(k) == Direction.MAX) cmp = -cmp;
            if (cmp > 0) return false;
            if (cmp < 0) strictly = true;
        }
        return strictly;
    }

    public List<Point> front(Collection<Point> pts) {
        var out = new ArrayList<Point>();
        for (Point a : pts) {
            boolean dominated = false;
            for (Point b : pts) {
                if (a == b || a.id().equals(b.id())) continue;
                if (dominates(b, a)) { dominated = true; break; }
            }
            if (!dominated) out.add(a);
        }
        return out;
    }

    public double scalarize(Map<String, Double> values) {
        double sum = 0;
        int n = 0;
        for (var k : dirs.keySet()) {
            double t = targets.getOrDefault(k, 1.0);
            double v = values.getOrDefault(k, worst(k));
            double s = dirs.get(k) == Direction.MIN ? t / Math.max(v, 1e-9) : v / Math.max(t, 1e-9);
            if (!Double.isFinite(s)) s = 0;
            sum += Math.max(0, Math.min(2, s));
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    public Map<String, Direction> directions() { return Map.copyOf(dirs); }
}
