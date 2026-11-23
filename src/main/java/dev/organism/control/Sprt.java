package dev.organism.control;

/**
 * Столп 2: SPRT (Wald) вместо фиксированного окна канарейки.
 * Метрика — LOG-latency: P99 heavy-tailed, голое среднее врёт; инкремент ОБРЕЗАН (clip) — выброс не переворачивает тест.
 * H0: mu_diff = 0 (не лучше), H1: mu_diff = -delta (улучшение не меньше delta в log-единицах).
 * alpha/beta — ошибки I/II рода; maxN — предохранитель: INCONCLUSIVE -> остаёмся на контроле (fail-closed).
 * Детерминирован при фиксированной последовательности сэмплов.
 */
public final class Sprt {
    public enum Decision { CONTINUE, BETTER, NOT_BETTER, INCONCLUSIVE }

    private final double delta, sigma, clip, upper, lower;
    private final long maxN;
    private double llr;
    private long n;
    private Decision decision = Decision.CONTINUE;

    public Sprt(double alpha, double beta, double delta, double sigma, double clip, long maxN) {
        if (alpha <= 0 || beta <= 0 || alpha + beta >= 1) throw new IllegalArgumentException("sprt: bad alpha/beta");
        if (delta <= 0 || sigma <= 0 || clip <= 0 || maxN <= 0) throw new IllegalArgumentException("sprt: bad params");
        this.delta = delta;
        this.sigma = sigma;
        this.clip = clip;
        this.maxN = maxN;
        this.upper = Math.log((1 - beta) / alpha);
        this.lower = Math.log(beta / (1 - alpha));
    }

    /** Парные сэмплы latency (мс): log(t/c) гасит тяжёлые хвосты. */
    public Decision add(double controlMs, double treatmentMs) {
        double c = Math.max(controlMs, 1e-9), t = Math.max(treatmentMs, 1e-9);
        return addLogDiff(Math.log(t / c));
    }

    public synchronized Decision addLogDiff(double z) {
        if (decision != Decision.CONTINUE) return decision;
        double bounded = Math.max(-clip, Math.min(clip, z));
        llr += (-delta * bounded - delta * delta / 2) / (sigma * sigma);
        n++;
        if (llr >= upper) decision = Decision.BETTER;
        else if (llr <= lower) decision = Decision.NOT_BETTER;
        else if (n >= maxN) decision = Decision.INCONCLUSIVE;
        return decision;
    }

    public synchronized Decision decision() { return decision; }
    public synchronized long samples() { return n; }
    public synchronized double llr() { return llr; }
    public synchronized void reset() { llr = 0; n = 0; decision = Decision.CONTINUE; }
}
