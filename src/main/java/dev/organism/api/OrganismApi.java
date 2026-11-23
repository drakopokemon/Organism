package dev.organism.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.organism.control.FourEyesGate;
import dev.organism.evolution.HumanApprovalGate;
import dev.organism.obs.Json;
import dev.organism.obs.ObservabilityExporter;
import dev.organism.security.RbacPolicy;
import dev.organism.security.webauthn.WebAuthn;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * HTTP API узла: com.sun.net.httpserver + virtual threads (Loom), только JDK.
 * /health open; /metrics /agents /genome /cluster /audit -> VIEW; /mutations -> MUTATE;
 * /decide -> APPROVE (ADMIN). / и /ui -> GUI (HTML open, данные через auth).
 * 6.3 opt-in FIDO2: enableWebAuthn() -> /webauthn/status|register|challenge; approve только с assertion (enforce).
 * Bearer auth на каждый data-endpoint: 401/403/fail-closed. Ответы — байты UTF-8.
 * Hardening: per-IP token-bucket rate limit (429), body limit (413), security headers, CSP на GUI.
 * Опциональный TLS: HttpsServer + PKCS12.
 */
public final class OrganismApi implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService exec;
    private final RbacPolicy rbac;
    private final ObservabilityExporter metrics;
    private final Supplier<String> agentsJson, genomeJson, clusterJson, auditJson;
    private final HumanApprovalGate gate;
    private final Runnable manualTick;
    private final String uiHtml;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    // --- 6.3: opt-in FIDO2/WebAuthn на узле (дефолт null = legacy RBAC-only, поведение не меняется) ---
    private volatile WebAuthn webauthn;
    private volatile boolean webauthnEnforce;
    private volatile String webauthnRpId = "localhost";
    private volatile Set<String> webauthnOrigins = Set.of();
    private volatile RegisterHook webauthnHook;

    /** Callback персиста credential'ов (ядерный --webauthn-creds). */
    public interface RegisterHook { void registered(String credentialId, String user); }

    /**
     * Столп 6: FIDO2-подпись карточки мутации (challenge = SHA-256 карточки, человек подписывает то, что видит).
     * enforce=true: approve без валидной assertion -> 403 fail-closed; reject остаётся RBAC (отказ безопасен).
     * enforce=false: assertion опциональна; валидная -> who=webauthn:user, битая -> 403.
     * Вызывать после конструктора; повторный вызов запрещён. Four-eyes (две подписи) — на Control Plane.
     */
    public synchronized OrganismApi enableWebAuthn(WebAuthn wa, boolean enforce, String rpId,
                                                   Set<String> origins, RegisterHook hook) {
        if (webauthn != null) throw new IllegalStateException("webauthn already enabled");
        this.webauthn = Objects.requireNonNull(wa);
        this.webauthnEnforce = enforce;
        this.webauthnRpId = rpId == null || rpId.isBlank() ? "localhost" : rpId;
        this.webauthnOrigins = Set.copyOf(origins);
        this.webauthnHook = hook;
        server.createContext("/webauthn/status", ex -> handle(ex, RbacPolicy.Action.VIEW, this::waStatus));
        server.createContext("/webauthn/register", ex -> handle(ex, RbacPolicy.Action.APPROVE, this::waRegister));
        server.createContext("/webauthn/challenge", ex -> handle(ex, RbacPolicy.Action.APPROVE, this::waChallenge));
        return this;
    }

    public OrganismApi(int port, RbacPolicy rbac, ObservabilityExporter metrics,
                       Supplier<String> agentsJson, Supplier<String> genomeJson,
                       HumanApprovalGate gate, Runnable manualTick,
                       String tlsKeystore, String tlsPass) throws Exception {
        this(port, rbac, metrics, agentsJson, genomeJson, gate, manualTick, tlsKeystore, tlsPass, null, null, false);
    }

    public OrganismApi(int port, RbacPolicy rbac, ObservabilityExporter metrics,
                       Supplier<String> agentsJson, Supplier<String> genomeJson,
                       HumanApprovalGate gate, Runnable manualTick,
                       String tlsKeystore, String tlsPass,
                       Supplier<String> clusterJson, Supplier<String> auditJson, boolean serveUi) throws Exception {
        this.rbac = rbac;
        this.metrics = metrics;
        this.agentsJson = agentsJson;
        this.genomeJson = genomeJson;
        this.clusterJson = clusterJson;
        this.auditJson = auditJson;
        this.gate = gate;
        this.manualTick = manualTick;
        this.uiHtml = serveUi ? loadResource("/organism-ui.html") : null;
        if (tlsKeystore != null && !tlsKeystore.isBlank()) {
            char[] pass = (tlsPass == null ? "" : tlsPass).toCharArray();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(Path.of(tlsKeystore))) { ks.load(in, pass); }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pass);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            HttpsServer https = HttpsServer.create(new InetSocketAddress(port), 64);
            https.setHttpsConfigurator(new HttpsConfigurator(ctx));
            server = https;
        } else {
            server = HttpServer.create(new InetSocketAddress(port), 64);
        }
        this.exec = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(exec);
        server.createContext("/health", ex -> handle(ex, null, this::health));
        server.createContext("/metrics", ex -> handle(ex, RbacPolicy.Action.VIEW, this::metrics));
        server.createContext("/agents", ex -> handle(ex, RbacPolicy.Action.VIEW, this::agents));
        server.createContext("/genome", ex -> handle(ex, RbacPolicy.Action.VIEW, this::genome));
        server.createContext("/cluster", ex -> handle(ex, RbacPolicy.Action.VIEW, this::cluster));
        server.createContext("/audit", ex -> handle(ex, RbacPolicy.Action.VIEW, this::audit));
        server.createContext("/mutations", ex -> handle(ex, RbacPolicy.Action.MUTATE, this::mutations));
        server.createContext("/decide", ex -> handle(ex, RbacPolicy.Action.APPROVE, this::decide));
        server.createContext("/", ex -> handle(ex, null, this::ui));
        server.start();
    }

    private static String loadResource(String name) {
        try (InputStream in = OrganismApi.class.getResourceAsStream(name)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { return null; }
    }

    public int port() { return server.getAddress().getPort(); }

    private interface H { void run(HttpExchange ex) throws Exception; }

    private void handle(HttpExchange ex, RbacPolicy.Action need, H h) {
        try {
            String ip = ex.getRemoteAddress().getAddress().getHostAddress();
            if (!allow(ip)) {
                ex.getResponseHeaders().set("Retry-After", "1");
                send(ex, 429, "{\"error\":\"rate limited\"}");
                return;
            }
            String cl = ex.getRequestHeaders().getFirst("Content-Length");
            if (cl != null) {
                try { if (Long.parseLong(cl) > 65536) { send(ex, 413, "{\"error\":\"body too large\"}"); return; } }
                catch (NumberFormatException ignored) { }
            }
            if (need != null) {
                if (rbac == null) { send(ex, 403, "{\"error\":\"rbac not configured; api fail-closed\"}"); return; }
                RbacPolicy.Role role = rbac.roleOf(bearer(ex));
                if (role == null) { penalize(ip); send(ex, 401, "{\"error\":\"unauthorized\"}"); return; }
                if (!rbac.allows(role, need)) { send(ex, 403, "{\"error\":\"forbidden\",\"role\":\"" + role + "\"}"); return; }
            }
            h.run(ex);
        } catch (Exception e) {
            try { send(ex, 500, "{\"error\":\"internal\"}"); } catch (IOException ignored) { }
        } finally {
            ex.close();
        }
    }

    private static final class Bucket { double tokens = 60; long last = System.nanoTime(); }

    private boolean allow(String ip) {
        if (buckets.size() > 10000) buckets.clear();
        long now = System.nanoTime();
        Bucket b = buckets.computeIfAbsent(ip, k -> new Bucket());
        synchronized (b) {
            b.tokens = Math.min(60, b.tokens + (now - b.last) / 1e9 * 20.0);
            b.last = now;
            if (b.tokens >= 1) { b.tokens -= 1; return true; }
            return false;
        }
    }

    private void penalize(String ip) {
        Bucket b = buckets.get(ip);
        if (b != null) synchronized (b) { b.tokens = Math.max(0, b.tokens - 5); }
    }

    private void health(HttpExchange ex) throws IOException {
        if (!method(ex, "GET")) return;
        send(ex, 200, "{\"status\":\"UP\",\"ts\":" + System.currentTimeMillis() + "}");
    }

    private void metrics(HttpExchange ex) throws IOException {
        if (!method(ex, "GET")) return;
        send(ex, 200, metrics == null ? "" : metrics.scrape(), "text/plain; version=0.0.4; charset=utf-8");
    }

    private void agents(HttpExchange ex) throws IOException {
        if (!method(ex, "GET")) return;
        send(ex, 200, agentsJson == null ? "{}" : agentsJson.get());
    }

    private void genome(HttpExchange ex) throws IOException {
        if (!method(ex, "GET")) return;
        send(ex, 200, genomeJson == null ? "{}" : genomeJson.get());
    }

    private void cluster(HttpExchange ex) throws IOException {
        if (!method(ex, "GET")) return;
        send(ex, 200, clusterJson == null ? "{}" : clusterJson.get());
    }

    private void audit(HttpExchange ex) throws IOException {
        if (!method(ex, "GET")) return;
        send(ex, 200, auditJson == null ? "{}" : auditJson.get());
    }

    private void ui(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/ui")) { send(ex, 404, "{\"error\":\"not found\"}"); return; }
        if (!method(ex, "GET")) return;
        if (uiHtml == null) { send(ex, 404, "{\"error\":\"ui not enabled\"}"); return; }
        ex.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'");
        send(ex, 200, uiHtml, "text/html; charset=utf-8");
    }

    private void mutations(HttpExchange ex) throws IOException {
        String m = ex.getRequestMethod();
        if (m.equals("GET")) {
            send(ex, 200, proposalsJson());
        } else if (m.equals("POST")) {
            if (manualTick == null) { send(ex, 503, "{\"error\":\"evolution disabled\"}"); return; }
            manualTick.run();
            send(ex, 200, "{\"ok\":true}");
        } else method(ex, "GET");
    }

    private void decide(HttpExchange ex) throws IOException {
        if (!method(ex, "POST")) return;
        if (gate == null) { send(ex, 503, "{\"error\":\"evolution disabled\"}"); return; }
        var q = query(ex);
        String id = q.get("id"), decision = q.get("decision");
        if (id == null || decision == null) { send(ex, 400, "{\"error\":\"id and decision required\"}"); return; }
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) { send(ex, 400, "{\"error\":\"bad id\"}"); return; }
        boolean approve;
        if (decision.equals("approve")) approve = true;
        else if (decision.equals("reject")) approve = false;
        else { send(ex, 400, "{\"error\":\"decision must be approve|reject\"}"); return; }
        String tok = bearer(ex);
        String who = "sha256:" + RbacPolicy.sha256Hex(tok == null ? "" : tok).substring(0, 12);
        String cred = q.get("cred");
        if (approve && webauthn != null && (webauthnEnforce || cred != null)) {
            String cd = q.get("cd"), ad = q.get("ad"), sig = q.get("sig");
            if (cred == null || cd == null || ad == null || sig == null) {
                send(ex, 403, "{\"error\":\"webauthn assertion required (fail-closed)\"}"); return;
            }
            var p = gate.get(id);
            if (p == null) { send(ex, 404, "{\"error\":\"unknown proposal\"}"); return; }
            try {
                byte[] challenge = WebAuthn.challengeFor(FourEyesGate.card(p.id, p.agentId, p.params, p.risk));
                webauthn.verifyAssertion(cred, b64d(cd), b64d(ad), b64d(sig),
                        challenge, webauthnOrigins, webauthnRpId, true);
            } catch (SecurityException | IllegalArgumentException e) {
                send(ex, 403, "{\"error\":\"webauthn verification failed\"}"); return;
            }
            who = "webauthn:" + webauthn.userOf(cred);
        }
        var st = gate.decide(id, approve, who);
        if (st == null) { send(ex, 404, "{\"error\":\"unknown proposal\"}"); return; }
        send(ex, 200, "{\"id\":" + Json.str(id) + ",\"status\":\"" + st + "\"}");
    }

    private void waStatus(HttpExchange ex) throws IOException {
        if (!method(ex, "GET")) return;
        send(ex, 200, "{\"enabled\":true,\"enforced\":" + webauthnEnforce
                + ",\"rpId\":" + Json.str(webauthnRpId) + ",\"credentials\":" + webauthn.size() + "}");
    }

    private void waRegister(HttpExchange ex) throws IOException {
        if (!method(ex, "POST")) return;
        var q = query(ex);
        String id = q.get("id"), user = q.get("user"), cose = q.get("cose");
        if (id == null || user == null || cose == null) { send(ex, 400, "{\"error\":\"id, user, cose required\"}"); return; }
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) { send(ex, 400, "{\"error\":\"bad credential id\"}"); return; }
        if (!user.matches("[A-Za-z0-9_.@-]{1,64}")) { send(ex, 400, "{\"error\":\"bad user\"}"); return; }
        try { webauthn.register(id, user, b64d(cose)); }
        catch (IllegalArgumentException e) { send(ex, 400, "{\"error\":\"bad COSE key\"}"); return; }
        if (webauthnHook != null) webauthnHook.registered(id, user);
        send(ex, 200, "{\"ok\":true,\"credentials\":" + webauthn.size() + "}");
    }

    private void waChallenge(HttpExchange ex) throws IOException {
        if (!method(ex, "GET")) return;
        if (gate == null) { send(ex, 503, "{\"error\":\"evolution disabled\"}"); return; }
        String id = query(ex).get("id");
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) { send(ex, 400, "{\"error\":\"bad id\"}"); return; }
        var p = gate.get(id);
        if (p == null) { send(ex, 404, "{\"error\":\"unknown proposal\"}"); return; }
        String card = FourEyesGate.card(p.id, p.agentId, p.params, p.risk);
        String ch = Base64.getUrlEncoder().withoutPadding().encodeToString(WebAuthn.challengeFor(card));
        send(ex, 200, "{\"id\":" + Json.str(id) + ",\"card\":" + Json.str(card)
                + ",\"challenge\":" + Json.str(ch) + ",\"rpId\":" + Json.str(webauthnRpId) + "}");
    }

    /** base64url/base64, padding не обязателен. IllegalArgumentException -> 400/403 выше. */
    private static byte[] b64d(String s) {
        String u = s.replace('+', '-').replace('/', '_');
        int eq = u.indexOf('=');
        if (eq >= 0) u = u.substring(0, eq);
        return Base64.getUrlDecoder().decode(u);
    }

    private String proposalsJson() {
        var sb = new StringBuilder("{\"proposals\":[");
        if (gate != null) {
            boolean first = true;
            for (var p : gate.all()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"id\":").append(Json.str(p.id))
                  .append(",\"agent\":").append(Json.str(p.agentId))
                  .append(",\"risk\":").append(Json.num(p.risk))
                  .append(",\"status\":\"").append(p.status).append('"')
                  .append(",\"decidedBy\":").append(Json.str(p.decidedBy))
                  .append(",\"createdMs\":").append(p.createdMs)
                  .append(",\"ttlMs\":").append(p.ttlMs)
                  .append(",\"params\":{");
                boolean f2 = true;
                for (var e : new java.util.TreeMap<>(p.params).entrySet()) {
                    if (!f2) sb.append(',');
                    f2 = false;
                    sb.append(Json.str(e.getKey())).append(':').append(Json.num(e.getValue()));
                }
                sb.append("}}");
            }
        }
        return sb.append("]}").toString();
    }

    private boolean method(HttpExchange ex, String want) throws IOException {
        if (!ex.getRequestMethod().equals(want)) {
            send(ex, 405, "{\"error\":\"method not allowed\"}");
            return false;
        }
        return true;
    }

    private static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return null;
        return h.substring(7).trim();
    }

    private static Map<String, String> query(HttpExchange ex) {
        var m = new HashMap<String, String>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, body, "application/json; charset=utf-8");
    }

    private static void send(HttpExchange ex, int code, String body, String contentType) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        var hdr = ex.getResponseHeaders();
        hdr.set("Content-Type", contentType);
        hdr.set("X-Content-Type-Options", "nosniff");
        hdr.set("X-Frame-Options", "DENY");
        hdr.set("Referrer-Policy", "no-referrer");
        hdr.set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    @Override public void close() {
        server.stop(0);
        exec.shutdownNow();
    }
}
