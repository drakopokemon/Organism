package dev.organism.obs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Минимальный JSON-парсер (только JDK): objects/arrays/strings/numbers/bool/null. Fail-closed на мусоре. */
public final class JsonReader {
    private final String s;
    private int p;

    private JsonReader(String s) { this.s = s; }

    public static Object parse(String s) {
        JsonReader r = new JsonReader(s);
        r.ws();
        Object o = r.value();
        r.ws();
        if (r.p != s.length()) throw new IllegalArgumentException("json: trailing garbage at " + r.p);
        return o;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new IllegalArgumentException("json: expected object");
    }

    public static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof String s) return s;
        throw new IllegalArgumentException("json: missing string " + k);
    }

    public static double dbl(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException("json: missing number " + k);
    }

    public static Map<String, Object> map(Map<String, Object> m, String k) { return obj(m.get(k)); }

    private void ws() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }

    private Object value() {
        char c = peek();
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Object literal(String lit, Object v) {
        if (!s.startsWith(lit, p)) throw new IllegalArgumentException("json: bad literal at " + p);
        p += lit.length();
        return v;
    }

    private Map<String, Object> object() {
        p++;
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (peek() == '}') { p++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("json: expected , or } at " + (p - 1));
        }
    }

    private List<Object> array() {
        p++;
        List<Object> l = new ArrayList<>();
        ws();
        if (peek() == ']') { p++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = next();
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("json: expected , or ] at " + (p - 1));
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') return sb.toString();
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (p + 4 > s.length()) throw new IllegalArgumentException("json: bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                        p += 4;
                    }
                    default -> throw new IllegalArgumentException("json: bad escape");
                }
            } else sb.append(c);
        }
    }

    private Double number() {
        int start = p;
        if (peek() == '-') p++;
        while (p < s.length() && "0123456789+-.eE".indexOf(s.charAt(p)) >= 0) p++;
        if (p == start) throw new IllegalArgumentException("json: bad number at " + p);
        return Double.parseDouble(s.substring(start, p));
    }

    private char peek() {
        if (p >= s.length()) throw new IllegalArgumentException("json: eof");
        return s.charAt(p);
    }

    private char next() { char c = peek(); p++; return c; }

    private void expect(char c) {
        if (next() != c) throw new IllegalArgumentException("json: expected " + c + " at " + (p - 1));
    }
}
