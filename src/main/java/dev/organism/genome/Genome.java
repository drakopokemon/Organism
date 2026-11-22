package dev.organism.genome;

import java.nio.file.Path;
import java.util.List;

public sealed interface Genome permits Genome.GenomeImpl {
    List<Objective> objectives();
    List<Constraint> constraints();
    MutationPolicy mutationPolicy();

    static Genome fromFile(Path path) {
        return new GenomeImpl(
            List.of(new LatencyObjective(50.0), new CostObjective(300.0)),
            List.of(new RegionConstraint("eu-west")),
            new MutationPolicy(10.0, 1.0, 100.0, 500, 50, 2000, 64, 64, 1024, 50.0, 100.0, 2000.0)
        );
    }

    sealed interface Objective permits LatencyObjective, CostObjective {
        String type();
    }
    record LatencyObjective(double targetP99Ms) implements Objective {
        @Override public String type() { return "latency"; }
    }
    record CostObjective(double maxUsdPerMonth) implements Objective {
        @Override public String type() { return "cost"; }
    }

    sealed interface Constraint permits RegionConstraint, ResourceConstraint {
        String type();
    }
    record RegionConstraint(String allowedRegion) implements Constraint {
        @Override public String type() { return "region"; }
    }
    record ResourceConstraint(int maxRamMb) implements Constraint {
        @Override public String type() { return "resource"; }
    }

    record MutationPolicy(
        double poolSizeStep, double poolSizeMin, double poolSizeMax,
        double queueLimitStep, double queueLimitMin, double queueLimitMax,
        double cacheMbStep, double cacheMbMin, double cacheMbMax,
        double timeoutMsStep, double timeoutMsMin, double timeoutMsMax
    ) {}

    final class GenomeImpl implements Genome {
        private final List<Objective> objectives;
        private final List<Constraint> constraints;
        private final MutationPolicy mutationPolicy;

        public GenomeImpl(List<Objective> objectives, List<Constraint> constraints, MutationPolicy mutationPolicy) {
            this.objectives = objectives;
            this.constraints = constraints;
            this.mutationPolicy = mutationPolicy;
        }

        @Override public List<Objective> objectives() { return objectives; }
        @Override public List<Constraint> constraints() { return constraints; }
        @Override public MutationPolicy mutationPolicy() { return mutationPolicy; }
    }
}