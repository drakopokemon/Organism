package dev.organism;

import dev.organism.ProcessingCell.State;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Оркестратор тика: гонит жизненный цикл клеток в lockstep, детерминированно применяя их намерения. */
public final class Organism implements AutoCloseable {
    static final ScopedValue<TickContext> TICK = ScopedValue.newInstance();

    final Genome genome; final Substrate sub; final Gate gate; final boolean evolve, chaos; final long seed;
    final TreeMap<String, ProcessingCell> cells = new TreeMap<>();
    final TreeMap<String, ImmuneCell> immune = new TreeMap<>();
    final Journal journal; final SplittableRandom rng; final Storage store;
    final Map<String, Integer> counts = new TreeMap<>();
    final List<double[]> history = new ArrayList<>();   // t, rps, p99, avail, cost, usd/M, fit, replicas
    final Deque<Map<String, Object>> recent = new ArrayDeque<>();
    List<Event> lastEvents = List.of();
    double medianP99, lastP99, lastAvail = 1;
    Phenotype champion;
    private int nextId, births, deaths, maxBirths, maxDeaths;
    private final ArrayDeque<String> down = new ArrayDeque<>();   // убитые узлы (последний — первым)

    public Organism(Genome genome, long seed, Gate gate, boolean evolve, boolean chaos, Path journalPath) throws java.io.IOException {
        this.genome = genome; this.seed = seed; this.gate = gate; this.evolve = evolve; this.chaos = chaos;
        this.rng = new SplittableRandom(seed);
        this.journal = new Journal(journalPath);
        var specs = new ArrayList<Substrate.Node>();
        for (int i = 0; i < 4; i++) specs.add(new Substrate.Node("eu-a-" + i, "eu-a", 4, 4096, 0.30 + 0.02 * i));
        for (int i = 0; i < 4; i++) specs.add(new Substrate.Node("eu-b-" + i, "eu-b", 4, 4096, 0.26 + 0.02 * i));
        for (int i = 0; i < 2; i++) specs.add(new Substrate.Node("eu-c-" + i, "eu-c", 4, 4096, 0.22 + 0.02 * i));
        for (int i = 0; i < 2; i++) specs.add(new Substrate.Node("us-1-" + i, "us-1", 4, 4096, 0.12));   // дёшево, но запрещено геномом
        sub = new Substrate(specs, seed);
        sub.nodes.keySet().forEach(n -> immune.put(n, new ImmuneCell(n)));
        store = genome.storage() != null ? new Storage(sub, genome) : null;
        // «Высев»: минимально жизнеспособная популяция, распределённая по разрешённым узлам
        var allowed = sub.nodes.values().stream().filter(n -> genome.zoneAllowed(n.spec.zone())).toList();
        for (int i = 0; i < 6; i++) spawn(allowed.get((i * 3) % allowed.size()).spec.id(), genome.initial(), 0);
        journal.write(Map.of("ev", "GENESIS", "genome", genome.name(), "seed", seed, "evolve", evolve));
    }

    private ProcessingCell spawn(String node, Phenotype g, long t) {
        var c = new ProcessingCell(nextId++, node, t, g, seed);
        cells.put(c.id, c); sub.nodes.get(node).cells.add(c);
        return c;
    }
    private void remove(ProcessingCell c) {
        c.state = State.DEAD; cells.remove(c.id); sub.nodes.get(c.node).cells.remove(c);
    }

    private <T> void parallel(Collection<T> items, Consumer<T> body, TickContext ctx) throws Exception {
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            var fs = new ArrayList<Future<?>>();
            for (T it : items) fs.add(ex.submit(() -> ScopedValue.where(TICK, ctx).run(() -> body.accept(it))));
            for (var f : fs) f.get();
        }
    }

    // ------------------------------------------------------------------------------------------
    public Map<String, Object> tick(long t) throws Exception {
        var ctx = new TickContext(t, genome, sub, gate, evolve, lastP99, lastAvail);
        var ev = new ArrayList<Event>();
        births = deaths = 0;
        if (chaos) chaos(t, ev);
        sub.gossip(t);
        double lambda = World.lambda(t, seed);
        if (store != null) store.step(t, lambda, ev);

        // балансировщик: веса ~ ёмкость; карантин получает 2% «пробного» трафика
        var all = new ArrayList<>(cells.values());
        var w = new HashMap<String, Double>(); double tw = 0;
        for (var c : all) {
            double x = switch (c.state) { case ACTIVE -> c.lastMu; case QUARANTINED -> 0.02 * c.lastMu; default -> 0.0; };
            w.put(c.id, x); tw += x;
        }
        final double totalW = tw;
        parallel(all, c -> c.step(totalW > 0 ? lambda * w.get(c.id) / totalW : 0, sub.nodes.get(c.node)), ctx);

        // телеметрия организма
        double served = 0, errs = totalW > 0 ? 0 : lambda, ctlSum = 0; int ctlN = 0, active = 0;
        var act = new ArrayList<Double>();
        for (var c : all) {
            served += c.last.served(); errs += c.last.errors();
            if (c.state == State.ACTIVE) { active++; act.add(c.last.p99Ms()); if (!c.inTrial()) { ctlSum += c.fit; ctlN++; } }
        }
        Collections.sort(act); medianP99 = act.isEmpty() ? 0 : act.get(act.size() / 2);
        var byP99 = all.stream().filter(c -> c.last.served() > 0).sorted((a, b) -> Double.compare(b.last.p99Ms(), a.last.p99Ms())).toList();
        double p99 = 0, acc = 0;
        for (var c : byP99) { acc += c.last.served(); p99 = c.last.p99Ms(); if (acc >= 0.01 * served) break; }
        double avail = lambda < 1 ? 1 : Math.max(0, Math.min(1, 1 - errs / lambda));
        double cost = sub.activeCost(), usd = served < 1 ? 5 : Math.min(5, cost / (served * 3600 / 1e6));
        double fit = genome.fitness(new Genome.Telemetry(p99, avail, usd));
        lastP99 = p99; lastAvail = avail;

        // канареечные разрешения: не более 25% популяции экспериментирует одновременно
        int running = (int) all.stream().filter(c -> c.inTrial() || c.pending != null).count();
        int slots = Math.max(1, active / 4) - running;
        for (var c : all) { c.permit = slots > 0 && c.canTrial(t); if (c.permit) slots--; }
        maxBirths = Math.max(2, active / 3); maxDeaths = Math.max(1, active / 10);

        for (var n : sub.nodes.values()) n.headroom = sub.freeMem(n);
        final double cs = ctlSum; final int cn = ctlN;
        parallel(all, c -> c.post(cs, cn), ctx);
        parallel(immune.values(), i -> i.inspect(this, t), ctx);

        var intents = new ArrayList<Intent>();
        for (var c : all) { ev.addAll(c.events); intents.addAll(c.intents); }
        for (var i : immune.values()) intents.addAll(i.out);
        for (var in : intents) resolve(in, t, ev);

        if (genome.capabilities().contains(Genome.Capability.SPORULATE)) sporulate(t);

        lastEvents = ev;
        for (var e : ev) {
            counts.merge(e.type(), 1, Integer::sum);
            journal.write(e.toMap());
            if (!List.of("BUD", "APOPTOSIS", "ADOPT").contains(e.type())) { recent.addFirst(e.toMap()); if (recent.size() > 40) recent.removeLast(); }
        }
        history.add(new double[]{t, lambda, p99, avail, cost, usd, fit, cells.size()});
        return snapshot(t, lambda, p99, avail, cost, usd, fit, ev);
    }

    // ------------------------------------------------------------------------------------------
    private void resolve(Intent in, long t, List<Event> ev) {
        switch (in) {
            case Intent.Bud b -> {
                var p = b.parent(); var n = sub.nodes.get(b.node());
                if (births >= maxBirths || p.state != State.ACTIVE || !cells.containsKey(p.id) || cells.size() >= genome.maxReplicas()) return;
                if (!n.alive || !genome.zoneAllowed(n.spec.zone()) || sub.freeMem(n) < World.memMb(p.stableGenes())) return;
                if (sub.activeCost() + (n.cells.isEmpty() ? n.spec.cost() : 0) > genome.maxCost()) return;
                var c = spawn(b.node(), p.stableGenes(), t); births++;
                ev.add(Event.of(t, "BUD", c.id, "parent", p.id, "node", b.node()));
            }
            case Intent.Apoptosis a -> {
                if (deaths >= maxDeaths || cells.size() <= genome.minReplicas() || !cells.containsKey(a.cell().id)) return;
                sub.spores.remove(a.cell().id); remove(a.cell()); deaths++;
                ev.add(Event.of(t, "APOPTOSIS", a.cell().id, "node", a.cell().node));
            }
            case Intent.Quarantine q -> {
                if (q.cell().state != State.ACTIVE) return;
                q.cell().state = State.QUARANTINED; q.cell().quarTicks = 0; q.cell().okTicks = 0;
                ev.add(Event.of(t, "QUARANTINE", q.cell().id, "p99", q.cell().last.p99Ms(), "median", medianP99));
            }
            case Intent.Release r -> {
                if (r.cell().state != State.QUARANTINED) return;
                r.cell().state = State.ACTIVE; r.cell().anomalyTicks = 0;
                ev.add(Event.of(t, "RELEASE", r.cell().id));
            }
            case Intent.Kill k -> {
                var c = k.cell(); if (!cells.containsKey(c.id)) return;
                var g = champion != null ? champion : c.stableGenes(); String node = c.node;
                sub.spores.remove(c.id); remove(c);
                if (sub.freeMem(sub.nodes.get(node)) < World.memMb(g)) g = genome.initial();
                var nc = spawn(node, g, t);
                ev.add(Event.of(t, "REPLACED", c.id, "by", nc.id, "why", k.why()));
            }
            case Intent.Germinate g -> {
                var s = g.spore();
                if (!sub.spores.containsKey(s.cellId()) || cells.containsKey(s.cellId()) || cells.size() >= genome.maxReplicas()) return;
                sub.pickNode(sub.nodes.get(g.guardian()), genome, t, s.genes()).ifPresent(node -> {
                    var n = sub.nodes.get(node);
                    if (!n.alive || sub.freeMem(n) < World.memMb(s.genes())) return;
                    sub.spores.remove(s.cellId());
                    var c = spawn(node, s.genes(), t);
                    ev.add(Event.of(t, "GERMINATE", c.id, "spore", s.cellId(), "guardian", g.guardian(), "node", node));
                });
            }
            case Intent.Replicate r -> { if (store != null) store.replicate(r, t, ev); }
            case Intent.Adopt a -> {
                champion = a.to(); int n = 0;
                for (var c : cells.values()) {
                    if (c == a.winner() || c.state != State.ACTIVE || c.inTrial() || rng.nextDouble() < 0.5) continue;
                    var np = c.genes;
                    for (var k : ProcessingCell.diff(a.from(), a.to()).keySet()) np = np.with(k, a.to().get(k));
                    double grow = World.memMb(np) - World.memMb(c.genes);
                    if (genome.violation(np).isEmpty() && sub.freeMem(sub.nodes.get(c.node)) >= grow) { c.genes = np; n++; }
                }
                ev.add(Event.of(t, "ADOPT", a.winner().id, "cells", n));
            }
        }
    }

    private void sporulate(long t) {
        var alive = sub.nodes.values().stream().filter(n -> n.alive && genome.zoneAllowed(n.spec.zone())).map(n -> n.spec.id()).toList();
        for (var c : cells.values()) {
            if (c.state != State.ACTIVE || (t + c.num) % 5 != 0) continue;
            var hosts = new ArrayList<>(alive); hosts.remove(c.node);
            Collections.shuffle(hosts, new Random(rng.nextLong()));
            sub.spores.put(c.id, new Substrate.Spore(c.id, c.stableGenes(), c.node, List.copyOf(hosts.subList(0, Math.min(2, hosts.size())))));
        }
    }

    /** Хаос-сценарий: детерминированный, одинаковый для A/B. */
    private void chaos(long tAbs, List<Event> ev) {
        long t0 = tAbs, t = tAbs % World.SCENARIO_PERIOD;
        if (t == 140 || t == 360) {
            var victim = sub.nodes.values().stream().filter(n -> n.alive)
                    .max(Comparator.<Substrate.NodeState>comparingInt(n -> n.cells.size()).thenComparing(n -> n.spec.id())).orElseThrow();
            int lost = victim.cells.size();
            for (var c : new ArrayList<>(victim.cells)) remove(c);
            sub.kill(victim.spec.id()); down.push(victim.spec.id());
            if (store != null) store.nodeDown(victim.spec.id(), t0, ev);
            ev.add(Event.of(t0, "CHAOS_NODE_DOWN", victim.spec.id(), "cellsLost", lost));
        }
        if (t == 260) {
            var act = cells.values().stream().filter(c -> c.state == State.ACTIVE && !c.inTrial()).toList();
            if (!act.isEmpty()) { var c = act.get(rng.nextInt(act.size())); c.health = 6; ev.add(Event.of(t0, "CHAOS_POISON", c.id)); }
        }
        if ((t == 420 || t == 520) && !down.isEmpty()) {          // 420 — последний убитый, 520 — все оставшиеся
            do {
                var n = down.pop(); sub.revive(n, t0);
                if (store != null) store.nodeRevived(n);
                ev.add(Event.of(t0, "NODE_REVIVED", n));
            } while (t == 520 && !down.isEmpty());
        }
    }

    private Map<String, Object> snapshot(long t, double rps, double p99, double avail, double cost, double usd, double fit, List<Event> ev) {
        var m = new LinkedHashMap<String, Object>();
        m.put("t", t); m.put("rps", rps); m.put("p99", p99); m.put("target", genome.p99Target()); m.put("avail", avail);
        m.put("cost", cost); m.put("maxCost", genome.maxCost()); m.put("usd", usd); m.put("fit", fit); m.put("replicas", cells.size());
        var genes = new LinkedHashMap<String, Object>();
        for (var g : genome.genes()) {
            double s = 0; int n = 0;
            for (var c : cells.values()) if (c.state == State.ACTIVE) { s += c.stableGenes().get(g.name()); n++; }
            genes.put(g.name(), new double[]{n == 0 ? g.initial() : s / n, g.min(), g.max()});
        }
        m.put("genes", genes);
        var nodes = new ArrayList<Object>();
        for (var n : sub.nodes.values()) {
            var cl = new ArrayList<Object>();
            for (var c : n.cells) cl.add(List.of(c.id, c.state.ordinal(), Math.round(c.last.rho() * 100) / 100.0, c.inTrial() ? 1 : 0));
            nodes.add(Map.of("id", n.spec.id(), "zone", n.spec.zone(), "up", n.alive, "banned", !genome.zoneAllowed(n.spec.zone()), "cells", cl, "store", n.store.size()));
        }
        m.put("nodes", nodes);
        if (store != null) {
            var s = store.stats(); var d = new LinkedHashMap<String, Object>();
            d.put("shards", s.map()); d.put("total", s.shards()); d.put("R", store.replication);
            d.put("healthy", s.healthy()); d.put("degraded", s.degraded()); d.put("lost", s.lost());
            d.put("copying", s.copying()); d.put("lag", s.lag()); d.put("writesLost", s.writesLost()); d.put("repaired", s.repaired());
            m.put("data", d);
        }
        m.put("events", ev.stream().filter(e -> !List.of("BUD", "APOPTOSIS", "ADOPT", "TRIAL_START").contains(e.type())).map(Event::toMap).toList());
        if (gate instanceof Gate.Manual mg) m.put("pending", mg.pending().stream().map(p -> Map.of("id", p.id(), "cell", p.cell(), "changes", p.changes())).toList());
        return m;
    }

    /** Агрегат по окну тиков [from, to). */
    double[] summary(int from, int to) {
        double fit = 0, bad = 0, cost = 0, usd = 0, av = 0; int n = 0;
        for (var h : history) {
            if (h[0] < from || h[0] >= to) continue;
            n++; fit += h[6]; cost += h[4]; usd += h[5]; av += h[3]; if (h[2] > genome.p99Target()) bad++;
        }
        n = Math.max(n, 1);
        return new double[]{fit / n, 100 * bad / n, cost / n, usd / n, 100 * av / n};
    }

    @Override public void close() throws java.io.IOException { journal.close(); }
}
