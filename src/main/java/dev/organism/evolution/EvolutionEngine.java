package dev.organism.evolution;
import dev.organism.genome.Genome;
import dev.organism.net.Membership;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class EvolutionEngine {
    private final Genome genome;
    private final Membership membership;
    private final EvolutionJournal journal;
    private final MutationGuard guard;
    private final SplittableRandom rnd;
    private final Map<String, AgentState> agents = new ConcurrentHashMap<>();
    private final Consumer<MutationDecision> applier;
    private long tick;
    private int evalWindow = 50;
    private double mutationRate = 0.1;

    public record AgentState(String id, Map<String, Double> params, double fitness, long lastMutation, boolean canary) {}
    public record MutationDecision(String agentId, Map<String, Double> newParams, double expectedFitness) {}

    public EvolutionEngine(Genome g, Membership m, EvolutionJournal j, MutationGuard gd, long seed, Consumer<MutationDecision> a) {
        genome=g; membership=m; journal=j; guard=gd; rnd=new SplittableRandom(seed); applier=a;
    }

    public synchronized void registerAgent(String id, Map<String, Double> p) {
        agents.put(id, new AgentState(id, new HashMap<>(p), 0.0, 0, false));
    }

    public synchronized void updateFitness(String id, double f) {
        var a = agents.get(id);
        if (a != null) agents.put(id, new AgentState(id, a.params, f, a.lastMutation, a.canary));
    }

    public synchronized void tick() {
        tick++;
        if (tick % 10 != 0) return;
        var sorted = new ArrayList<>(agents.values());
        sorted.sort((a, b) -> Double.compare(b.fitness, a.fitness));
        if (sorted.size() < 2) return;
        var champ = sorted.get(0);
        var under = sorted.get(sorted.size() - 1);

        if (rnd.nextDouble() < mutationRate) {
            var np = mutate(under.params);
            if (!guard.validate(np, genome)) {
                journal.log(tick, under.id, "REJECTED_BY_GUARD", under.params, np, under.fitness, 0.0);
                mutationRate = Math.max(0.01, mutationRate * 0.9);
                return;
            }
            agents.put(under.id, new AgentState(under.id, np, under.fitness, tick, true));
            applier.accept(new MutationDecision(under.id, np, champ.fitness));
            journal.log(tick, under.id, "MUTATION_APPLIED", under.params, np, under.fitness, champ.fitness);
        }

        for (var e : new HashMap<>(agents).entrySet()) {
            var a = e.getValue();
            if (a.canary && tick - a.lastMutation > evalWindow) {
                if (a.fitness < 0.0) {
                    var bl = new HashMap<>(a.params);
                    agents.put(a.id, new AgentState(a.id, bl, a.fitness, tick, false));
                    applier.accept(new MutationDecision(a.id, bl, a.fitness));
                    journal.log(tick, a.id, "MUTATION_ROLLBACK", a.params, bl, a.fitness, a.fitness);
                } else {
                    agents.put(a.id, new AgentState(a.id, a.params, a.fitness, a.lastMutation, false));
                    journal.log(tick, a.id, "MUTATION_FIXED", a.params, a.params, a.fitness, a.fitness);
                    mutationRate = Math.min(0.3, mutationRate * 1.1);
                }
            }
        }
    }

    private Map<String, Double> mutate(Map<String, Double> base) {
        var r = new HashMap<>(base);
        var p = genome.mutationPolicy;
        for (var k : r.keySet()) {
            if (rnd.nextDouble() < 0.5) {
                double c = r.get(k);
                double s = step(k, p), mn = min(k, p), mx = max(k, p);
                r.put(k, Math.max(mn, Math.min(mx, c + (rnd.nextBoolean() ? s : -s))));
            }
        }
        return r;
    }

    private double step(String k, Genome.MutationPolicy p) {
        return switch (k) {
            case "poolSize" -> p.poolSizeStep();
            case "queueLimit" -> p.queueLimitStep();
            case "cacheMb" -> p.cacheMbStep();
            case "timeoutMs" -> p.timeoutMsStep();
            default -> 1.0;
        };
    }

    private double min(String k, Genome.MutationPolicy p) {
        return switch (k) {
            case "poolSize" -> p.poolSizeMin();
            case "queueLimit" -> p.queueLimitMin();
            case "cacheMb" -> p.cacheMbMin();
            case "timeoutMs" -> p.timeoutMsMin();
            default -> 0;
        };
    }

    private double max(String k, Genome.MutationPolicy p) {
        return switch (k) {
            case "poolSize" -> p.poolSizeMax();
            case "queueLimit" -> p.queueLimitMax();
            case "cacheMb" -> p.cacheMbMax();
            case "timeoutMs" -> p.timeoutMsMax();
            default -> 1000;
        };
    }

    /** v4 additive: params snapshot for risk calculation / API. */
    public synchronized Map<String, Double> paramsOf(String id) {
        var a = agents.get(id);
        return a == null ? null : Map.copyOf(a.params);
    }

    /** v4 additive: apply params (human-approved mutation or gate rollback). */
    public synchronized void applyParams(String id, Map<String, Double> p, boolean canary) {
        var a = agents.get(id);
        if (a == null) return;
        agents.put(id, new AgentState(id, new HashMap<>(p), a.fitness, tick, canary));
    }

    public Map<String, AgentState> snapshot() {
        return Collections.unmodifiableMap(agents);
    }
}