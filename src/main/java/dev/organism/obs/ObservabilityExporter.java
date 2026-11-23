package dev.organism.obs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Реестр метрик: gauge (volatile double) + counter (DoubleAdder), ConcurrentHashMap.
 * Скрейп ИДЕМПОТЕНТНЫЙ: counter.sum(), НЕ sumThenReset. Prometheus text 0.0.4, escape labels.
 * Опциональный OTLP-JSON push через java.net.http.HttpClient — без внешних либ.
 */
public final class ObservabilityExporter {
    private static final class Series {
        final String name;
        final TreeMap<String, String> labels;
        final boolean counter;
        final DoubleAdder adder = new DoubleAdder();
        volatile double gauge;
        Series(String name, TreeMap<String, String> labels, boolean counter) {
            this.name = name; this.labels = labels; this.counter = counter;
        }
        double value() { return counter ? adder.sum() : gauge; }
    }

    private final ConcurrentHashMap<String, Series> reg = new ConcurrentHashMap<>();

    private static void checkName(String name) {
        if (name == null || !name.matches("[a-zA-Z_:][a-zA-Z0-9_:]*"))
            throw new IllegalArgumentException("bad metric name: " + name);
    }

    private static TreeMap<String, String> labels(String[] lp) {
        if (lp.length % 2 != 0) throw new IllegalArgumentException("labels must be key/value pairs");
        var m = new TreeMap<String, String>();
        for (int i = 0; i < lp.length; i += 2) {
            if (lp[i] == null || !lp[i].matches("[a-zA-Z_][a-zA-Z0-9_]*"))
                throw new IllegalArgumentException("bad label name: " + lp[i]);
            m.put(lp[i], lp[i + 1] == null ? "" : lp[i + 1]);
        }
        return m;
    }

    private static String key(String name, TreeMap<String, String> labels) {
        var sb = new StringBuilder(name);
        sb.append('{');
        for (var e : labels.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append(',');
        return sb.append('}').toString();
    }

    public void gauge(String name, double value, String... labelPairs) {
        checkName(name);
        var l = labels(labelPairs);
        reg.computeIfAbsent(key(name, l), k -> new Series(name, l, false)).gauge = value;
    }

    public void inc(String name, double delta, String... labelPairs) {
        checkName(name);
        var l = labels(labelPairs);
        reg.computeIfAbsent(key(name, l), k -> new Series(name, l, true)).adder.add(delta);
    }

    public double value(String name, String... labelPairs) {
        Series s = reg.get(key(name, labels(labelPairs)));
        return s == null ? 0 : s.value();
    }

    private static String escLabel(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String fmt(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (v == Double.POSITIVE_INFINITY) return "+Inf";
        if (v == Double.NEGATIVE_INFINITY) return "-Inf";
        if (v == Math.floor(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return Double.toString(v);
    }

    public String scrape() {
        var byName = new TreeMap<String, List<Series>>();
        for (var e : new TreeMap<>(reg).entrySet())
            byName.computeIfAbsent(e.getValue().name, k -> new ArrayList<>()).add(e.getValue());
        var sb = new StringBuilder();
        for (var e : byName.entrySet()) {
            sb.append("# TYPE ").append(e.getKey()).append(e.getValue().get(0).counter ? " counter" : " gauge").append('\n');
            for (Series s : e.getValue()) {
                sb.append(s.name);
                if (!s.labels.isEmpty()) {
                    sb.append('{');
                    boolean first = true;
                    for (var l : s.labels.entrySet()) {
                        if (!first) sb.append(',');
                        first = false;
                        sb.append(l.getKey()).append("=\"").append(escLabel(l.getValue())).append('"');
                    }
                    sb.append('}');
                }
                sb.append(' ').append(fmt(s.value())).append('\n');
            }
        }
        return sb.toString();
    }

    public String otlpJson(String serviceName) {
        long nowNano = System.currentTimeMillis() * 1_000_000L;
        var sb = new StringBuilder();
        sb.append("{\"resourceMetrics\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":")
          .append(Json.str(serviceName)).append("}}]},\"scopeMetrics\":[{\"scope\":{\"name\":\"organism\"},\"metrics\":[");
        boolean first = true;
        for (var e : new TreeMap<>(reg).entrySet()) {
            Series s = e.getValue();
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":").append(Json.str(s.name));
            sb.append(s.counter ? ",\"sum\":{\"isMonotonic\":true,\"aggregationTemporality\":2,\"dataPoints\":["
                                : ",\"gauge\":{\"dataPoints\":[");
            sb.append("{\"asDouble\":").append(Json.num(s.value()))
              .append(",\"timeUnixNano\":\"").append(nowNano).append("\"");
            if (!s.labels.isEmpty()) {
                sb.append(",\"attributes\":[");
                boolean f2 = true;
                for (var l : s.labels.entrySet()) {
                    if (!f2) sb.append(',');
                    f2 = false;
                    sb.append("{\"key\":").append(Json.str(l.getKey()))
                      .append(",\"value\":{\"stringValue\":").append(Json.str(l.getValue())).append("}}");
                }
                sb.append(']');
            }
            sb.append("]}}");
        }
        sb.append("]}]}]}");
        return sb.toString();
    }

    public AutoCloseable startOtlpPush(String endpoint, long intervalMs, String serviceName) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        Thread t = Thread.ofVirtual().name("otlp-push").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(Math.max(1000, intervalMs));
                    HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(otlpJson(serviceName)))
                            .build();
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() / 100 == 2) inc("organism_otlp_push_total", 1);
                    else inc("organism_otlp_push_failures_total", 1);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    inc("organism_otlp_push_failures_total", 1);
                }
            }
        });
        return t::interrupt;
    }
}
