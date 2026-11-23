package dev.organism.rollout;

import dev.organism.control.FourEyesGate;
import dev.organism.control.Sprt;
import dev.organism.data.EpochFence;
import dev.organism.evolution.HumanApprovalGate;
import dev.organism.genome.Genome;
import dev.organism.obs.Json;
import dev.organism.obs.ObservabilityExporter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 6.2 Canary-first rollout: иммутабельные поколения вместо мутации "на лету".
 *
 * IDLE -> CANARY(5% new / 5% control) -> SPRT(Wald, log-latency)
 *      -> impact > threshold ? AWAIT_APPROVAL (four-eyes при impact>0.5) : auto-in-envelope
 *      -> ROLLOUT 25% -> 50% -> 100% (health-guard на каждой стадии)
 *      -> DRAINING (epoch N+1 через EpochFence, graceful drain старого поколения) -> DONE.
 * Любая деградация/отказ/таймаут -> ROLLED_BACK/REJECTED: 100% трафика назад на старое поколение.
 * Всё opt-in: без вызова start() поведение узла не меняется. Часы инжектируемые -> детерминированные тесты.
 */
public final class RolloutManager {
    public enum Phase { IDLE, CANARY, AWAIT_APPROVAL, ROLLOUT, DRAINING, DONE, REJECTED, ROLLED_BACK }

    /** Флот агентов. Реализации: CellFleet (реальные клетки), симуляционный флот в selftest. */
    public interface Fleet {
        int size();
        void spawnCanary(Map<String, Double> params);
        void weights(double newGenPct, double controlPct);
        double canaryP99();
        double controlP99();
        double canaryDropRate();
        void promote(Map<String, Double> params, double sharePct);
        void drainOld(long graceMs);
        void killCanary();
        void rollback();
    }

    public static final class Config {
        public final double canaryPct, controlPct, approvalThreshold, targetP99Ms, maxDropRate;
        public final int stageDwellTicks;
        public final long drainGraceMs, maxCanaryTicks;
        private final double[] stages;

        public Config(double canaryPct, double controlPct, double[] stages, int stageDwellTicks,
                      long drainGraceMs, double approvalThreshold, double targetP99Ms,
                      long maxCanaryTicks, double maxDropRate) {
            if (stages == null || stages.length == 0) throw new IllegalArgumentException("rollout: empty stages");
            for (int i = 0; i < stages.length; i++) {
                if (stages[i] <= 0 || stages[i] > 100) throw new IllegalArgumentException("rollout: bad stage " + stages[i]);
                if (i > 0 && stages[i] <= stages[i - 1]) throw new IllegalArgumentException("rollout: stages must increase");
            }
            if (stages[stages.length - 1] != 100.0) throw new IllegalArgumentException("rollout: last stage must be 100");
            if (stageDwellTicks < 1 || drainGraceMs < 0 || maxCanaryTicks < 1)
                throw new IllegalArgumentException("rollout: bad dwell/grace/maxTicks");
            if (maxDropRate <= 0 || maxDropRate > 1) throw new IllegalArgumentException("rollout: bad maxDropRate");
            new TrafficSplit(canaryPct, controlPct);
            this.canaryPct = canaryPct; this.controlPct = controlPct;
            this.stages = stages.clone();
            this.stageDwellTicks = stageDwellTicks;
            this.drainGraceMs = drainGraceMs;
            this.approvalThreshold = approvalThreshold;
            this.targetP99Ms = targetP99Ms;
            this.maxCanaryTicks = maxCanaryTicks;
            this.maxDropRate = maxDropRate;
        }

        public static Config defaults() {
            return new Config(5, 5, new double[]{25, 50, 100}, 3, 5000, 0.10, 120, 600, 0.05);
        }

        public double stage(int i) { return stages[i]; }
        public int stageCount() { return stages.length; }
    }

    private final Fleet fleet;
    private final Sprt sprt;
    private final HumanApprovalGate gate;
    private final FourEyesGate fourEyes;
    private final EpochFence fence;
    private final Genome.MutationPolicy policy;
    private final Impact.Criticality crit;
    private final Config cfg;
    private final BiConsumer<String, String> audit;
    private final ObservabilityExporter metrics;
    private final LongSupplier clock;
    private static final AtomicLong SEQ = new AtomicLong();

    private Phase phase = Phase.IDLE;
    private String rolloutId = "", agentId = "", proposalId = "", resource = "", lastReason = "";
    private Map<String, Double> newParams = Map.of();
    private Impact impact = Impact.of(0, 0, 0);
    private int stageIdx = -1, dwell, canaryTicks;
    private long epoch, startedMs, drainMs;

    public RolloutManager(Fleet fleet, Sprt sprt, HumanApprovalGate gate, FourEyesGate fourEyes,
                          EpochFence fence, Genome.MutationPolicy policy, Impact.Criticality crit,
                          Config cfg, BiConsumer<String, String> audit, ObservabilityExporter metrics,
                          LongSupplier clock) {
        if (fleet == null || sprt == null || gate == null || fence == null)
            throw new IllegalArgumentException("rollout: fleet/sprt/gate/fence required");
        this.fleet = fleet; this.sprt = sprt; this.gate = gate; this.fourEyes = fourEyes; this.fence = fence;
        this.policy = policy;
        this.crit = crit == null ? Impact.Criticality.defaults() : crit;
        this.cfg = cfg == null ? Config.defaults() : cfg;
        this.audit = audit; this.metrics = metrics;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    public synchronized String start(String agent, Map<String, Double> oldP, Map<String, Double> newP,
                                     int affectedNodes, int totalNodes) {
        if (phase == Phase.CANARY || phase == Phase.AWAIT_APPROVAL || phase == Phase.ROLLOUT || phase == Phase.DRAINING)
            throw new IllegalStateException("rollout in flight: " + phase);
        this.agentId = agent;
        this.newParams = Map.copyOf(newP);
        this.impact = Impact.compute(oldP, newP, policy, crit, affectedNodes, totalNodes);
        this.rolloutId = "r" + SEQ.incrementAndGet();
        this.resource = "agent:" + agent;
        this.stageIdx = -1; this.canaryTicks = 0; this.dwell = 0; this.lastReason = ""; this.proposalId = "";
        this.epoch = fence.current(resource);
        this.startedMs = clock.getAsLong();
        sprt.reset();
        fleet.spawnCanary(newParams);
        fleet.weights(cfg.canaryPct, cfg.controlPct);
        phase = Phase.CANARY;
        log("ROLLOUT_CANARY_START", "id=" + rolloutId + " agent=" + agent + " canary=" + cfg.canaryPct
                + " control=" + cfg.controlPct + " impact=" + impact.json());
        metric();
        return rolloutId;
    }

    public synchronized Phase tick() {
        switch (phase) {
            case CANARY -> tickCanary();
            case AWAIT_APPROVAL -> tickApproval();
            case ROLLOUT -> tickRollout();
            default -> { }
        }
        metric();
        return phase;
    }

    private void tickCanary() {
        canaryTicks++;
        double c = fleet.canaryP99(), k = fleet.controlP99(), drop = fleet.canaryDropRate();
        if (metrics != null) {
            metrics.gauge("organism_rollout_canary_p99", c, "id", rolloutId);
            metrics.gauge("organism_rollout_control_p99", k, "id", rolloutId);
            metrics.gauge("organism_rollout_canary_drop_rate", drop, "id", rolloutId);
        }
        if (drop > cfg.maxDropRate) { abort(Phase.ROLLED_BACK, "CANARY_DROP_RATE " + drop); return; }
        if (cfg.targetP99Ms > 0 && c > cfg.targetP99Ms * 3) { abort(Phase.ROLLED_BACK, "CANARY_SLO " + c); return; }
        if (c > 0 && k > 0) sprt.add(k, c);
        if (metrics != null) {
            metrics.gauge("organism_rollout_sprt_llr", sprt.llr(), "id", rolloutId);
            metrics.gauge("organism_rollout_sprt_samples", sprt.samples(), "id", rolloutId);
        }
        switch (sprt.decision()) {
            case BETTER -> passed();
            case NOT_BETTER -> abort(Phase.ROLLED_BACK, "SPRT_NOT_BETTER n=" + sprt.samples());
            case INCONCLUSIVE -> abort(Phase.ROLLED_BACK, "SPRT_INCONCLUSIVE n=" + sprt.samples());
            case CONTINUE -> { if (canaryTicks >= cfg.maxCanaryTicks) abort(Phase.ROLLED_BACK, "SPRT_TIMEOUT ticks=" + canaryTicks); }
        }
    }

    private void passed() {
        log("ROLLOUT_SPRT_BETTER", "id=" + rolloutId + " n=" + sprt.samples() + " llr=" + sprt.llr() + " impact=" + impact.json());
        if (impact.value() > cfg.approvalThreshold) {
            Consumer<Map<String, Double>> applier = p -> beginRollout();
            proposalId = (fourEyes != null && fourEyes.enabled())
                    ? fourEyes.submit(agentId, newParams, impact.value(), applier)
                    : gate.submit(agentId, newParams, impact.value(), applier);
            phase = Phase.AWAIT_APPROVAL;
            if (metrics != null) metrics.inc("organism_rollout_pending_total", 1);
            log("ROLLOUT_PENDING_APPROVAL", "id=" + rolloutId + " proposal=" + proposalId + " impact=" + impact.value()
                    + " fourEyes=" + (fourEyes != null && fourEyes.requiresFourEyes(proposalId)));
        } else {
            log("ROLLOUT_AUTO_IN_ENVELOPE", "id=" + rolloutId + " impact=" + impact.value());
            beginRollout();
        }
    }

    private void beginRollout() {
        stageIdx = 0;
        dwell = cfg.stageDwellTicks;
        fleet.promote(newParams, cfg.stage(0));
        fleet.weights(cfg.stage(0), 0);
        phase = Phase.ROLLOUT;
        if (metrics != null) metrics.inc("organism_rollout_approved_total", 1);
        log("ROLLOUT_STAGE", "id=" + rolloutId + " share=" + cfg.stage(0));
    }

    private void tickApproval() {
        gate.sweep();
        var p = gate.get(proposalId);
        if (p == null) { abort(Phase.REJECTED, "PROPOSAL_MISSING"); return; }
        switch (p.status) {
            case REJECTED -> abort(Phase.REJECTED, "HUMAN_REJECTED by=" + p.decidedBy);
            case EXPIRED -> abort(Phase.REJECTED, "APPROVAL_EXPIRED");
            default -> { }
        }
    }

    private void tickRollout() {
        double c = fleet.canaryP99(), drop = fleet.canaryDropRate();
        if (drop > cfg.maxDropRate) { abort(Phase.ROLLED_BACK, "ROLLOUT_DROP_RATE " + drop); return; }
        if (cfg.targetP99Ms > 0 && c > cfg.targetP99Ms * 2) { abort(Phase.ROLLED_BACK, "ROLLOUT_SLO " + c); return; }
        if (--dwell > 0) return;
        if (stageIdx + 1 < cfg.stageCount()) {
            stageIdx++;
            dwell = cfg.stageDwellTicks;
            fleet.promote(newParams, cfg.stage(stageIdx));
            fleet.weights(cfg.stage(stageIdx), 0);
            log("ROLLOUT_STAGE", "id=" + rolloutId + " share=" + cfg.stage(stageIdx));
            return;
        }
        finish();
    }

    private void finish() {
        phase = Phase.DRAINING;
        epoch = fence.allocate(resource);
        log("ROLLOUT_HANDOFF", "id=" + rolloutId + " resource=" + resource + " epoch=" + epoch);
        long t0 = clock.getAsLong();
        fleet.drainOld(cfg.drainGraceMs);
        drainMs = Math.max(0, clock.getAsLong() - t0);
        phase = Phase.DONE;
        if (metrics != null) {
            metrics.inc("organism_rollouts_total", 1, "result", "done");
            metrics.gauge("organism_rollout_drain_ms", drainMs, "id", rolloutId);
            metrics.gauge("organism_rollout_epoch", epoch, "resource", resource);
        }
        log("ROLLOUT_DONE", "id=" + rolloutId + " epoch=" + epoch + " drainMs=" + drainMs
                + " totalMs=" + (clock.getAsLong() - startedMs));
    }

    private void abort(Phase end, String reason) {
        lastReason = reason;
        fleet.rollback();
        fleet.killCanary();
        phase = end;
        if (metrics != null)
            metrics.inc("organism_rollouts_total", 1, "result", end == Phase.REJECTED ? "rejected" : "rolled_back");
        log(end == Phase.REJECTED ? "ROLLOUT_REJECTED" : "ROLLOUT_ABORT", "id=" + rolloutId + " reason=" + reason);
    }

    private void metric() {
        if (metrics == null) return;
        metrics.gauge("organism_rollout_phase", phase.ordinal(), "id", rolloutId);
        metrics.gauge("organism_rollout_impact", impact.value(), "id", rolloutId);
        metrics.gauge("organism_rollout_stage_share", stageIdx < 0 ? 0 : cfg.stage(stageIdx), "id", rolloutId);
    }

    private void log(String event, String detail) { if (audit != null) audit.accept(event, detail); }

    public synchronized Phase phase() { return phase; }
    public synchronized Impact impact() { return impact; }
    public synchronized String rolloutId() { return rolloutId; }
    public synchronized String proposalId() { return proposalId; }
    public synchronized String lastReason() { return lastReason; }
    public synchronized long epoch() { return epoch; }
    public synchronized long drainMs() { return drainMs; }
    public synchronized int canaryTicks() { return canaryTicks; }
    public synchronized double stageShare() { return stageIdx < 0 ? 0 : cfg.stage(stageIdx); }

    public synchronized String json() {
        return "{\"id\":" + Json.str(rolloutId) + ",\"agent\":" + Json.str(agentId)
                + ",\"phase\":" + Json.str(phase.name()) + ",\"proposal\":" + Json.str(proposalId)
                + ",\"share\":" + Json.num(stageShare()) + ",\"epoch\":" + Json.num(epoch)
                + ",\"reason\":" + Json.str(lastReason) + ",\"impact\":" + impact.json() + "}";
    }
}
