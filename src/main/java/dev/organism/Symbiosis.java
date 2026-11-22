package dev.organism;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Демо симбиоза (--symbiont): пул из 3 РЕАЛЬНЫХ внешних процессов под управлением обёрток, живой трафик,
 * kill -9 процессов посреди работы и простая эволюция параметров обёртки (timeoutMs, retries) по реальной телеметрии.
 */
final class Symbiosis {
    /** Пул обёрток: балансировка + ретраи. Гены пула: timeoutMs, retries. */
    static final class Pool implements AutoCloseable {
        final List<Symbiont> members = new ArrayList<>();
        volatile int timeoutMs = 300, retries = 1;
        private final AtomicInteger rr = new AtomicInteger();
        Pool(List<String> cmd, int n) { for (int i = 0; i < n; i++) { var s = new Symbiont("svc" + i, cmd); members.add(s); s.start(); } }
        int healthy() { int n = 0; for (var s : members) if (s.state == Symbiont.State.HEALTHY) n++; return n; }

        Symbiont.Reply request() {
            long t0 = System.nanoTime(); Symbiont.Reply last = new Symbiont.Reply(false, 0, 0);
            for (int a = 0; a <= retries; a++) {
                Symbiont pick = null;
                for (int k = 0; k < members.size() && pick == null; k++) {
                    var m = members.get(Math.floorMod(rr.getAndIncrement(), members.size()));
                    if (m.available()) pick = m;
                }
                if (pick == null) { try { Thread.sleep(20); } catch (InterruptedException e) { break; } continue; }
                last = pick.call("/work", timeoutMs);
                if (last.ok()) break;
            }
            return new Symbiont.Reply(last.ok(), last.status(), (System.nanoTime() - t0) / 1e6);
        }
        @Override public void close() { members.forEach(Symbiont::close); }
    }

    record Window(int n, int ok, double p50, double p99) {
        double score() { return n == 0 ? 0 : (double) ok / n - p99 / 2000.0; }   // успех важнее, но хвост тоже штрафуется
    }

    static void run(List<String> cmd, int seconds) throws Exception {
        System.out.println(Main.C + "\n  SYMBIOSIS" + Main.R + Main.D + "  3 внешних процесса под управлением обёрток, ~150 req/s, " + seconds + " с" + Main.R);
        try (var pool = new Pool(cmd, 3); var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            long deadline = System.currentTimeMillis() + 20_000;
            while (pool.healthy() < 3 && System.currentTimeMillis() < deadline) Thread.sleep(100);
            System.out.println("  симбионты подняты: " + pool.healthy() + "/3 здоровы\n");

            var samples = new ConcurrentLinkedQueue<double[]>();   // {ok, ms}
            var stop = new AtomicBoolean();
            Thread.ofVirtual().start(() -> {
                try { while (!stop.get()) {
                    for (int i = 0; i < 15; i++) ex.submit(() -> { var r = pool.request(); samples.add(new double[]{r.ok() ? 1 : 0, r.ms()}); });
                    Thread.sleep(100);
                } } catch (InterruptedException ignored) {}
            });

            Window base = null, trial = null; int oldTimeout = 0, oldRetries = 0;
            var rnd = new Random(7); var acc = new ArrayList<double[]>(); int lastRestarts = 0;
            for (int s = 1; s <= seconds; s++) {
                Thread.sleep(1000);
                var w = drain(samples); acc.add(new double[]{w.n(), w.ok(), w.p99()});
                String ev = "";
                if (s == 10) { pool.members.get(0).crash(); ev = "  \u001b[31m💀 kill -9 svc0\u001b[0m"; }
                if (s == 26) { pool.members.get(1).crash(); pool.members.get(2).crash(); ev = "  \u001b[31m💀 kill -9 svc1+svc2\u001b[0m"; }
                System.out.printf("  t=%2ds healthy=%d/3 restarts=%d  ok=%5.1f%%  p50=%4.0fms p99=%4.0fms  timeout=%dms retries=%d%s%n",
                    s, pool.healthy(), pool.members.stream().mapToInt(m -> m.restarts.get()).sum(),
                    w.n() == 0 ? 0 : 100.0 * w.ok() / w.n(), w.p50(), w.p99(), pool.timeoutMs, pool.retries, ev);

                if (s % 4 == 0) {   // каждые 4 с: решение (1+1)-эволюции параметров обёртки
                    int rs = pool.members.stream().mapToInt(m -> m.restarts.get()).sum();
                    boolean disturbed = rs != lastRestarts || pool.healthy() < 3;   // в окне был сбой — сравнение нечестное
                    lastRestarts = rs;
                    var win = merge(acc.subList(Math.max(0, acc.size() - 4), acc.size()));
                    if (disturbed) {
                        if (trial != null) { pool.timeoutMs = oldTimeout; pool.retries = oldRetries; trial = null; System.out.println(Main.D + "        ✘ проба отменена: во время неё был сбой процесса" + Main.R); }
                        else System.out.println(Main.D + "        … окно со сбоем — не используем для сравнения" + Main.R);
                    } else if (trial == null) {
                        base = win; oldTimeout = pool.timeoutMs; oldRetries = pool.retries;
                        if (rnd.nextBoolean()) { int[] opts = {50, 100, 150, 200, 300, 400, 600}; do pool.timeoutMs = opts[rnd.nextInt(opts.length)]; while (pool.timeoutMs == oldTimeout); }
                        else pool.retries = clamp(oldRetries + (rnd.nextBoolean() ? 1 : -1), 0, 3);
                        if (pool.timeoutMs == oldTimeout && pool.retries == oldRetries) pool.retries = oldRetries == 0 ? 1 : oldRetries - 1;
                        trial = win; System.out.println(Main.D + "        ⟳ проба: timeout " + oldTimeout + "→" + pool.timeoutMs + ", retries " + oldRetries + "→" + pool.retries + Main.R);
                    } else {
                        boolean ok = win.score() > base.score() + 0.002;
                        if (!ok) { pool.timeoutMs = oldTimeout; pool.retries = oldRetries; }
                        System.out.printf("        %s%s (score %.3f → %.3f)%n", ok ? Main.G + "✔ принято" : Main.D + "✘ откат", Main.R, base.score(), win.score());
                        trial = null;
                    }
                }
            }
            if (trial != null) { pool.timeoutMs = oldTimeout; pool.retries = oldRetries; }   // незавершённую пробу не засчитываем
            stop.set(true);
            var tot = merge(acc);
            System.out.printf("%n  итог: запросов=%d, успешных=%.2f%%, перезапусков процессов=%d, финальные гены: timeout=%dms retries=%d%n",
                tot.n(), 100.0 * tot.ok() / Math.max(1, tot.n()), pool.members.stream().mapToInt(m -> m.restarts.get()).sum(), pool.timeoutMs, pool.retries);
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static Window merge(List<double[]> a) {
        int n = 0, ok = 0; double p99 = 0;
        for (var x : a) { n += (int) x[0]; ok += (int) x[1]; p99 = Math.max(p99, x[2]); }
        return new Window(n, ok, 0, p99);
    }
    private static Window drain(Queue<double[]> q) {
        var ms = new ArrayList<Double>(); int n = 0, ok = 0; double[] x;
        while ((x = q.poll()) != null) { n++; if (x[0] > 0) ok++; ms.add(x[1]); }
        Collections.sort(ms);
        return new Window(n, ok, ms.isEmpty() ? 0 : ms.get(ms.size() / 2), ms.isEmpty() ? 0 : ms.get((int) Math.min(ms.size() - 1, ms.size() * 0.99)));
    }
}
