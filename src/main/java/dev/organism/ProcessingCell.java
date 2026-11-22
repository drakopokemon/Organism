package dev.organism;

import java.util.*;

/**
 * Processing-клетка. Живёт собственной жизнью на виртуальном потоке:
 *  метаболизм (обработка нагрузки) -> эволюция (канареечная (1+1)-ES с правилом 1/5) -> почкование/апоптоз.
 */
public final class ProcessingCell implements Agent {
    public enum State { STARTING, ACTIVE, QUARANTINED, DEAD }
    static final int W = 12;   // окно оценки мутации, тиков

    final String id; final int num; final long born; String node;
    volatile State state = State.STARTING;
    Phenotype genes;
    private final SplittableRandom rng;

    double backlog, health = 1, lastMu = 100, rhoEma, fit;
    World.Result last = World.Result.IDLE;
    Genome.Telemetry tel = new Genome.Telemetry(0, 1, 0);

    int hot, cold, anomalyTicks, okTicks, quarTicks; long lastBud = -100;

    boolean permit; double sigma = 0.12; long lastTrialEnd = -100;
    Phenotype trialFrom, trialTo; double preMean, preVar; int trialAge, badTicks;
    Gate.Proposal pending; Phenotype pendingTo; long pendingSince;
    private final ArrayDeque<Double> rel = new ArrayDeque<>();   // fit клетки минус fit контрольной группы
    final List<Event> events = new ArrayList<>();
    final List<Intent> intents = new ArrayList<>();

    ProcessingCell(int num, String node, long born, Phenotype genes, long seed) {
        this.num = num; this.id = "c" + num; this.node = node; this.born = born; this.genes = genes;
        this.rng = new SplittableRandom(seed * 1_000_003L + num);
    }
    public String id() { return id; }
    boolean inTrial() { return trialTo != null; }
    Phenotype stableGenes() { return trialTo != null ? trialFrom : genes; }
    boolean canTrial(long t) {
        return state == State.ACTIVE && trialTo == null && pending == null && rel.size() >= W && t - lastTrialEnd >= W;
    }

    // ---- фаза 1: метаболизм ------------------------------------------------------------------
    void step(double lambda, Substrate.NodeState n) {
        var ctx = Organism.TICK.get();
        if (state == State.STARTING && ctx.tick() - born >= 2) state = State.ACTIVE;
        double share = n.spec.cores() / (double) Math.max(1, n.cells.size());
        double noise = Math.max(0.8, 1 + 0.04 * rng.nextGaussian());
        double in = state == State.STARTING ? 0 : lambda;
        last = World.step(genes, in, backlog, share, health, noise);
        backlog = last.backlog(); lastMu = last.mu();
        rhoEma = 0.7 * rhoEma + 0.3 * last.rho();
        double avail = in < 1 ? 1 : Math.max(0, 1 - last.errors() / in);
        double cost = World.cellCost(n.spec, genes);
        double usd = last.served() < 1 ? 5 : Math.min(5, cost / (last.served() * 3600 / 1e6));
        tel = new Genome.Telemetry(last.p99Ms(), avail, usd);
        fit = ctx.genome().fitness(tel);
    }

    // ---- фаза 2: эволюция и репликация -------------------------------------------------------
    void post(double ctlSum, int ctlN) {
        var ctx = Organism.TICK.get();
        events.clear(); intents.clear();
        if (state != State.ACTIVE && state != State.QUARANTINED) return;
        if (state == State.ACTIVE) {
            boolean inCtl = trialTo == null;
            int n = ctlN - (inCtl ? 1 : 0);
            if (n >= 1) {
                double mean = (ctlSum - (inCtl ? fit : 0)) / n;
                rel.addLast(fit - mean);
                while (rel.size() > 3 * W) rel.removeFirst();
            }
            if (ctx.evolve()) evolve(ctx);
            scale(ctx);
        }
    }

    private void evolve(TickContext ctx) {
        if (pending != null) { handlePending(ctx); return; }
        if (trialTo != null) { trialTick(ctx); return; }
        if (!permit || !canTrial(ctx.tick()) || rng.nextDouble() > 0.35) return;
        var cand = mutate(ctx.genome());
        if (cand == null) return;
        var viol = ctx.genome().violation(cand);
        if (viol.isPresent()) {   // иммунный барьер: отклоняем ДО запуска, штрафуем шаг
            sigma = Math.max(0.03, sigma * 0.9); lastTrialEnd = ctx.tick();
            emit(ctx, "MUTATION_BLOCKED", "reason", viol.get(), "change", diff(genes, cand));
            return;
        }
        var nd = ctx.sub().nodes.get(node);
        double grow = World.memMb(cand) - World.memMb(genes);
        if (grow > nd.headroom / Math.max(1, nd.cells.size())) {   // иммунный барьер: ёмкость узла
            sigma = Math.max(0.03, sigma * 0.9); lastTrialEnd = ctx.tick();
            emit(ctx, "MUTATION_BLOCKED", "reason", "node RAM", "change", diff(genes, cand));
            return;
        }
        var ch = diff(genes, cand);
        boolean high = ch.keySet().stream().anyMatch(g -> ctx.genome().gene(g).risk() == Genome.Risk.HIGH);
        if (high) {
            pending = new Gate.Proposal(id + "@" + ctx.tick(), id, ctx.tick(), ch);
            pendingTo = cand; pendingSince = ctx.tick();
            emit(ctx, "PROPOSAL", "id", pending.id(), "change", ch);
            handlePending(ctx);
        } else begin(ctx, cand);
    }

    private void handlePending(TickContext ctx) {
        switch (ctx.gate().review(pending, ctx)) {
            case PENDING -> {
                if (ctx.tick() - pendingSince > 60) { emit(ctx, "PROPOSAL_EXPIRED", "id", pending.id()); clearPending(ctx); }
            }
            case DENIED -> { emit(ctx, "PROPOSAL_DENIED", "id", pending.id()); sigma = Math.max(0.03, sigma * 0.9); clearPending(ctx); }
            case APPROVED -> { emit(ctx, "PROPOSAL_APPROVED", "id", pending.id()); var c = pendingTo; pending = null; begin(ctx, c); }
        }
    }
    private void clearPending(TickContext ctx) { pending = null; pendingTo = null; lastTrialEnd = ctx.tick(); }

    private void begin(TickContext ctx, Phenotype cand) {
        var pre = tail(W);
        preMean = mean(pre); preVar = var(pre, preMean);
        trialFrom = genes; trialTo = cand; genes = cand; trialAge = 0; badTicks = 0;
        emit(ctx, "TRIAL_START", "change", diff(trialFrom, cand));
    }

    private void trialTick(TickContext ctx) {
        trialAge++;
        boolean bad = tel.availability() < 0.75 || tel.p99Ms() > 4 * ctx.genome().p99Target();
        badTicks = bad ? badTicks + 1 : 0;
        if (badTicks >= 3) { finish(ctx, false, "ABORT", Double.NaN); return; }   // аварийный откат
        if (trialAge < W) return;
        var tr = tail(W);
        double m = mean(tr), gain = m - preMean;
        double se = Math.sqrt(var(tr, m) / W + preVar / W);
        finish(ctx, gain > Math.max(0.004, se), null, gain);
    }

    private void finish(TickContext ctx, boolean ok, String forced, double gain) {
        var from = trialFrom; var to = trialTo; var ch = diff(from, to);
        if (ok) {
            sigma = Math.min(0.4, sigma * 1.25);    // правило 1/5: успех -> шаг больше
            emit(ctx, "MUTATION_ACCEPTED", "change", ch, "gain", gain);
            intents.add(new Intent.Adopt(this, from, to));
        } else {
            genes = from;
            sigma = Math.max(0.03, sigma * 0.82);   // неудача -> шаг меньше
            emit(ctx, forced != null ? forced : "MUTATION_REJECTED", "change", ch, "gain", gain);
        }
        trialFrom = trialTo = null; lastTrialEnd = ctx.tick();
    }

    private Phenotype mutate(Genome g) {
        var cand = genes; int k = rng.nextInt(10) < 7 ? 1 : 2;
        for (int i = 0; i < k; i++) {
            var gene = g.genes().get(rng.nextInt(g.genes().size()));
            double cur = cand.get(gene.name());
            long steps = Math.round(rng.nextGaussian() * sigma * (gene.max() - gene.min()) / gene.step());
            if (steps == 0) steps = rng.nextBoolean() ? 1 : -1;
            cand = cand.with(gene.name(), gene.snap(cur + steps * gene.step()));
        }
        return diff(genes, cand).isEmpty() ? null : cand;
    }

    private void scale(TickContext ctx) {
        long t = ctx.tick();
        if (rhoEma > 0.7) { hot++; cold = 0; } else if (rhoEma < 0.35) { cold++; hot = 0; } else hot = cold = 0;
        if (hot >= 2 && t - lastBud >= 6 && rng.nextDouble() < 0.5) {
            ctx.sub().pickNode(ctx.sub().nodes.get(node), ctx.genome(), t, stableGenes())
               .ifPresent(n -> intents.add(new Intent.Bud(this, n)));
            lastBud = t; hot = 0;
        }
        if (cold >= 12 && trialTo == null && rng.nextDouble() < 0.25) { intents.add(new Intent.Apoptosis(this)); cold = 0; }
    }

    // ---- утилиты -----------------------------------------------------------------------------
    static Map<String, double[]> diff(Phenotype a, Phenotype b) {
        var m = new TreeMap<String, double[]>();
        for (var e : b.g().entrySet()) { double o = a.get(e.getKey()); if (o != e.getValue()) m.put(e.getKey(), new double[]{o, e.getValue()}); }
        return m;
    }
    private double[] tail(int n) {
        var all = rel.stream().mapToDouble(Double::doubleValue).toArray();
        return Arrays.copyOfRange(all, Math.max(0, all.length - n), all.length);
    }
    private static double mean(double[] a) { return Arrays.stream(a).average().orElse(0); }
    private static double var(double[] a, double m) { return Arrays.stream(a).map(x -> (x - m) * (x - m)).sum() / Math.max(1, a.length - 1); }
    private void emit(TickContext ctx, String type, Object... kv) { events.add(Event.of(ctx.tick(), type, id, kv)); }
}
