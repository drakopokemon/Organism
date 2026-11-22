package dev.organism;

import java.util.*;
import java.util.concurrent.*;

/** Human-in-the-loop: HIGH-risk мутации проходят через шлюз. */
interface Gate {
    record Proposal(String id, String cell, long tick, Map<String, double[]> changes) {}
    enum Verdict { PENDING, APPROVED, DENIED }

    Verdict review(Proposal p, TickContext ctx);

    /** Авто-политика: одобряем, только если кластер здоров и шаг не радикален. */
    final class Policy implements Gate {
        public Verdict review(Proposal p, TickContext ctx) {
            boolean healthy = ctx.clusterP99() < 1.3 * ctx.genome().p99Target() && ctx.clusterAvail() > 0.98;
            boolean small = p.changes().entrySet().stream().allMatch(e -> {
                var g = ctx.genome().gene(e.getKey());
                return Math.abs(e.getValue()[1] - e.getValue()[0]) <= 0.25 * (g.max() - g.min());
            });
            return healthy && small ? Verdict.APPROVED : Verdict.DENIED;
        }
    }

    /** Ручной режим: человек жмёт Approve/Deny в дашборде. */
    final class Manual implements Gate {
        private final ConcurrentMap<String, Verdict> verdicts = new ConcurrentHashMap<>();
        private final ConcurrentMap<String, Proposal> proposals = new ConcurrentHashMap<>();
        static final int MAX_PENDING = 6;   // backpressure: человека не заваливаем — лишние предложения отклоняются
        public synchronized Verdict review(Proposal p, TickContext ctx) {
            var v = verdicts.get(p.id());
            if (v != null) return v;
            if (pending().size() >= MAX_PENDING) { verdicts.put(p.id(), Verdict.DENIED); return Verdict.DENIED; }
            proposals.put(p.id(), p); verdicts.put(p.id(), Verdict.PENDING);
            return Verdict.PENDING;
        }
        synchronized void decideAll(boolean ok) { for (var p : pending()) decide(p.id(), ok); }
        void decide(String id, boolean ok) { verdicts.computeIfPresent(id, (k, v) -> ok ? Verdict.APPROVED : Verdict.DENIED); }
        List<Proposal> pending() {
            return proposals.values().stream().filter(p -> verdicts.get(p.id()) == Verdict.PENDING)
                    .sorted(Comparator.comparing(Proposal::id)).toList();
        }
    }
}
