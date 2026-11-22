package dev.organism.genome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Геном: декларативное описание цели, ограничений и способностей.
 * Гарантирует compile-time безопасность через sealed interfaces.
 */
public final class Genome {
    public sealed interface Objective permits LatencyObjective, CostObjective, ThroughputObjective {}
    public record LatencyObjective(double targetP99Ms) implements Objective {}
    public record CostObjective(double maxUsdPerMonth) implements Objective {}
    public record ThroughputObjective(double minRps) implements Objective {}

    public sealed interface Constraint permits RegionConstraint, MemoryConstraint, ApiConstraint {}
    public record RegionConstraint(Set<String> allowedRegions) implements Constraint {}
    public record MemoryConstraint(int maxMbPerAgent) implements Constraint {}
    public record ApiConstraint(Set<String> forbiddenApis) implements Constraint {}

    public sealed interface Capability permits ProcessingCapability, StorageCapability, BalancingCapability, ImmuneCapability {}
    public record ProcessingCapability(int minInstances, int maxInstances) implements Capability {}
    public record StorageCapability(int shards, int replicationFactor) implements Capability {}
    public record BalancingCapability(String algorithm) implements Capability {}
    public record ImmuneCapability(boolean enabled) implements Capability {}

    public record MutationPolicy(
        int poolSizeMin, int poolSizeMax, int poolSizeStep,
        int queueLimitMin, int queueLimitMax, int queueLimitStep,
        int cacheMbMin, int cacheMbMax, int cacheMbStep,
        int timeoutMsMin, int timeoutMsMax, int timeoutMsStep
    ) {}

    public final List<Objective> objectives;
    public final List<Constraint> constraints;
    public final List<Capability> capabilities;
    public final MutationPolicy mutationPolicy;

    public Genome(List<Objective> objectives, List<Constraint> constraints,
                  List<Capability> capabilities, MutationPolicy mutationPolicy) {
        this.objectives = List.copyOf(objectives);
        this.constraints = List.copyOf(constraints);
        this.capabilities = List.copyOf(capabilities);
        this.mutationPolicy = mutationPolicy;
    }

    public static Genome fromFile(Path path) throws IOException {
        var lines = Files.readAllLines(path);
        var objectives = new ArrayList<Objective>();
        var constraints = new ArrayList<Constraint>();
        var capabilities = new ArrayList<Capability>();
        int poolMin = 2, poolMax = 16, poolStep = 2;
        int queueMin = 64, queueMax = 2048, queueStep = 64;
        int cacheMin = 32, cacheMax = 512, cacheStep = 32;
        int timeoutMin = 100, timeoutMax = 2000, timeoutStep = 100;

        String section = null;
        for (var line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            
            if (line.startsWith("objectives:")) section = "objectives";
            else if (line.startsWith("constraints:")) section = "constraints";
            else if (line.startsWith("capabilities:")) section = "capabilities";
            else if (line.startsWith("mutation_policy:")) section = "mutation_policy";
            else if (section != null && line.startsWith("-")) {
                var parts = line.substring(1).trim().split(":\\s*", 2);
                if (parts.length != 2) continue;
                var key = parts[0];
                var value = parts[1];
                
                switch (section) {
                    case "objectives" -> {
                        if (key.equals("latency_p99_ms")) objectives.add(new LatencyObjective(Double.parseDouble(value)));
                        else if (key.equals("cost_usd_per_month")) objectives.add(new CostObjective(Double.parseDouble(value)));
                        else if (key.equals("throughput_rps")) objectives.add(new ThroughputObjective(Double.parseDouble(value)));
                    }
                    case "constraints" -> {
                        if (key.equals("allowed_regions")) constraints.add(new RegionConstraint(Set.of(value.split(","))));
                        else if (key.equals("max_memory_mb_per_agent")) constraints.add(new MemoryConstraint(Integer.parseInt(value)));
                        else if (key.equals("forbidden_apis")) constraints.add(new ApiConstraint(Set.of(value.split(","))));
                    }
                    case "capabilities" -> {
                        if (key.equals("processing")) {
                            var range = value.split(",");
                            capabilities.add(new ProcessingCapability(Integer.parseInt(range[0]), Integer.parseInt(range[1])));
                        } else if (key.equals("storage")) {
                            var parts2 = value.split(",");
                            capabilities.add(new StorageCapability(Integer.parseInt(parts2[0]), Integer.parseInt(parts2[1])));
                        } else if (key.equals("balancing")) capabilities.add(new BalancingCapability(value));
                        else if (key.equals("immune")) capabilities.add(new ImmuneCapability(Boolean.parseBoolean(value)));
                    }
                }
            } else if (section != null && line.contains(":")) {
                var parts = line.split(":\\s*", 2);
                if (parts.length != 2) continue;
                var key = parts[0];
                var value = parts[1];
                
                if (section.equals("mutation_policy")) {
                    if (key.equals("pool_size")) { var r = value.split(","); poolMin = Integer.parseInt(r[0]); poolMax = Integer.parseInt(r[1]); poolStep = Integer.parseInt(r[2]); }
                    else if (key.equals("queue_limit")) { var r = value.split(","); queueMin = Integer.parseInt(r[0]); queueMax = Integer.parseInt(r[1]); queueStep = Integer.parseInt(r[2]); }
                    else if (key.equals("cache_mb")) { var r = value.split(","); cacheMin = Integer.parseInt(r[0]); cacheMax = Integer.parseInt(r[1]); cacheStep = Integer.parseInt(r[2]); }
                    else if (key.equals("timeout_ms")) { var r = value.split(","); timeoutMin = Integer.parseInt(r[0]); timeoutMax = Integer.parseInt(r[1]); timeoutStep = Integer.parseInt(r[2]); }
                }
            }
        }
        return new Genome(objectives, constraints, capabilities,
            new MutationPolicy(poolMin, poolMax, poolStep, queueMin, queueMax, queueStep,
                cacheMin, cacheMax, cacheStep, timeoutMin, timeoutMax, timeoutStep));
    }

    public double calculateFitness(double p99Ms, double costUsdPerHour, double rps) {
        double fitness = 0.0;
        for (var obj : objectives) {
            if (obj instanceof LatencyObjective l) {
                fitness += Math.max(0, (l.targetP99Ms() - p99Ms) / l.targetP99Ms());
            } else if (obj instanceof CostObjective c) {
                double monthly = costUsdPerHour * 24 * 30;
                fitness += Math.max(0, (c.maxUsdPerMonth() - monthly) / c.maxUsdPerMonth());
            } else if (obj instanceof ThroughputObjective t) {
                fitness += Math.max(0, (rps - t.minRps()) / t.minRps());
            }
        }
        return objectives.isEmpty() ? 1.0 : fitness / objectives.size();
    }

    public boolean validateConstraints(int memoryMb, String region, Set<String> usedApis) {
        for (var c : constraints) {
            if (c instanceof MemoryConstraint m && memoryMb > m.maxMbPerAgent()) return false;
            if (c instanceof RegionConstraint r && !r.allowedRegions().contains(region)) return false;
            if (c instanceof ApiConstraint a && !Collections.disjoint(a.forbiddenApis(), usedApis)) return false;
        }
        return true;
    }
}
