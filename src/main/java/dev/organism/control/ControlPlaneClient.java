package dev.organism.control;

import dev.organism.obs.Json;
import dev.organism.obs.JsonReader;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 6.1 Uplink: тонкий клиент Control Plane (JDK HttpClient, ноль зависимостей). Аддитивный opt-in слой.
 * Fail-soft: любая сетевая ошибка / не-200 / битый JSON -> null или -1, наверх НИКОГДА не бросает.
 * Кернел не должен падать из-за недоступности control plane: локальный HumanApprovalGate остаётся рабочим fallback.
 * RBAC: токен передаётся заголовком X-Organism-Token (совместимо с RbacPolicy control plane).
 */
public final class ControlPlaneClient implements AutoCloseable {
    public record RemoteProposal(String id, boolean fourEyes) {}
    public record RemoteState(String status, int approvals, String decidedBy) {}

    private final HttpClient http;
    private final String base;
    private final String token;
    private final Duration timeout;

    public ControlPlaneClient(String baseUrl, String token, int timeoutMs) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (!b.startsWith("http://") && !b.startsWith("https://"))
            throw new IllegalArgumentException("control-plane url must be http(s): " + baseUrl);
        this.base = b;
        this.token = token;
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    public boolean health() {
        HttpResponse<String> r = send("GET", "/health", null);
        return r != null && r.statusCode() == 200;
    }

    /** POST /mutations/submit -> remote proposal id, либо null (down/403/битый ответ). */
    public RemoteProposal submitMutation(String agentId, Map<String, Double> params, double risk) {
        StringBuilder sb = new StringBuilder("{\"agentId\":").append(Json.str(agentId)).append(",\"params\":{");
        boolean first = true;
        for (Map.Entry<String, Double> e : params.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(Json.str(e.getKey())).append(':').append(Json.num(e.getValue()));
        }
        sb.append("},\"risk\":").append(Json.num(risk)).append('}');
        HttpResponse<String> r = send("POST", "/mutations/submit", sb.toString());
        if (r == null || r.statusCode() != 200) return null;
        try {
            Map<String, Object> m = JsonReader.obj(JsonReader.parse(r.body()));
            boolean fe = m.get("fourEyes") instanceof Boolean bb && bb;
            return new RemoteProposal(JsonReader.str(m, "id"), fe);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** GET /mutations/status?id= -> статус remote proposal, либо null (down/404/битый ответ). */
    public RemoteState status(String remoteId) {
        HttpResponse<String> r = send("GET", "/mutations/status?id=" + URLEncoder.encode(remoteId, StandardCharsets.UTF_8), null);
        if (r == null || r.statusCode() != 200) return null;
        try {
            Map<String, Object> m = JsonReader.obj(JsonReader.parse(r.body()));
            int approvals = (int) JsonReader.dbl(m, "approvals");
            Object by = m.get("decidedBy");
            return new RemoteState(JsonReader.str(m, "status"), approvals, by instanceof String s ? s : "");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** POST /propose (Raft-аудит control plane) -> commit index, либо -1. */
    public long propose(String event, String detail) {
        HttpResponse<String> r = send("POST", "/propose", event + "|" + detail);
        if (r == null || r.statusCode() != 200) return -1;
        try {
            return (long) JsonReader.dbl(JsonReader.obj(JsonReader.parse(r.body())), "index");
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** GET /audit/root -> сырой JSON (root/events/anchors/verified), либо null. */
    public String auditRoot() {
        HttpResponse<String> r = send("GET", "/audit/root", null);
        return r != null && r.statusCode() == 200 ? r.body() : null;
    }

    private HttpResponse<String> send(String method, String path, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                    .header("Accept", "application/json");
            if (token != null && !token.isBlank()) b.header("X-Organism-Token", token);
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override public void close() { http.close(); }
}
