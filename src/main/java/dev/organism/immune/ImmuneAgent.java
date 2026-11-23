package dev.organism.immune;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Cluster-level иммунитет: p99-трекинг себя и соседей по gossip-фактам.
 * HEALTHY -> DEGRADED (degradeTicks подряд p99 > target*degradeMult)
 *         -> QUARANTINED (quarantineTicks подряд p99 > target*quarantineMult).
 * Recovery: recoverTicks подряд healthy. Forget: узел пропал из view / TTL sweep.
 * Детерминированный (тикает от kernel tick, без часов). Callback -> аудит + метрики.
 * Enforcement (снятие трафика) — opt-in флаг кернела; здесь только детекция.
 */
public final class ImmuneAgent {
    public enum Health { HEALTHY, DEGRADED, QUARANTINED }
    public record Event(long tick, String node, Health from, Health to, double p99Ms, String reason) {}

    private static final class S {
        Health h = Health.HEALTHY;
        int bad, good;
        long lastSeen;
        double p99;
    }

    private final Map<String, S> nodes = new HashMap<>();
    private final double target, degradeMult, quarantineMult;
    private final int degradeTicks, quarantineTicks, recoverTicks;
    private final long forgetTicks;
    private final Consumer<Event> sink;

    public ImmuneAgent(double targetP99Ms, int degradeTicks, int quarantineTicks,
                       int recoverTicks, long forgetTicks, Consumer<Event> sink) {
        this(targetP99Ms, 2.0, 4.0, degradeTicks, quarantineTicks, recoverTicks, forgetTicks, sink);
    }

    public ImmuneAgent(double targetP99Ms, double degradeMult, double quarantineMult,
                       int degradeTicks, int quarantineTicks, int recoverTicks,
                       long forgetTicks, Consumer<Event> sink) {
        this.target = Math.max(1e-9, targetP99Ms);
        this.degradeMult = degradeMult;
        this.quarantineMult = quarantineMult;
        this.degradeTicks = Math.max(1, degradeTicks);
        this.quarantineTicks = Math.max(this.degradeTicks, quarantineTicks);
        this.recoverTicks = Math.max(1, recoverTicks);
        this.forgetTicks = Math.max(1, forgetTicks);
        this.sink = sink != null ? sink : e -> {};
    }

    public synchronized void observe(long tick, String node, double p99) {
        S s = nodes.computeIfAbsent(node, k -> new S());
        s.lastSeen = tick;
        s.p99 = p99;
        Health from = s.h;
        if (p99 <= target * degradeMult) {
            s.bad = 0;
            s.good++;
            if (s.h == Health.QUARANTINED && s.good >= recoverTicks) s.h = Health.HEALTHY;
            else if (s.h == Health.DEGRADED) s.h = Health.HEALTHY;
        } else {
            s.good = 0;
            s.bad++;
            if (p99 > target * quarantineMult && s.bad >= quarantineTicks) s.h = Health.QUARANTINED;
            else if (s.bad >= degradeTicks && s.h == Health.HEALTHY) s.h = Health.DEGRADED;
        }
        if (s.h != from)
            sink.accept(new Event(tick, node, from, s.h, p99, s.h == Health.HEALTHY ? "recovery" : "p99_breach"));
    }

    public synchronized void forget(long tick, String node) {
        S s = nodes.remove(node);
        if (s != null)
            sink.accept(new Event(tick, node, s.h, Health.HEALTHY, s.p99, "forget"));
    }

    public synchronized void sweep(long tick) {
        var gone = new ArrayList<String>();
        for (var e : nodes.entrySet())
            if (tick - e.getValue().lastSeen > forgetTicks) gone.add(e.getKey());
        for (String n : gone) forget(tick, n);
    }

    public synchronized boolean isQuarantined(String node) {
        S s = nodes.get(node);
        return s != null && s.h == Health.QUARANTINED;
    }

    public synchronized Health health(String node) {
        S s = nodes.get(node);
        return s == null ? Health.HEALTHY : s.h;
    }

    public synchronized Map<String, Health> snapshot() {
        var m = new TreeMap<String, Health>();
        nodes.forEach((k, v) -> m.put(k, v.h));
        return m;
    }
}
