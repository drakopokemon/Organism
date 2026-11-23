package dev.organism.genome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Строгий парсер подмножества YAML для genome.yaml. Без внешних либ, fail-closed.
 * Секции: objectives / constraints / capabilities (списки "- key: value"),
 * mutation_policy (map "key: min,max,step"). Невалидный конфиг -> IllegalArgumentException.
 */
public final class GenomeParser {
    private GenomeParser() {}

    public static Genome parse(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        var objectives = new ArrayList<Genome.Objective>();
        var constraints = new ArrayList<Genome.Constraint>();
        var capabilities = new ArrayList<Genome.Capability>();
        double[] pool = null, queue = null, cache = null, timeout = null;
        String section = null;
        int ln = 0;
        for (String raw : lines) {
            ln++;
            String line = stripComment(raw).stripTrailing();
            if (line.isBlank()) continue;
            String t = line.strip();
            boolean indented = line.startsWith(" ") || line.startsWith("\t");
            if (!indented && t.endsWith(":")) {
                section = t.substring(0, t.length() - 1).strip();
                continue;
            }
            if (t.startsWith("- ")) {
                String item = t.substring(2).strip();
                int c = item.indexOf(':');
                if (c < 0) throw err(ln, "list item without ':'");
                String k = item.substring(0, c).strip();
                String v = item.substring(c + 1).strip();
                if (section == null) throw err(ln, "list item outside section");
                switch (section) {
                    case "objectives" -> objectives.add(objective(k, v, ln));
                    case "constraints" -> constraints.addAll(constraint(k, v, ln));
                    case "capabilities" -> capabilities.add(capability(k, v, ln));
                    default -> throw err(ln, "unknown section: " + section);
                }
            } else if ("mutation_policy".equals(section) && indented) {
                int c = t.indexOf(':');
                if (c < 0) throw err(ln, "mutation_policy entry without ':'");
                String k = t.substring(0, c).strip();
                double[] tri = triple(t.substring(c + 1).strip(), ln);
                switch (k) {
                    case "pool_size" -> pool = tri;
                    case "queue_limit" -> queue = tri;
                    case "cache_mb" -> cache = tri;
                    case "timeout_ms" -> timeout = tri;
                    default -> throw err(ln, "unknown mutation_policy key: " + k);
                }
            } else {
                throw err(ln, "unrecognized line: " + t);
            }
        }
        if (objectives.isEmpty()) throw new IllegalArgumentException("genome: no objectives");
        if (pool == null || queue == null || cache == null || timeout == null)
            throw new IllegalArgumentException("genome: mutation_policy incomplete (need pool_size, queue_limit, cache_mb, timeout_ms)");
        var mp = new Genome.MutationPolicy(
                pool[2], pool[0], pool[1],
                queue[2], queue[0], queue[1],
                cache[2], cache[0], cache[1],
                timeout[2], timeout[0], timeout[1]);
        return new Genome(List.copyOf(objectives), List.copyOf(constraints), List.copyOf(capabilities), mp);
    }

    private static Genome.Objective objective(String k, String v, int ln) {
        return switch (k) {
            case "latency_p99_ms" -> new Genome.LatencyObjective(pos(v, ln));
            case "cost_usd_per_month" -> new Genome.CostObjective(pos(v, ln));
            case "throughput_rps" -> new Genome.ThroughputObjective(pos(v, ln));
            default -> throw err(ln, "unknown objective: " + k);
        };
    }

    private static List<Genome.Constraint> constraint(String k, String v, int ln) {
        switch (k) {
            case "allowed_regions" -> {
                var out = new ArrayList<Genome.Constraint>();
                for (String r : v.split(",")) {
                    String rr = r.strip();
                    if (rr.isEmpty()) throw err(ln, "empty region");
                    out.add(new Genome.RegionConstraint(rr));
                }
                return out;
            }
            case "max_memory_mb_per_agent" -> {
                return List.of(new Genome.ResourceConstraint((int) pos(v, ln)));
            }
            case "forbidden_apis" -> {
                if (v.isEmpty()) throw err(ln, "empty forbidden_apis");
                return List.of(new Genome.ForbiddenApisConstraint(v));
            }
            default -> throw err(ln, "unknown constraint: " + k);
        }
    }

    private static Genome.Capability capability(String k, String v, int ln) {
        switch (k) {
            case "processing" -> {
                int[] p = ints(v, 2, ln);
                if (p[0] < 1 || p[1] < p[0]) throw err(ln, "processing needs 1<=min<=max");
                return new Genome.ProcessingCapability(p[0], p[1]);
            }
            case "storage" -> {
                int[] p = ints(v, 2, ln);
                if (p[0] < 1 || p[1] < 1) throw err(ln, "storage needs shards>=1, replication>=1");
                return new Genome.StorageCapability(p[0], p[1]);
            }
            case "balancing" -> {
                if (v.isEmpty()) throw err(ln, "empty balancing algorithm");
                return new Genome.BalancingCapability(v);
            }
            case "immune" -> {
                if (!v.equals("true") && !v.equals("false")) throw err(ln, "immune must be true|false");
                return new Genome.ImmuneCapability(v.equals("true"));
            }
            default -> throw err(ln, "unknown capability: " + k);
        }
    }

    /** "min,max,step" -> [min,max,step], валидация. */
    private static double[] triple(String v, int ln) {
        String[] p = v.split(",");
        if (p.length != 3) throw err(ln, "expected min,max,step got: " + v);
        double min = num(p[0], ln), max = num(p[1], ln), step = num(p[2], ln);
        if (!(step > 0) || min > max || min < 0) throw err(ln, "bad policy range: " + v);
        return new double[]{min, max, step};
    }

    private static int[] ints(String v, int n, int ln) {
        String[] p = v.split(",");
        if (p.length != n) throw err(ln, "expected " + n + " ints got: " + v);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = (int) num(p[i], ln);
        return out;
    }

    private static double pos(String v, int ln) {
        double d = num(v, ln);
        if (!(d > 0)) throw err(ln, "must be > 0: " + v);
        return d;
    }

    private static double num(String v, int ln) {
        try {
            double d = Double.parseDouble(v.strip());
            if (!Double.isFinite(d)) throw err(ln, "non-finite number: " + v);
            return d;
        } catch (NumberFormatException e) {
            throw err(ln, "not a number: " + v);
        }
    }

    private static String stripComment(String s) {
        int i = s.indexOf('#');
        return i < 0 ? s : s.substring(0, i);
    }

    private static IllegalArgumentException err(int ln, String msg) {
        return new IllegalArgumentException("genome.yaml line " + ln + ": " + msg);
    }
}
