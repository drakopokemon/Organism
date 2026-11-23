package dev.organism.control;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Столп 2: Огибающая (Envelope) для Edge — человек один раз задаёт границы, система мутирует внутри них сама.
 * Вне огибающей -> NEEDS_HUMAN (границы меняет только человек). Запрещённый параметр -> REJECT.
 * Пустая огибающая = всё NEEDS_HUMAN: дефолтное поведение HumanApprovalGate НЕ меняется.
 * Impact = StepSize*0.3 + BlastRadius*0.5 + Criticality*0.2 (спека 6.0).
 * Конфиг-строки: param:min:max:region:maxImpact (region "*" = wildcard), "!param" — запрет.
 */
public final class Envelope {
    public enum Decision { AUTO_APPROVE, NEEDS_HUMAN, REJECT }
    public record Bound(String param, double min, double max, String region, double maxImpact) {}

    private final List<Bound> bounds;
    private final Set<String> forbidden;

    public Envelope(List<Bound> bounds, Set<String> forbidden) {
        this.bounds = List.copyOf(bounds);
        this.forbidden = Set.copyOf(forbidden);
    }

    public static double impact(double stepSize, double blastRadius, double criticality) {
        double r = 0.3 * stepSize + 0.5 * blastRadius + 0.2 * criticality;
        return Math.max(0, Math.min(1, r));
    }

    public Decision evaluate(String region, Map<String, Double> oldParams, Map<String, Double> newParams, double impact) {
        for (var e : newParams.entrySet()) {
            String k = e.getKey();
            double nv = e.getValue();
            Double ov = oldParams == null ? null : oldParams.get(k);
            if (ov != null && ov.doubleValue() == nv) continue;
            if (forbidden.contains(k)) return Decision.REJECT;
            Bound b = find(k, region);
            if (b == null) return Decision.NEEDS_HUMAN;
            if (nv < b.min() || nv > b.max()) return Decision.NEEDS_HUMAN;
            if (impact > b.maxImpact()) return Decision.NEEDS_HUMAN;
        }
        return Decision.AUTO_APPROVE;
    }

    private Bound find(String param, String region) {
        Bound wildcard = null;
        for (Bound b : bounds) {
            if (!b.param().equals(param)) continue;
            if (b.region().equals(region)) return b;
            if (b.region().equals("*") && wildcard == null) wildcard = b;
        }
        return wildcard;
    }

    public static Envelope fromLines(List<String> lines) {
        List<Bound> bs = new ArrayList<>();
        Set<String> forb = new HashSet<>();
        for (String raw : lines) {
            String l = raw.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (l.startsWith("!")) { forb.add(l.substring(1).trim()); continue; }
            String[] p = l.split(":");
            if (p.length != 5) throw new IllegalArgumentException("bad envelope line: " + l);
            bs.add(new Bound(p[0].trim(), Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim()),
                    p[3].trim(), Double.parseDouble(p[4].trim())));
        }
        return new Envelope(bs, forb);
    }

    public List<Bound> bounds() { return bounds; }
    public Set<String> forbidden() { return forbidden; }
}
