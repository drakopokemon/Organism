package dev.organism.evolution;

import dev.organism.obs.ObservabilityExporter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Consumer<MutationDecision>: risk = max относительная дельта параметров.
 * risk <= threshold -> авто-применение (engine уже применил canary) + audit/metrics.
 * risk >  threshold -> ОТКАТ canary через engine.applyParams(old,false) + proposal в gate.
 * Одобрение -> applyApproved -> engine.applyParams(new,true) (canary eval window).
 * Двухфазный bind(engine) разрывает цикл ctor-зависимости с EvolutionEngine.
 * accept() вызывается из engine.tick ПОД монитором engine (реентерабельно);
 * gate.decide вызывает callback ВНЕ своего лока -> без lock-order inversion.
 */
public final class EvolutionController implements Consumer<EvolutionEngine.MutationDecision> {
    private final HumanApprovalGate gate;
    private final BiConsumer<String, String> audit;
    private final ObservabilityExporter metrics;
    private final double riskThreshold;
    private final Map<String, Map<String, Double>> last = new ConcurrentHashMap<>();
    private volatile EvolutionEngine engine;

    public EvolutionController(HumanApprovalGate gate, BiConsumer<String, String> audit,
                               ObservabilityExporter metrics, double riskThreshold) {
        this.gate = gate;
        this.audit = audit;
        this.metrics = metrics;
        this.riskThreshold = riskThreshold;
    }

    public void bind(EvolutionEngine e) {
        this.engine = e;
        for (var a : e.snapshot().values()) last.put(a.id(), Map.copyOf(a.params()));
    }

    public static double risk(Map<String, Double> old, Map<String, Double> neu) {
        double r = 0;
        for (var k : neu.keySet()) {
            double n = neu.get(k);
            double o = old == null ? n : old.getOrDefault(k, n);
            r = Math.max(r, Math.abs(n - o) / Math.max(Math.abs(o), 1e-9));
        }
        return r;
    }

    @Override public void accept(EvolutionEngine.MutationDecision d) {
        EvolutionEngine e = engine;
        if (e == null) throw new IllegalStateException("EvolutionController not bound to engine");
        Map<String, Double> old = last.getOrDefault(d.agentId(), d.newParams());
        double risk = risk(old, d.newParams());
        if (metrics != null) {
            metrics.inc("organism_mutations_total", 1);
            metrics.gauge("organism_mutation_risk", risk, "agent", d.agentId());
        }
        if (risk > riskThreshold) {
            e.applyParams(d.agentId(), old, false);
            String id = gate.submit(d.agentId(), d.newParams(), risk, p -> applyApproved(d.agentId(), p));
            last.put(d.agentId(), Map.copyOf(old));
            if (metrics != null) metrics.inc("organism_mutations_pending_total", 1);
            if (audit != null) audit.accept("MUTATION_PENDING",
                    "agent=" + d.agentId() + " risk=" + risk + " proposal=" + id);
        } else {
            last.put(d.agentId(), Map.copyOf(d.newParams()));
            if (audit != null) audit.accept("MUTATION_AUTO", "agent=" + d.agentId() + " risk=" + risk);
        }
    }

    void applyApproved(String agentId, Map<String, Double> p) {
        EvolutionEngine e = engine;
        if (e == null) return;
        e.applyParams(agentId, p, true);
        last.put(agentId, Map.copyOf(p));
        if (metrics != null) metrics.inc("organism_mutations_approved_total", 1);
        if (audit != null) audit.accept("MUTATION_APPROVED_APPLIED", "agent=" + agentId);
    }
}
