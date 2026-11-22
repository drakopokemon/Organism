package dev.organism;

import java.nio.file.Path;
import java.util.*;

/**
 * ORGANISM — самоэволюционирующая среда выполнения.
 *   (без флагов)   живая система + дашборд
 *   --ab           A/B: статика vs ORGANISM    --selftest   проверка инвариантов
 *   --symbiont     демо симбиоза с внешними процессами
 *   --ticks N --seed S --port P --tick-ms MS --no-ui --no-open --fast --manual --no-chaos
 */
public final class Main {
    static final String R = "\u001b[0m", G = "\u001b[32m", Y = "\u001b[33m", C = "\u001b[36m", M = "\u001b[35m", X = "\u001b[31m", D = "\u001b[90m";

    static final String USAGE = """
      ORGANISM
        (без флагов)        живая система + дашборд на http://localhost:8080 (бесконечно)
        --manual            HIGH-risk мутации ждут Approve/Deny в дашборде
        --ab [--ticks N]    A/B: тот же мир и катастрофы, статика vs ORGANISM
        --selftest          проверка инвариантов, детерминизма и самовосстановления
        --symbiont          демо: внешние процессы под управлением Symbiont Wrapper
          --symbiont-cmd "python3 svc.py {port}"   свой внешний сервис (/health + /work на {port})
          --symbiont-seconds N
        --ticks N  --seed S  --port P  --tick-ms MS  --no-ui  --no-open  --fast  --no-chaos
      """;

    public static void main(String[] args) throws Exception {
        int ticks = -1, port = 8080, tickMs = 100, symSeconds = 40; long seed = 42;
        boolean ui = true, fast = false, ab = false, manual = false, chaos = true, open = true, selftest = false, symbiont = false;
        String symCmd = null;
        for (int i = 0; i < args.length; i++) switch (args[i]) {
            case "--ticks" -> ticks = Integer.parseInt(args[++i]);
            case "--seed" -> seed = Long.parseLong(args[++i]);
            case "--port" -> port = Integer.parseInt(args[++i]);
            case "--tick-ms" -> tickMs = Integer.parseInt(args[++i]);
            case "--no-ui" -> ui = false;
            case "--no-open" -> open = false;
            case "--fast" -> fast = true;
            case "--ab" -> ab = true;
            case "--manual" -> manual = true;
            case "--no-chaos" -> chaos = false;
            case "--selftest" -> selftest = true;
            case "--symbiont" -> symbiont = true;
            case "--symbiont-cmd" -> symCmd = args[++i];
            case "--symbiont-seconds" -> symSeconds = Integer.parseInt(args[++i]);
            case "-h", "--help" -> { System.out.println(USAGE); return; }
            default -> { System.err.println("unknown arg " + args[i] + " (см. --help)"); System.exit(2); }
        }
        if (selftest) { System.exit(SelfTest.run() ? 0 : 1); }
        if (symbiont) {
            Symbiosis.run(symCmd == null ? Symbiont.defaultCommand() : Arrays.asList(symCmd.split("\\s+")), symSeconds);
            return;
        }
        if (ticks < 0) ticks = ab ? 700 : ui ? Integer.MAX_VALUE : 900;   // с дашбордом живём, пока не нажмёте Ctrl+C
        if (ab) { abTest(ticks, seed); return; }

        Gate gate = manual ? new Gate.Manual() : new Gate.Policy();
        try (var org = new Organism(Genomes.shopApi(), seed, gate, true, chaos, Path.of("organism-journal.jsonl"));
             var dash = ui ? new Dashboard(port, gate instanceof Gate.Manual m ? m : null) : null) {
            System.out.println(C + "\n  ORGANISM" + R + D + "  genome=" + org.genome.name() + " seed=" + seed + " java=" + Runtime.version() + R);
            if (dash != null) {
                String url = "http://localhost:" + port;
                System.out.println("  dashboard → " + G + url + R + (manual ? Y + "  (manual approvals ON)" + R : "") + D + "   metrics → " + url + "/metrics" + R);
                if (open) openBrowser(url);
            }
            System.out.println(D + "  journal   → organism-journal.jsonl   (Ctrl+C — выход)\n" + R);
            var spark = new ArrayDeque<Double>();
            for (int t = 0; t < ticks; t++) {
                var snap = org.tick(t);
                if (dash != null) dash.publish(Json.of(snap), Dashboard.promText(snap));
                spark.addLast((Double) snap.get("p99")); if (spark.size() > 30) spark.removeFirst();
                for (var e : org.lastEvents) log(e);
                if (t % 15 == 0) status(snap, spark, org.genome.p99Target());
                if (!fast) Thread.sleep(tickMs);
            }
            report(org, ticks);
            if (dash != null && !fast) { System.out.println(D + "\n  симуляция окончена, дашборд жив. Ctrl+C для выхода." + R); Thread.currentThread().join(); }
        }
    }

    static void openBrowser(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String[] cmd = os.contains("win") ? new String[]{"rundll32", "url.dll,FileProtocolHandler", url}
                         : os.contains("mac") ? new String[]{"open", url} : new String[]{"xdg-open", url};
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (Exception ignored) { /* нет GUI/браузера — не страшно, URL выше */ }
    }

    static void status(Map<String, Object> s, Collection<Double> spark, double target) {
        var sb = new StringBuilder();
        for (double v : spark) sb.append("▁▂▃▄▅▆▇█".charAt((int) Math.min(7, Math.max(0, v / (2 * target) * 7))));
        @SuppressWarnings("unchecked") var d = (Map<String, Object>) s.get("data");
        String data = d == null ? "" : " data=%s/%s".formatted(d.get("healthy"), d.get("total"));
        System.out.printf("%st=%-4s%s rps=%-5.0f cells=%-3s p99=%-6.0f avail=%5.1f%% $/h=%.2f fit=%.3f%s %s%s%n",
            D, s.get("t"), R, (Double) s.get("rps"), s.get("replicas"), (Double) s.get("p99"), 100 * (Double) s.get("avail"),
            (Double) s.get("cost"), (Double) s.get("fit"), data, C, sb + R);
    }

    static void log(Event e) {
        String col = switch (e.type()) {
            case "MUTATION_ACCEPTED" -> G; case "MUTATION_REJECTED", "MUTATION_BLOCKED" -> D;
            case "ABORT", "QUARANTINE", "CHAOS_NODE_DOWN", "CHAOS_POISON", "DATA_LOST", "WRITES_LOST" -> X;
            case "GERMINATE", "REPLACED", "RELEASE", "NODE_REVIVED", "REPLICATE" -> M;
            case "PROPOSAL", "PROPOSAL_APPROVED", "PROPOSAL_DENIED" -> Y;
            default -> null;
        };
        if (col == null || e.type().equals("MUTATION_REJECTED")) return;
        System.out.printf("%s  t=%-4d %-18s %-8s %s%s%n", col, e.tick(), e.type(), e.subject(), fmt(e.data()), R);
    }
    static String fmt(Map<String, Object> d) {
        var sb = new StringBuilder();
        d.forEach((k, v) -> {
            if (v instanceof Map<?, ?> m) m.forEach((gk, gv) -> { double[] a = (double[]) gv; sb.append("%s %.0f→%.0f ".formatted(gk, a[0], a[1])); });
            else if (v instanceof Double x) sb.append("%s=%.3f ".formatted(k, x)); else sb.append(k).append('=').append(v).append(' ');
        });
        return sb.toString().trim();
    }

    static void report(Organism o, int ticks) {
        System.out.println("\n" + C + "  ── итог ──" + R);
        System.out.println("  события: " + o.counts);
        if (o.champion != null) System.out.println("  чемпион (последний принятый геном): " + G + o.champion + R);
    }

    /** A/B: тот же мир, те же катастрофы — со включённой и выключенной эволюцией. */
    static void abTest(int ticks, long seed) throws Exception {
        double[][] r = new double[2][]; Organism[] os = new Organism[2];
        for (int k = 0; k < 2; k++) {
            os[k] = new Organism(Genomes.shopApi(), seed, new Gate.Policy(), k == 1, true, k == 1 ? Path.of("organism-journal.jsonl") : null);
            for (int t = 0; t < ticks; t++) os[k].tick(t);
            r[k] = os[k].summary(100, ticks);
        }
        System.out.printf("%n%s  A/B (%d тиков, seed=%d, окно 100..%d)%s%n", C, ticks, seed, ticks, R);
        System.out.printf("  %-26s %12s %12s%n", "", "статика", "ORGANISM");
        String[] names = {"fitness (выше лучше)", "тиков с p99 > SLA, %", "стоимость, $/ч", "$ за 1M запросов", "доступность, %"};
        for (int i = 0; i < 5; i++) System.out.printf("  %-26s %12.3f %12.3f%n", names[i], r[0][i], r[1][i]);
        if (os[0].store != null) {
            var a = os[0].store.stats(); var b = os[1].store.stats();
            System.out.printf("  %-26s %12d %12d%n", "шардов потеряно", a.lost(), b.lost());
            System.out.printf("  %-26s %12d %12d%n", "записей потеряно", a.writesLost(), b.writesLost());
            System.out.printf("  %-26s %12d %12d%n", "реплик восстановлено", a.repaired(), b.repaired());
            System.out.println(D + "  (самовосстановление данных — часть иммунной системы и работает в обоих режимах)" + R);
        }
        System.out.println("  события ORGANISM: " + os[1].counts);
        System.out.println("  чемпион: " + G + os[1].champion + R);
        os[1].close(); os[0].close();
    }
}
