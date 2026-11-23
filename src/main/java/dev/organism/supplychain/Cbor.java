package dev.organism.supplychain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Канонический CBOR (RFC 7049 subset), только JDK.
 * Типы: uint/negint (до 2^63-1), bstr, tstr, array, map, bool, null, float64/float32.
 * Map-ключи сортируются по encoded bytes (length-first, затем unsigned lex) — детерминированный артефакт.
 * Indefinite lengths, теги, uint64 overflow — ЗАПРЕЩЕНЫ (fail-closed).
 * Потребители: genome-артефакты (Ed25519), Raft RPC, COSE-ключи WebAuthn, TSA-токены.
 */
public final class Cbor {
    private Cbor() {}

    public static byte[] encode(Object o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, o);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, Object o) {
        if (o == null) { out.write(0xf6); return; }
        if (o instanceof Boolean b) { out.write(b ? 0xf5 : 0xf4); return; }
        if (o instanceof Integer i) { writeInt(out, i.longValue()); return; }
        if (o instanceof Long l) { writeInt(out, l); return; }
        if (o instanceof Double d) {
            out.write(0xfb);
            long bits = Double.doubleToRawLongBits(d);
            for (int s = 56; s >= 0; s -= 8) out.write((int) ((bits >>> s) & 0xff));
            return;
        }
        if (o instanceof byte[] bs) { head(out, 2, bs.length); out.write(bs, 0, bs.length); return; }
        if (o instanceof String s) {
            byte[] u = s.getBytes(StandardCharsets.UTF_8);
            head(out, 3, u.length); out.write(u, 0, u.length); return;
        }
        if (o instanceof List<?> l) {
            head(out, 4, l.size());
            for (Object e : l) write(out, e);
            return;
        }
        if (o instanceof Map<?, ?> m) {
            List<Object[]> es = new ArrayList<>();
            for (Map.Entry<?, ?> e : m.entrySet()) es.add(new Object[]{encode(e.getKey()), e.getValue()});
            es.sort((a, b) -> cmp((byte[]) a[0], (byte[]) b[0]));
            head(out, 5, es.size());
            byte[] prev = null;
            for (Object[] e : es) {
                byte[] k = (byte[]) e[0];
                if (prev != null && cmp(prev, k) == 0) throw new IllegalArgumentException("cbor: duplicate key");
                out.write(k, 0, k.length);
                write(out, e[1]);
                prev = k;
            }
            return;
        }
        throw new IllegalArgumentException("cbor: unsupported type " + o.getClass().getName());
    }

    private static int cmp(byte[] a, byte[] b) {
        if (a.length != b.length) return Integer.compare(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            int x = a[i] & 0xff, y = b[i] & 0xff;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static void writeInt(ByteArrayOutputStream out, long v) {
        if (v >= 0) head(out, 0, v);
        else {
            long n = -1 - v;
            if (n < 0) throw new IllegalArgumentException("cbor: int64 overflow");
            head(out, 1, n);
        }
    }

    private static void head(ByteArrayOutputStream out, int major, long val) {
        if (val < 0) throw new IllegalArgumentException("cbor: negative length");
        int mb = major << 5;
        if (val < 24) out.write(mb | (int) val);
        else if (val <= 0xff) { out.write(mb | 24); out.write((int) val); }
        else if (val <= 0xffff) { out.write(mb | 25); out.write((int) (val >>> 8)); out.write((int) val & 0xff); }
        else if (val <= 0xffffffffL) {
            out.write(mb | 26);
            for (int s = 24; s >= 0; s -= 8) out.write((int) ((val >>> s) & 0xff));
        } else {
            out.write(mb | 27);
            for (int s = 56; s >= 0; s -= 8) out.write((int) ((val >>> s) & 0xff));
        }
    }

    public static Object decode(byte[] b) {
        int[] p = {0};
        Object o = read(b, p);
        if (p[0] != b.length) throw new IllegalArgumentException("cbor: trailing bytes");
        return o;
    }

    private static Object read(byte[] b, int[] p) {
        int ib = u8(b, p);
        int major = ib >>> 5, ai = ib & 31;
        if (ai == 31) throw new IllegalArgumentException("cbor: indefinite length forbidden");
        if (major == 7) {
            if (ai == 20) return Boolean.FALSE;
            if (ai == 21) return Boolean.TRUE;
            if (ai == 22) return null;
            if (ai == 26) return (double) Float.intBitsToFloat((int) raw(b, p, 4));
            if (ai == 27) return Double.longBitsToDouble(raw(b, p, 8));
            throw new IllegalArgumentException("cbor: bad simple value " + ai);
        }
        if (major == 6) throw new IllegalArgumentException("cbor: tags forbidden");
        long val = arg(b, p, ai);
        if (val < 0) throw new IllegalArgumentException("cbor: uint64 overflow unsupported");
        switch (major) {
            case 0: return val;
            case 1: return -1 - val;
            case 2: { byte[] r = new byte[(int) val]; take(b, p, r); return r; }
            case 3: { byte[] r = new byte[(int) val]; take(b, p, r); return new String(r, StandardCharsets.UTF_8); }
            case 4: {
                if (val > 1_000_000) throw new IllegalArgumentException("cbor: array too big");
                List<Object> l = new ArrayList<>();
                for (long i = 0; i < val; i++) l.add(read(b, p));
                return l;
            }
            case 5: {
                if (val > 1_000_000) throw new IllegalArgumentException("cbor: map too big");
                Map<Object, Object> m = new LinkedHashMap<>();
                for (long i = 0; i < val; i++) {
                    Object k = read(b, p);
                    Object v = read(b, p);
                    if (m.put(k, v) != null) throw new IllegalArgumentException("cbor: duplicate key");
                }
                return m;
            }
            default: throw new IllegalArgumentException("cbor: bad major " + major);
        }
    }

    private static long arg(byte[] b, int[] p, int ai) {
        if (ai < 24) return ai;
        if (ai == 24) return u8(b, p);
        if (ai == 25) return raw(b, p, 2);
        if (ai == 26) return raw(b, p, 4);
        if (ai == 27) return raw(b, p, 8);
        throw new IllegalArgumentException("cbor: reserved ai " + ai);
    }

    private static int u8(byte[] b, int[] p) {
        if (p[0] >= b.length) throw new IllegalArgumentException("cbor: eof");
        return b[p[0]++] & 0xff;
    }

    private static long raw(byte[] b, int[] p, int n) {
        if (p[0] + n > b.length) throw new IllegalArgumentException("cbor: eof");
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (b[p[0]++] & 0xffL);
        return v;
    }

    private static void take(byte[] b, int[] p, byte[] r) {
        if (p[0] + r.length > b.length) throw new IllegalArgumentException("cbor: eof");
        System.arraycopy(b, p[0], r, 0, r.length);
        p[0] += r.length;
    }

    @SuppressWarnings("unchecked")
    public static Map<Object, Object> asMap(Object o) {
        if (o instanceof Map) return (Map<Object, Object>) o;
        throw new IllegalArgumentException("cbor: expected map, got " + (o == null ? "null" : o.getClass().getName()));
    }

    public static Object get(Map<?, ?> m, Object key) {
        Object v = m.get(key);
        if (v == null && !m.containsKey(key)) throw new IllegalArgumentException("cbor: missing key " + key);
        return v;
    }

    public static long lng(Map<?, ?> m, Object k) {
        Object v = get(m, k);
        if (v instanceof Number n) return n.longValue();
        throw new IllegalArgumentException("cbor: key " + k + " not a number");
    }

    public static double dbl(Map<?, ?> m, Object k) {
        Object v = get(m, k);
        if (v instanceof Number n) return n.doubleValue();
        throw new IllegalArgumentException("cbor: key " + k + " not a number");
    }

    public static String str(Map<?, ?> m, Object k) {
        Object v = get(m, k);
        if (v instanceof String s) return s;
        throw new IllegalArgumentException("cbor: key " + k + " not a string");
    }

    public static byte[] bytes(Map<?, ?> m, Object k) {
        Object v = get(m, k);
        if (v instanceof byte[] b) return b;
        throw new IllegalArgumentException("cbor: key " + k + " not bytes");
    }

    public static boolean bool(Map<?, ?> m, Object k) {
        Object v = get(m, k);
        if (v instanceof Boolean b) return b;
        throw new IllegalArgumentException("cbor: key " + k + " not a bool");
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<?, ?> m, Object k) {
        Object v = get(m, k);
        if (v instanceof List) return (List<Object>) v;
        throw new IllegalArgumentException("cbor: key " + k + " not a list");
    }
}
