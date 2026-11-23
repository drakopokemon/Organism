package dev.organism.control;

import dev.organism.evolution.HumanApprovalGate;

import java.util.Map;

/**
 * Opt-in клей: proposal ВНУТРИ огибающей -> авто-approve через СУЩЕСТВУЮЩИЙ HumanApprovalGate.
 * Контракты gate/EvolutionController не меняются; без этого класса всё идёт по старому пути.
 */
public final class EnvelopeAutoApprover {
    private final HumanApprovalGate gate;
    private final Envelope envelope;
    private final String region;

    public EnvelopeAutoApprover(HumanApprovalGate gate, Envelope envelope, String region) {
        this.gate = gate;
        this.envelope = envelope;
        this.region = region;
    }

    public boolean tryAuto(String proposalId, Map<String, Double> oldParams) {
        var p = gate.get(proposalId);
        if (p == null || p.status != HumanApprovalGate.Status.PENDING) return false;
        Envelope.Decision d = envelope.evaluate(region, oldParams, p.params, p.risk);
        if (d != Envelope.Decision.AUTO_APPROVE) return false;
        return gate.decide(proposalId, true, "envelope:" + region) == HumanApprovalGate.Status.APPROVED;
    }
}
