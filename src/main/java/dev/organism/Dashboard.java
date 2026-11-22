package dev.organism;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Живой дашборд: SSE-поток снапшотов + Approve/Deny для human-in-the-loop. Только JDK (jdk.httpserver). */
final class Dashboard implements AutoCloseable {
    private final HttpServer server;
    private final List<BlockingQueue<String>> clients = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedDeque<String> history = new ConcurrentLinkedDeque<>();
    private volatile String metrics = "# no data yet\n";

    Dashboard(int port, Gate.Manual manual) throws IOException {
        byte[] html;
        try (var in = Dashboard.class.getResourceAsStream("/dashboard.html")) { html = in.readAllBytes(); }
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> send(ex, 200, "text/html; charset=utf-8", html));
        server.createContext("/history", ex ->
            send(ex, 200, "application/json", ("[" + String.join(",", history) + "]").getBytes(StandardCharsets.UTF_8)));
        server.createContext("/metrics", ex -> send(ex, 200, "text/plain; version=0.0.4; charset=utf-8", metrics.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/decide", ex -> {
            var q = new HashMap<String, String>();
            for (var kv : Optional.ofNullable(ex.getRequestURI().getRawQuery()).orElse("").split("&")) {
                var p = kv.split("=", 2); if (p.length == 2) q.put(p[0], URLDecoder.decode(p[1], StandardCharsets.UTF_8));
            }
            boolean ok = "1".equals(q.get("ok"));
            if (manual != null && q.containsKey("all")) manual.decideAll(ok);
            else if (manual != null && q.containsKey("id")) manual.decide(q.get("id"), ok);
            send(ex, 204, "text/plain", new byte[0]);
        });
        server.createContext("/stream", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.getResponseHeaders().add("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0);
            var q = new LinkedBlockingQueue<String>(256); clients.add(q);
            try (var out = ex.getResponseBody()) {
                while (true) {
                    out.write(("data: " + q.take() + "\n\n").getBytes(StandardCharsets.UTF_8)); out.flush();
                }
            } catch (IOException | InterruptedException e) { /* клиент ушёл */ } finally { clients.remove(q); }
        });
        server.start();
    }

    private static void send(com.sun.net.httpserver.HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, code == 204 ? -1 : body.length);
        if (code != 204) try (var o = ex.getResponseBody()) { o.write(body); }
        ex.close();
    }

    /** Prometheus text format (скрейпится Prometheus-ом или OTel Collector-ом через prometheus receiver). */
    static String promText(Map<String, Object> s) {
        var sb = new StringBuilder();
        BiConsumer<String, Object> g = (k, v) -> { if (v instanceof Number n) sb.append("organism_").append(k).append(' ').append(n.doubleValue()).append('\n'); };
        g.accept("tick", s.get("t")); g.accept("requests_per_second", s.get("rps")); g.accept("latency_p99_ms", s.get("p99"));
        g.accept("latency_sla_ms", s.get("target")); g.accept("availability_ratio", s.get("avail")); g.accept("cost_usd_per_hour", s.get("cost"));
        g.accept("cost_usd_per_million_requests", s.get("usd")); g.accept("fitness", s.get("fit")); g.accept("cells", s.get("replicas"));
        if (s.get("genes") instanceof Map<?, ?> genes)
            genes.forEach((k, v) -> sb.append("organism_gene{name=\"").append(k).append("\"} ").append(((double[]) v)[0]).append('\n'));
        if (s.get("data") instanceof Map<?, ?> d) for (var k : List.of("healthy", "degraded", "lost", "copying", "lag", "writesLost", "repaired"))
            g.accept("data_shards_" + k.replaceAll("([A-Z])", "_$1").toLowerCase(), d.get(k));
        return sb.toString();
    }

    void publish(String json, String prom) {
        metrics = prom;
        publish(json);
    }
    void publish(String json) {
        history.addLast(json); if (history.size() > 600) history.pollFirst();
        for (var q : clients) if (!q.offer(json)) { q.poll(); q.offer(json); }
    }
    @Override public void close() { server.stop(0); }
}
