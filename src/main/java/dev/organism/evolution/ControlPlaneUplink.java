package dev.organism.evolution;

import dev.organism.control.ControlPlaneClient;
import dev.organism.obs.ObservabilityExporter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 6.1 Uplink: мост локального HumanApprovalGate узла <-> opt-in Control Plane.
 *
 * Семантика (честно):
 * - PENDING-мутации узла ЗЕРКАЛИРУЮТСЯ в control plane (/mutations/submit): Raft-аудит, four-eyes/WebAuthn, Envelope.
 * - Решение control plane (APPROVED/REJECTED/EXPIRED) применяется к ЛОКАЛЬНОМУ proposal через gate.decide,
 *   который вызывает существующий per-proposal applier (EvolutionController.applyApproved) -> canary eval window.
 * - Кто решил первым — тот и прав: локальный API /decide остаётся рабочим (fail-open к локальному HITL при down CP).
 *   Осиротевший remote proposal истекает по TTL control plane. Это осознанный trade-off доступности edge-узла.
 * - poll() вызывается из главного цикла кернела (НЕ под монитором engine) -> без lock-order inversion.
 * - poll() не бросает наверх: down CP = метрика/аудит, мутации продолжают ждать локального одобрения.
 */
public final class ControlPlaneUplink implements AutoCloseable {
    private final ControlPlaneClient client;
    private final HumanApprovalGate gate;
    private final BiConsumer<String, String> audit;
    private final ObservabilityExporter metrics;
    private final Map<String, String> mirrored = new ConcurrentHashMap<>();
    private Boolean up;

    public ControlPlaneUplink(ControlPlaneClient client, HumanApprovalGate gate,
                              BiConsumer<String, String> audit, ObservabilityExporter metrics) {
        this.client = client;
        this.gate = gate;
        this.audit = audit;
        this.metrics = metrics;
    }

    public void poll() {
        try {
            boolean success = false;
            boolean failure = false;
            for (HumanApprovalGate.Proposal p : gate.all()) {
                if (p.status != HumanApprovalGate.Status.PENDING) {
                    mirrored.remove(p.id());
                    continue;
                }
                String rid = mirrored.get(p.id());
                if (rid == null) {
                    ControlPlaneClient.RemoteProposal rp = client.submitMutation(p.agentId, p.params, p.risk);
                    if (rp == null) {
                        failure = true;
                        inc("organism_control_uplink_errors_total");
                        continue;
                    }
                    success = true;
                    mirrored.put(p.id(), rp.id());
                    inc("organism_control_mirrored_total");
                    audit("CONTROL_PROPOSAL_MIRRORED", "local=" + p.id() + " remote=" + rp.id()
                            + " agent=" + p.agentId + " risk=" + p.risk + " fourEyes=" + rp.fourEyes());
                } else {
                    ControlPlaneClient.RemoteState st = client.status(rid);
                    if (st == null) {
                        failure = true;
                        inc("organism_control_uplink_errors_total");
                        continue;
                    }
                    success = true;
                    switch (st.status()) {
                        case "APPROVED" -> {
                            HumanApprovalGate.Status r = gate.decide(p.id(), true, "control-plane:" + st.decidedBy());
                            if (r == HumanApprovalGate.Status.APPROVED) {
                                inc("organism_control_remote_approved_total");
                                audit("CONTROL_REMOTE_APPROVED", "local=" + p.id() + " remote=" + rid + " by=" + st.decidedBy());
                            } else {
                                inc("organism_control_remote_stale_total");
                                audit("CONTROL_REMOTE_STALE", "local=" + p.id() + " remote=" + rid + " localStatus=" + r);
                            }
                        }
                        case "REJECTED", "EXPIRED" -> {
                            HumanApprovalGate.Status r = gate.decide(p.id(), false, "control-plane:" + st.decidedBy());
                            inc("organism_control_remote_rejected_total");
                            audit("CONTROL_REMOTE_REJECTED", "local=" + p.id() + " remote=" + rid
                                    + " remoteStatus=" + st.status() + " localStatus=" + r);
                        }
                        default -> { /* PENDING: ждём людей */ }
                    }
                }
            }
            boolean nowUp = success || (!failure && client.health());
            if (metrics != null) {
                metrics.gauge("organism_control_uplink_up", nowUp ? 1 : 0);
                metrics.gauge("organism_control_mirrored_pending", mirrored.size());
            }
            if (up == null || up != nowUp) {
                audit(nowUp ? "CONTROL_UPLINK_UP" : "CONTROL_UPLINK_DOWN", "mirrored=" + mirrored.size());
                if (!nowUp) inc("organism_control_uplink_down_total");
            }
            up = nowUp;
        } catch (RuntimeException e) {
            inc("organism_control_uplink_errors_total");
            audit("CONTROL_UPLINK_ERROR", String.valueOf(e.getMessage()));
        }
    }

    public int mirroredCount() { return mirrored.size(); }

    private void inc(String name) { if (metrics != null) metrics.inc(name, 1); }
    private void audit(String ev, String d) { if (audit != null) audit.accept(ev, d); }

    @Override public void close() { client.close(); }
}
