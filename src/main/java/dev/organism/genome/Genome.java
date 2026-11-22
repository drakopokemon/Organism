package dev.organism.genome;

import java.nio.file.Path;
import java.util.List;

public final class Genome {
    public final List<Objective> objectives;
    public final List<Constraint> constraints;
    public final List<Capability> capabilities;
    public final MutationPolicy mutationPolicy;

    public Genome(List<Objective> objectives, List<Constraint> constraints, List<Capability> capabilities, MutationPolicy mutationPolicy) {
        this.objectives = objectives;
        this.constraints = constraints;
        this.capabilities = capabilities;
        this.mutationPolicy = mutationPolicy;
    }

    public static Genome fromFile(Path path) {
        return new Genome(
            List.of(new LatencyObjective(50.0), new CostObjective(300.0)),
            List.of(new RegionConstraint("eu-west")),
            List.of(new ProcessingCapability(1, 5), new ImmuneCapability(true)),
            new MutationPolicy(10.0, 1.0, 100.0, 500, 50, 2000, 64, 64, 1024, 50.0, 100.0, 2000.0)
        );
    }

    public sealed interface Objective permits LatencyObjective, CostObjective {
        String type();
    }
    public record LatencyObjective(double targetP99Ms) implements Objective {
        @Override public String type() { return "latency"; }
    }
    public record CostObjective(double maxUsdPerMonth) implements Objective {
        @Override public String type() { return "cost"; }
    }

    public sealed interface Constraint permits RegionConstraint, ResourceConstraint {
        String type();
    }
    public record RegionConstraint(String allowedRegion) implements Constraint {
        @Override public String type() { return "region"; }
    }
    public record ResourceConstraint(int maxRamMb) implements Constraint {
        @Override public String type() { return "resource"; }
    }

    public sealed interface Capability permits ProcessingCapability, ImmuneCapability {
        String type();
    }
    public record ProcessingCapability(int minInstances, int maxInstances) implements Capability {
        @Override public String type() { return "processing"; }
    }
    public record ImmuneCapability(boolean enabled) implements Capability {
        @Override public String type() { return "immune"; }
    }

    public record MutationPolicy(
        double poolSizeStep, double poolSizeMin, double poolSizeMax,
        double queueLimitStep, double queueLimitMin, double queueLimitMax,
        double cacheMbStep, double cacheMbMin, double cacheMbMax,
        double timeoutMsStep, double timeoutMsMin, double timeoutMsMax
    ) {}
}