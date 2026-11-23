package dev.organism.rollout;

/**
 * Детерминированный сплит трафика: canary% / control% / остальное на базовое поколение.
 * route() -- sticky-бакет по splitmix64(requestId): один и тот же запрос всегда в одной группе.
 * weights() -- целые веса для взвешенного роутера (CellHost /cells TSV читает LoadGen), сумма 10000.
 */
public record TrafficSplit(double canaryPct, double controlPct) {
    public enum Bucket { CANARY, CONTROL, BASE }

    public TrafficSplit {
        if (canaryPct < 0 || controlPct < 0 || canaryPct + controlPct > 100.0 + 1e-9)
            throw new IllegalArgumentException("split: canary=" + canaryPct + " control=" + controlPct);
    }

    public Bucket route(long requestId) {
        long h = mix(requestId) % 10000L;
        long c = Math.round(canaryPct * 100);
        long k = Math.round((canaryPct + controlPct) * 100);
        if (h < c) return Bucket.CANARY;
        if (h < k) return Bucket.CONTROL;
        return Bucket.BASE;
    }

    /** Порядок весов: canary-агенты, затем control-агенты, затем базовое поколение. */
    public int[] weights(int canaryN, int controlN, int baseN) {
        if (canaryN < 0 || controlN < 0 || baseN < 0) throw new IllegalArgumentException("negative group size");
        int total = 10000;
        int cShare = canaryN == 0 ? 0 : (int) Math.round(canaryPct * 100);
        int kShare = controlN == 0 ? 0 : (int) Math.round(controlPct * 100);
        int bShare = total - cShare - kShare;
        if (baseN == 0) { cShare += bShare; bShare = 0; }
        if (canaryN == 0 && controlN == 0) bShare = total;
        int[] w = new int[canaryN + controlN + baseN];
        fill(w, 0, canaryN, cShare);
        fill(w, canaryN, controlN, kShare);
        fill(w, canaryN + controlN, baseN, bShare);
        return w;
    }

    private static void fill(int[] w, int off, int n, int share) {
        if (n <= 0) return;
        if (share <= 0) { for (int i = 0; i < n; i++) w[off + i] = 0; return; }
        int each = Math.max(1, share / n);
        int rest = share - each * n;
        for (int i = 0; i < n; i++) w[off + i] = each + (i < rest ? 1 : 0);
    }

    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        z = z ^ (z >>> 31);
        return z & Long.MAX_VALUE;
    }
}
