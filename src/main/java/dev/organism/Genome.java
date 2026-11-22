package dev.organism;

import java.util.*;

/**
 * Геном — декларативные «правила игры»: что оптимизировать, что нельзя нарушать,
 * что умеем и в каких пределах разрешено мутировать. Иерархии sealed => компилятор
 * гарантирует, что движок эволюции обработал ВСЕ виды целей и ограничений.
 */
public record Genome(String name, List<Objective> objectives, List<Constraint> constraints,
                     Set<Capability> capabilities, List<Gene> genes) {

    public enum Capability { PROCESS, BALANCE, IMMUNE, SPORULATE, EVOLVE, STORE }
    public enum Risk { LOW, HIGH }   // HIGH-мутации проходят через Gate (human-in-the-loop)

    public sealed interface Objective {
        double weight();
        record P99Latency(double targetMs, double weight) implements Objective {}
        record CostPerMReq(double targetUsd, double weight) implements Objective {}
        record Availability(double target, double weight) implements Objective {}
    }

    public sealed interface Constraint {
        record MaxCostPerHour(double usd) implements Constraint {}
        record MaxMemPerCellMb(int mb) implements Constraint {}
        record ForbiddenZone(String zone) implements Constraint {}
        record Replicas(int min, int max) implements Constraint {}
        /** Слой данных: число шардов и фактор репликации каждого. */
        record Storage(int shards, int replication) implements Constraint {}
    }

    public record Gene(String name, double min, double max, double step, double initial, Risk risk) {
        public Gene {
            if (!(min < max) || step <= 0 || initial < min || initial > max)
                throw new IllegalArgumentException("bad gene " + name);
        }
        public double snap(double v) {
            double c = Math.max(min, Math.min(max, v));
            return min + Math.round((c - min) / step) * step;
        }
    }

    /** Наблюдаемое состояние (одной клетки или всего организма). */
    public record Telemetry(double p99Ms, double availability, double usdPerMReq) {}

    public Genome {
        if (objectives.isEmpty() || genes.isEmpty()) throw new IllegalArgumentException("empty genome");
        objectives = List.copyOf(objectives);
        constraints = List.copyOf(constraints);
        capabilities = Set.copyOf(capabilities);
        genes = List.copyOf(genes);
    }

    public Gene gene(String n) {
        return genes.stream().filter(g -> g.name().equals(n)).findFirst().orElseThrow();
    }

    public Phenotype initial() {
        var m = new TreeMap<String, Double>();
        genes.forEach(g -> m.put(g.name(), g.initial()));
        return new Phenotype(m);
    }

    /** Полезность в [0,1]; exhaustive switch по sealed-иерархии. */
    public double fitness(Telemetry t) {
        double sum = 0, w = 0;
        for (Objective o : objectives) {
            double u = switch (o) {
                case Objective.P99Latency l -> 1 / (1 + Math.pow(t.p99Ms() / l.targetMs(), 3));
                case Objective.CostPerMReq c -> 1 / (1 + t.usdPerMReq() / c.targetUsd());
                case Objective.Availability a -> t.availability() >= a.target() ? 1
                        : Math.max(0, 1 - (a.target() - t.availability()) * 20);
            };
            sum += u * o.weight();
            w += o.weight();
        }
        return sum / w;
    }

    /** «Иммунный барьер»: мутация проверяется ДО применения. */
    public Optional<String> violation(Phenotype p) {
        for (Constraint c : constraints) {
            String v = switch (c) {
                case Constraint.MaxMemPerCellMb m ->
                        World.memMb(p) > m.mb() ? "mem %.0f > %d MB".formatted(World.memMb(p), m.mb()) : null;
                case Constraint.MaxCostPerHour x -> null;   // кластерный — проверяется при почковании
                case Constraint.ForbiddenZone x -> null;    // топологический
                case Constraint.Replicas x -> null;         // популяционный
                case Constraint.Storage x -> null;          // топологический (проверяется при репликации)
            };
            if (v != null) return Optional.of(v);
        }
        return Optional.empty();
    }

    public Constraint.Storage storage() {
        if (!capabilities.contains(Capability.STORE)) return null;
        for (var c : constraints) if (c instanceof Constraint.Storage s) return s;
        return null;
    }

    public boolean zoneAllowed(String zone) {
        for (var c : constraints) if (c instanceof Constraint.ForbiddenZone f && f.zone().equals(zone)) return false;
        return true;
    }
    public double maxCost() {
        for (var c : constraints) if (c instanceof Constraint.MaxCostPerHour m) return m.usd();
        return Double.MAX_VALUE;
    }
    public int minReplicas() {
        for (var c : constraints) if (c instanceof Constraint.Replicas r) return r.min();
        return 1;
    }
    public int maxReplicas() {
        for (var c : constraints) if (c instanceof Constraint.Replicas r) return r.max();
        return 1000;
    }
    public double p99Target() {
        for (var o : objectives) if (o instanceof Objective.P99Latency l) return l.targetMs();
        return 100;
    }
}
