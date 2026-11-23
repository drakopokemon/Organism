package dev.organism.genome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Декларативный геном. Sealed = compile-time полнота обработки (не runtime-защита).
 * v4.1: РЕАЛЬНЫЙ парсинг genome.yaml (GenomeParser), fail-closed на невалидном конфиге.
 * Существующие record-контракты НЕ изменены; новые подтипы добавлены аддитивно
 * (все switch-потребители имеют default-ветку).
 */
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

    /** Файл есть -> строгий парсинг (бросает на невалидном). Файла нет -> типизированный дефолт (совместимость с v3). */
    public static Genome fromFile(Path path) {
        if (path != null && Files.exists(path)) {
            try {
                return GenomeParser.parse(path);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("genome parse failed: " + path + ": " + e.getMessage(), e);
            }
        }
        return defaultGenome();
    }

    public static Genome defaultGenome() {
        return new Genome(
            List.of(new LatencyObjective(50.0), new CostObjective(300.0)),
            List.of(new RegionConstraint("eu-west")),
            List.of(new ProcessingCapability(1, 5), new ImmuneCapability(true)),
            new MutationPolicy(10.0, 1.0, 100.0, 500, 50, 2000, 64, 64, 1024, 50.0, 100.0, 2000.0)
        );
    }

    public sealed interface Objective permits LatencyObjective, CostObjective, ThroughputObjective {
        String type();
    }
    public record LatencyObjective(double targetP99Ms) implements Objective {
        @Override public String type() { return "latency"; }
    }
    public record CostObjective(double maxUsdPerMonth) implements Objective {
        @Override public String type() { return "cost"; }
    }
    public record ThroughputObjective(double targetRps) implements Objective {
        @Override public String type() { return "throughput"; }
    }

    public sealed interface Constraint permits RegionConstraint, ResourceConstraint, ForbiddenApisConstraint {
        String type();
    }
    public record RegionConstraint(String allowedRegion) implements Constraint {
        @Override public String type() { return "region"; }
    }
    public record ResourceConstraint(int maxRamMb) implements Constraint {
        @Override public String type() { return "resource"; }
    }
    public record ForbiddenApisConstraint(String forbiddenApis) implements Constraint {
        @Override public String type() { return "forbidden_apis"; }
    }

    public sealed interface Capability permits ProcessingCapability, ImmuneCapability, StorageCapability, BalancingCapability {
        String type();
    }
    public record ProcessingCapability(int minInstances, int maxInstances) implements Capability {
        @Override public String type() { return "processing"; }
    }
    public record ImmuneCapability(boolean enabled) implements Capability {
        @Override public String type() { return "immune"; }
    }
    public record StorageCapability(int shards, int replication) implements Capability {
        @Override public String type() { return "storage"; }
    }
    public record BalancingCapability(String algorithm) implements Capability {
        @Override public String type() { return "balancing"; }
    }

    public record MutationPolicy(
        double poolSizeStep, double poolSizeMin, double poolSizeMax,
        double queueLimitStep, double queueLimitMin, double queueLimitMax,
        double cacheMbStep, double cacheMbMin, double cacheMbMax,
        double timeoutMsStep, double timeoutMsMin, double timeoutMsMax
    ) {}
}
