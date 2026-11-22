package dev.organism;

import java.util.*;

/** Минимальный JSON-writer (без зависимостей). */
final class Json {
    private Json() {}
    static String of(Object o) { var sb = new StringBuilder(); w(sb, o); return sb.toString(); }

    private static void w(StringBuilder sb, Object o) {
        switch (o) {
            case null -> sb.append("null");
            case String s -> {
                sb.append('"');
                for (char c : s.toCharArray()) {
                    switch (c) {
                        case '"' -> sb.append("\\\"");
                        case '\\' -> sb.append("\\\\");
                        default -> sb.append(c < 32 ? ' ' : c);
                    }
                }
                sb.append('"');
            }
            case Double d -> sb.append(Double.isFinite(d)
                    ? String.format(Locale.ROOT, "%.4f", d).replaceAll("0+$", "").replaceAll("\\.$", "") : "null");
            case Number n -> sb.append(n);
            case Boolean b -> sb.append(b);
            case Enum<?> e -> w(sb, e.name());
            case double[] a -> { var l = new ArrayList<Double>(); for (double x : a) l.add(x); w(sb, l); }
            case Map<?, ?> m -> {
                sb.append('{'); boolean f = true;
                for (var e : m.entrySet()) {
                    if (!f) sb.append(','); f = false;
                    w(sb, String.valueOf(e.getKey())); sb.append(':'); w(sb, e.getValue());
                }
                sb.append('}');
            }
            case Collection<?> c -> {
                sb.append('['); boolean f = true;
                for (var x : c) { if (!f) sb.append(','); f = false; w(sb, x); }
                sb.append(']');
            }
            default -> w(sb, o.toString());
        }
    }
}
