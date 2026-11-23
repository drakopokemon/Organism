package dev.organism.control;

import dev.organism.evolution.HumanApprovalGate;
import dev.organism.obs.Json;
import dev.organism.security.webauthn.WebAuthn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Столп 6: Four-eyes — OPT-IN. Impact > threshold требует подписи ДВУХ РАЗНЫХ людей (FIDO2),
 * каждая подпись привязана к SHA-256 канонической карточки мутации.
 * disabled (дефолт) -> одиночный approve: legacy-путь и ProductionSelfTest НЕ ломаются.
 * Системные события без подписей людей аудитор видит сразу (Raft даёт порядок, не неотказуемость).
 */
public final class FourEyesGate {
    private final HumanApprovalGate gate;
    private final WebAuthn wa;
    private final double threshold;
    private final boolean enabled;
    private final String rpId;
    private final Set<String> origins;
    private final Map<String, Set<String>> approvals = new ConcurrentHashMap<>();

    public FourEyesGate(HumanApprovalGate gate, WebAuthn wa, double threshold, boolean enabled, String rpId, Set<String> origins) {
        this.gate = gate;
        this.wa = wa;
        this.threshold = threshold;
        this.enabled = enabled;
        this.rpId = rpId;
        this.origins = Set.copyOf(origins);
    }

    public boolean enabled() { return enabled; }

    public String submit(String agentId, Map<String, Double> params, double risk, Consumer<Map<String, Double>> applier) {
        String id = gate.submit(agentId, params, risk, applier);
        if (enabled && risk > threshold) approvals.put(id, ConcurrentHashMap.newKeySet());
        return id;
    }

    /** Каноническая карточка мутации — ровно то, что человек видит в UI и подписывает ключом. */
    public static String card(String proposalId, String agentId, Map<String, Double> params, double risk) {
        StringBuilder sb = new StringBuilder("{\"proposal\":").append(Json.str(proposalId))
                .append(",\"agent\":").append(Json.str(agentId))
                .append(",\"risk\":").append(Json.num(risk)).append(",\"params\":{");
        boolean first = true;
        for (String k : new TreeSet<>(params.keySet())) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.str(k)).append(':').append(Json.num(params.get(k)));
        }
        return sb.append("}}").toString();
    }

    public HumanApprovalGate.Status approve(String id, String credentialId, byte[] clientDataJson, byte[] authData, byte[] sig) {
        var p = gate.get(id);
        if (p == null) return null;
        Set<String> set = approvals.get(id);
        if (set == null) throw new IllegalStateException("four-eyes: proposal not tracked (risk<=threshold or disabled)");
        if (p.status != HumanApprovalGate.Status.PENDING) return p.status;
        byte[] challenge = WebAuthn.challengeFor(card(id, p.agentId, p.params, p.risk));
        wa.verifyAssertion(credentialId, clientDataJson, authData, sig, challenge, origins, rpId, false);
        set.add(wa.userOf(credentialId));
        if (set.size() >= 2) {
            List<String> users = new ArrayList<>(set);
            Collections.sort(users);
            return gate.decide(id, true, "four-eyes:" + String.join("+", users));
        }
        return HumanApprovalGate.Status.PENDING;
    }

    public HumanApprovalGate.Status reject(String id, String who) { return gate.decide(id, false, who); }
    public int approvalsCount(String id) { Set<String> s = approvals.get(id); return s == null ? 0 : s.size(); }
    public boolean requiresFourEyes(String id) { return approvals.containsKey(id); }
}
