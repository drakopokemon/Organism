package dev.organism.cell;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Пуассоновский loadgen: взвешенный роутинг по клеткам всех кернелов, клиентский p99.
 * java -cp out dev.organism.cell.LoadGen --seeds 127.0.0.1:8101,127.0.0.1:8102 --rps 200 --seconds 30
 *
 * Выход: JSON {"ok":N,"err":N,"errRate":R,"p50":P,"p99":P,"target":P}
 * Exit code 0 если errRate<0.15 и p99<target, иначе 1.
 */
public final class LoadGen {
    record Ref(String url, int weight) {}

    public static void main(String[] a) throws Exception {
        var m = parse(a);
        String[] seeds = m.getOrDefault("seeds", "127.0.0.1:8101").split(",");
        double rps = Double.parseDouble(m.getOrDefault("rps", "100"));
        int seconds = Integer.parseInt(m.getOrDefault("seconds", "30"));
        long seed = Long.parseLong(m.getOrDefault("seed", "42"));
        double target = Double.parseDouble(m.getOrDefault("target-p99", "600"));
        double spikeAt = seconds * Double.parseDouble(m.getOrDefault("spike-at", "0.4"));
        double spikeX = Double.parseDouble(m.getOrDefault("spike-x", "3"));
        var rnd = new SplittableRandom(seed);
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var lats = new ConcurrentLinkedQueue<Long>();
        AtomicLong ok = new AtomicLong(), err = new AtomicLong();
        var cellsRef = new AtomicReference<List<Ref>>(List.of());

        Thread.ofVirtual().start(() -> {
            while (true) {
                var list = new ArrayList<Ref>();
                for (var s : seeds) {
                    try {
                        var req = HttpRequest.newBuilder(URI.create("http://" + s + "/cells"))
                                .timeout(Duration.ofSeconds(2)).build();
                        for (var line : http.send(req, HttpResponse.BodyHandlers.ofString()).body().split("\n")) {
                            var p = line.split("\t");
                            if (p.length == 3 && Integer.parseInt(p[2]) > 0) {
                                String host = s.substring(0, s.indexOf(':'));
                                list.add(new Ref("http://" + host + ":" + p[1] + "/work", Integer.parseInt(p[2])));
                            }
                        }
                    } catch (Exception ignored) {}
                }
                if (!list.isEmpty()) cellsRef.set(list);
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        });
        Thread.sleep(1500);

        long t0 = System.currentTimeMillis();
        for (int step = 0; step < seconds * 10; step++) {
            double el = (System.currentTimeMillis() - t0) / 1000.0;
            double lambda = rps * (el >= spikeAt && el < spikeAt + 8 ? spikeX : 1) / 10.0;
            int n = (int) lambda + (rnd.nextDouble() < lambda % 1 ? 1 : 0);
            for (int i = 0; i < n; i++) {
                var list = cellsRef.get();
                if (list.isEmpty()) continue;
                int total = 0; for (var r : list) total += r.weight();
                int pick = rnd.nextInt(total), acc = 0;
                Ref target0 = list.get(list.size() - 1);
                for (var r : list) { acc += r.weight(); if (pick < acc) { target0 = r; break; } }
                var fin = target0;
                Thread.ofVirtual().start(() -> {
                    long s = System.nanoTime();
                    try {
                        var req = HttpRequest.newBuilder(URI.create(fin.url()))
                                .timeout(Duration.ofSeconds(3)).build();
                        int code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                        long ms = (System.nanoTime() - s) / 1_000_000;
                        lats.add(ms);
                        if (code == 200) ok.incrementAndGet(); else err.incrementAndGet();
                    } catch (Exception e) {
                        var list2 = cellsRef.get();
                        if (list2.size() > 1) {
                            try {
                                var alt = list2.get(ThreadLocalRandom.current().nextInt(list2.size()));
                                var req = HttpRequest.newBuilder(URI.create(alt.url()))
                                        .timeout(Duration.ofSeconds(3)).build();
                                int code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
                                long ms = (System.nanoTime() - s) / 1_000_000;
                                lats.add(ms);
                                if (code == 200) { ok.incrementAndGet(); return; }
                            } catch (Exception e2) { /* fallthrough */ }
                        }
                        err.incrementAndGet();
                    }
                });
            }
            Thread.sleep(100);
        }
        Thread.sleep(3500);
        var arr = new ArrayList<Long>(lats);
        Collections.sort(arr);
        double p99 = arr.isEmpty() ? -1 : arr.get((int) ((arr.size() - 1) * 0.99));
        double p50 = arr.isEmpty() ? -1 : arr.get((int) ((arr.size() - 1) * 0.50));
        double errRate = (ok.get() + err.get()) == 0 ? 1 : err.get() / (double) (ok.get() + err.get());
        System.out.println("{\"ok\":" + ok.get() + ",\"err\":" + err.get()
                + ",\"errRate\":" + String.format(Locale.ROOT, "%.4f", errRate)
                + ",\"p50\":" + p50 + ",\"p99\":" + p99 + ",\"target\":" + target + "}");
        System.exit(errRate < 0.15 && p99 < target && p99 >= 0 ? 0 : 1);
    }

    private static Map<String, String> parse(String[] args) {
        var m = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++)
            if (args[i].startsWith("--") && i + 1 < args.length) m.put(args[i].substring(2), args[++i]);
        return m;
    }
}
