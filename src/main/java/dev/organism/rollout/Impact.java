package dev.organism.rollout;

import dev.organism.genome.Genome;
import dev.organism.obs.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 6.2: Impact = StepSize*0.3 + BlastRadius*0.5 + Criticality*0.2 (все компоненты в [0,1]).
 * StepSize  -- |delta| / диапазон гена из MutationPolicy (не относительная дельта: шаг 16->24 при 8..48 = 0.2).
 * BlastRadius -- доля узлов флота, которых заденет раскатка.
 * Criticality -- важность гена; Genome ЗАМОРОЖЕН, поэтому берётся из opt-in side-car политики (criticality.cfg).
 * Impact > 0.5 -> four-eyes (две подписи разных людей), иначе одиночный ADMIN.
 */
public record Impact(double stepSize, double blastRadius, double criticality, double value) {

    public static final double W_STEP = 0.3, W_BLAST = 0.5, W_CRIT = 0.2;
    public static final double FOUR_EYES = 0.5;

    public Impact {
        stepSize = clamp(stepSize);
        blastRadius = clamp(blastRadius);
        criticality = clamp(criticality);
        value = clamp(W_STEP * stepSize + W_BLAST * blastRadius + W_CRIT * criticality);
    }

    public static Impact of(double step, double blast, double crit) { return new Impact(step, blast, crit, 0); }

    public boolean fourEyes() { return value > FOUR_EYES; }

    public String json() {
        return "{\"step\":" + Json.num(stepSize) + ",\"blast\":" + Json.num(blastRadius)
                + ",\"crit\":" + Json.num(criticality) + ",\"impact\":" + Json.num(value)
                + ",\"fourEyes\":" + fourEyes() + "}";
    }

    public static double clamp(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    public static double range(String gene, Genome.MutationPolicy p) {
        if (p == null) return 0;
        return switch (gene) {
            case "poolSize", "pool" -> p.poolSizeMax() - p.poolSizeMin();
            case "queueLimit" -> p.queueLimitMax() - p.queueLimitMin();
            case "cacheMb" -> p.cacheMbMax() - p.cacheMbMin();
            case "timeoutMs" -> p.timeoutMsMax() - p.timeoutMsMin();
            default -> 0;
        };
    }

    public static double stepSize(Map<String, Double> old, Map<String, Double> neu, Genome.MutationPolicy p) {
        double s = 0;
        for (var e : neu.entrySet()) {
            double n = e.getValue();
            double o = old == null ? n : old.getOrDefault(e.getKey(), n);
            double r = range(e.getKey(), p);
            double d = Math.abs(n - o);
            s = Math.max(s, r > 1e-9 ? d / r : d / Math.max(Math.abs(o), 1e-9));
        }
        return clamp(s);
    }

    public static Impact compute(Map<String, Double> old, Map<String, Double> neu,
                                 Genome.MutationPolicy policy, Criticality crit,
                                 int affectedNodes, int totalNodes) {
        Map<String, Double> changed = new LinkedHashMap<>();
        for (var e : neu.entrySet()) {
            double o = old == null ? e.getValue() : old.getOrDefault(e.getKey(), e.getValue());
            if (Math.abs(o - e.getValue()) > 1e-9) changed.put(e.getKey(), e.getValue());
        }
        double blast = totalNodes <= 0 ? 1.0 : affectedNodes / (double) totalNodes;
        Criticality c = crit == null ? Criticality.defaults() : crit;
        return of(stepSize(old, neu, policy), blast, c.of(changed.isEmpty() ? neu : changed));
    }

    /** Opt-in критичность генов: строки "gene:0.8", "*:0.5" (fallback), "#" -- комментарий. Fail-closed на мусоре. */
    public static final class Criticality {
        private final Map<String, Double> map;
        private final double fallback;

        public Criticality(Map<String, Double> map, double fallback) {
            this.map = Map.copyOf(map);
            this.fallback = clamp(fallback);
        }

        public static Criticality defaults() {
            var m = new TreeMap<String, Double>();
            m.put("poolSize", 0.8);
            m.put("pool", 0.8);
            m.put("queueLimit", 0.4);
            m.put("cacheMb", 0.5);
            m.put("timeoutMs", 0.6);
            return new Criticality(m, 0.5);
        }

        public static Criticality fromFile(Path f) throws IOException {
            var m = new TreeMap<String, Double>();
            double fb = 0.5;
            for (String raw : Files.readAllLines(f)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int c = line.indexOf(':');
                if (c <= 0 || c == line.length() - 1) throw new IOException("criticality: bad line '" + line + "'");
                String k = line.substring(0, c).trim();
                double v;
                try { v = Double.parseDouble(line.substring(c + 1).trim()); }
                catch (NumberFormatException e) { throw new IOException("criticality: bad number in '" + line + "'"); }
                if (v < 0 || v > 1) throw new IOException("criticality: out of [0,1] in '" + line + "'");
                if (k.equals("*")) fb = v; else m.put(k, v);
            }
            return new Criticality(m, fb);
        }

        public double of(Map<String, Double> genes) {
            double c = 0;
            boolean any = false;
            for (String k : genes.keySet()) { any = true; c = Math.max(c, map.getOrDefault(k, fallback)); }
            return any ? c : fallback;
        }
    }
}
