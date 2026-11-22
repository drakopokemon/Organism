package dev.organism;

import java.util.SplittableRandom;

/**
 * Физика мира: очередная модель (Sakasegawa-аппроксимация M/M/c + backlog) со скрытыми
 * константами. Эволюция НЕ знает этих формул — она находит оптимум только по телеметрии.
 */
final class World {
    private World() {}
    static final long SCENARIO_PERIOD = 600;   // сценарий хаоса/всплесков повторяется каждые 600 тиков
    static final double WORKING_SET_MB = 600, HIT_MS = 3, MISS_MS = 40;

    record Result(double served, double errors, double p99Ms, double backlog, double rho, double mu) {
        static final Result IDLE = new Result(0, 0, 0, 0, 0, 100);
    }

    static double memMb(Phenotype p) {
        return 120 + p.get("cacheMb") + 8 * p.get("pool") + 0.01 * p.get("queueLimit");
    }

    static double cellCost(Substrate.Node n, Phenotype p) {
        return n.cost() * (0.6 * Math.min(1, p.get("pool") / n.cores()) + 0.4 * memMb(p) / n.memMb());
    }

    static Result step(Phenotype p, double lambda, double backlog, double share, double health, double noise) {
        double pool = p.get("pool"), cache = p.get("cacheMb"), timeout = p.get("timeoutMs"),
               batch = p.get("batch"), qlim = p.get("queueLimit");
        double hit = 1 - Math.exp(-cache / (WORKING_SET_MB / 2));
        double s = (hit * HIT_MS + (1 - hit) * MISS_MS)
                 * (1 - 0.35 * (1 - 1 / batch))                // батчинг амортизирует
                 * (1 + 0.03 * Math.max(0, pool - share))      // overhead переподписки CPU
                 * health;
        double c = Math.max(0.25, Math.min(pool, share));      // CPU-bound эффективные «серверы»
        double mu = c * 1000 / s;
        double bw = batch <= 1 ? 0 : Math.min(25, (batch - 1) / 2 * 1000 / Math.max(lambda, 1));
        double offered = backlog + lambda, served = Math.min(offered, mu), nb = offered - served;
        double dropped = Math.max(0, nb - Math.min(qlim, mu * timeout / 1000));
        nb -= dropped;
        double rho = lambda / mu, rq = Math.min(rho, 0.97), cc = Math.max(1, c);
        double wq = s * Math.pow(rq, Math.sqrt(2 * (cc + 1)) - 1) / (cc * (1 - rq));
        double p99 = (s * 2.5 + wq * 4.6 + bw) * noise + nb / mu * 1000;
        double timedOut = p99 > timeout ? served * Math.min(1, (p99 - timeout) / p99) * 0.5 : 0;
        return new Result(served - timedOut, dropped + timedOut, Math.min(p99, timeout), nb, rho, mu);
    }

    /** Входящий трафик: синусоида + два всплеска + шум. */
    static double lambda(long t, long seed) {
        double base = 520 + 220 * Math.sin(2 * Math.PI * t / 360.0);
        double noise = 1 + 0.08 * (new SplittableRandom(seed * 31 + t).nextDouble() - 0.5);
        long c = t % SCENARIO_PERIOD;
        return (base + bump(c, 170, 215, 800) + bump(c, 430, 480, 1100)) * noise;
    }
    private static double bump(long t, long a, long b, double h) {
        if (t < a || t > b) return 0;
        return Math.min(1, Math.min((t - a) / 10.0, (b - t) / 10.0)) * h;
    }
}
