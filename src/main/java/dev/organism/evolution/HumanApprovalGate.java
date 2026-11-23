package dev.organism.evolution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Human-in-the-loop: мутации с risk > threshold ждут одобрения.
 * PENDING -> APPROVED / REJECTED / EXPIRED (TTL sweep). decide() РЕАЛЬНО применяет
 * мутацию через per-proposal callback ВНЕ лока proposal (без deadlock с engine monitor).
 * Инжектируемые часы для детерминированных тестов.
 */
public final class HumanApprovalGate {
    public enum Status { PENDING, APPROVED, REJECTED, EXPIRED }

    public static final class Proposal {
        public final String id, agentId;
        public final Map<String, Double> params;
        public final double risk;
        public final long createdMs, ttlMs;
        public volatile Status status = Status.PENDING;
        public volatile String decidedBy = "";
        final Consumer<Map<String, Double>> applier;

        Proposal(String id, String agentId, Map<String, Double> params, double risk,
                 long createdMs, long ttlMs, Consumer<Map<String, Double>> applier) {
            this.id = id; this.agentId = agentId; this.params = Map.copyOf(params);
            this.risk = risk; this.createdMs = createdMs; this.ttlMs = ttlMs; this.applier = applier;
        }
    }

    private final Map<String, Proposal> proposals = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final LongSupplier clock;
    private final AtomicLong seq = new AtomicLong();

    public HumanApprovalGate(long ttlMs) { this(ttlMs, System::currentTimeMillis); }

    public HumanApprovalGate(long ttlMs, LongSupplier clock) {
        this.ttlMs = Math.max(1, ttlMs);
        this.clock = clock;
    }

    public String submit(String agentId, Map<String, Double> params, double risk,
                         Consumer<Map<String, Double>> applier) {
        String id = "m" + seq.incrementAndGet();
        proposals.put(id, new Proposal(id, agentId, params, risk, clock.getAsLong(), ttlMs, applier));
        return id;
    }

    public Status decide(String id, boolean approve, String who) {
        Proposal p = proposals.get(id);
        if (p == null) return null;
        Consumer<Map<String, Double>> apply = null;
        synchronized (p) {
            if (p.status != Status.PENDING) return p.status;
            if (clock.getAsLong() - p.createdMs > p.ttlMs) {
                p.status = Status.EXPIRED;
                return Status.EXPIRED;
            }
            p.status = approve ? Status.APPROVED : Status.REJECTED;
            p.decidedBy = who == null ? "unknown" : who;
            if (approve) apply = p.applier;
        }
        if (apply != null) apply.accept(p.params);
        return p.status;
    }

    public int sweep() {
        int n = 0;
        long now = clock.getAsLong();
        for (Proposal p : proposals.values()) {
            synchronized (p) {
                if (p.status == Status.PENDING && now - p.createdMs > p.ttlMs) {
                    p.status = Status.EXPIRED;
                    n++;
                }
            }
        }
        return n;
    }

    public Proposal get(String id) { return proposals.get(id); }
    public Collection<Proposal> all() { return new ArrayList<>(proposals.values()); }
    public long pendingCount() { return proposals.values().stream().filter(p -> p.status == Status.PENDING).count(); }
    public long ttlMs() { return ttlMs; }
}
