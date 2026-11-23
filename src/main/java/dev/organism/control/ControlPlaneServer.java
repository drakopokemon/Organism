package dev.organism.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.organism.audit.AuditAnchoring;
import dev.organism.audit.MerkleTree;
import dev.organism.audit.Tsa;
import dev.organism.evolution.HumanApprovalGate;
import dev.organism.obs.Json;
import dev.organism.obs.JsonReader;
import dev.organism.raft.RaftAuditBridge;
import dev.organism.raft.RaftNet;
import dev.organism.raft.RaftNode;
import dev.organism.raft.RaftStore;
import dev.organism.security.AuditLog;
import dev.organism.security.RbacPolicy;
import dev.organism.security.ca.JdkCa;
import dev.organism.security.webauthn.WebAuthn;
import dev.organism.supplychain.GenomeArtifact;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * OPT-IN Control Plane (столпы 2/6): отдельный процесс, НЕ трогает замороженный OrganismApi/TissueKernel.
 * Raft (порядок аудита) -> hash-chain AuditLog -> Merkle/TSA-якоря; Envelope + SPRT + Four-eyes/WebAuthn; CRL-эндпоинт.
 * Дефолт: single-node raft (edge-автономия), bind ТОЛЬКО loopback; RBAC opt-in (--rbac-file), иначе доверенный loopback/reverse-proxy.
 * Запуск: java -cp out dev.organism.control.ControlPlaneServer --port 7470 --dir cp-data [--four-eyes] [--envelope f] [--ca-dir d] [--peers id@host:port,...]
 */
public final class ControlPlaneServer implements AutoCloseable {
    private final HttpServer http;
    private final RaftNode raft;
    private final RaftNet.Tcp tcp;
    private final AuditLog audit;
    private final Path auditFile;
    private final Tsa tsa;
    private final PublicKey tsaPub;
    private final Tsa.AnchorStore anchors;
    private final AuditAnchoring anchoring;
    private final HumanApprovalGate gate;
    private final WebAuthn wa;
    private final FourEyesGate four;
    private final Envelope envelope;
    private final JdkCa ca;
    private final RbacPolicy rbac;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Sprt> sprtSessions = new ConcurrentHashMap<>();

    public ControlPlaneServer(int port, Path dir, String id, List<String> peerSpecs, boolean fourEyes,
                              Envelope envelope, JdkCa ca, RbacPolicy rbac) throws IOException {
        Files.createDirectories(dir);
        this.envelope = envelope;
        this.ca = ca;
        this.rbac = rbac;
        Path secretFile = dir.resolve("audit.secret");
        String secret;
        if (Files.exists(secretFile)) secret = Files.readString(secretFile).trim();
        else {
            byte[] rnd = new byte[32];
            new SecureRandom().nextBytes(rnd);
            secret = HexFormat.of().formatHex(rnd);
            Files.writeString(secretFile, secret);
        }
        this.auditFile = dir.resolve("audit.log");
        this.audit = new AuditLog(auditFile, secret);
        RaftStore.File store = new RaftStore.File(dir.resolve("raft.log"));
        RaftAuditBridge bridge = new RaftAuditBridge(audit, dir.resolve("raft.applied"));
        if (peerSpecs.isEmpty()) {
            this.tcp = null;
            this.raft = new RaftNode(id, List.of(), (to, f) -> { }, store, bridge, 42);
            this.raft.tick(1000); // single-node: мгновенный лидер
        } else {
            Map<String, InetSocketAddress> addrs = new LinkedHashMap<>();
            List<String> peerIds = new ArrayList<>();
            for (String spec : peerSpecs) {
                String[] at = spec.split("@");
                String[] hp = at[1].split(":");
                addrs.put(at[0], new InetSocketAddress(hp[0], Integer.parseInt(hp[1])));
                peerIds.add(at[0]);
            }
            int raftPort = Integer.parseInt(System.getProperty("organism.raft.port", "7471"));
            RaftNode[] holder = new RaftNode[1];
            this.tcp = new RaftNet.Tcp(raftPort, addrs, (src, f) -> { RaftNode n = holder[0]; if (n != null) n.onFrame(src, f); });
            this.raft = new RaftNode(id, peerIds, tcp, store, bridge, 42);
            holder[0] = this.raft;
        }
        Path keyFile = dir.resolve("tsa.key"), pubFile = dir.resolve("tsa.pub");
        PrivateKey tsaPriv;
        if (Files.exists(keyFile) && Files.exists(pubFile)) {
            tsaPriv = GenomeArtifact.privateFromB64(Files.readString(keyFile));
            this.tsaPub = GenomeArtifact.publicFromB64(Files.readString(pubFile));
        } else {
            KeyPair kp = GenomeArtifact.generateKeyPair();
            Files.writeString(keyFile, GenomeArtifact.b64(kp.getPrivate()));
            Files.writeString(pubFile, GenomeArtifact.b64(kp.getPublic()));
            tsaPriv = kp.getPrivate();
            this.tsaPub = kp.getPublic();
        }
        this.tsa = new Tsa(tsaPriv, "organism-tsa", System::currentTimeMillis);
        this.anchors = new Tsa.AnchorStore(dir.resolve("anchors.b64"));
        this.anchoring = new AuditAnchoring(auditFile, tsa, anchors);
        this.gate = new HumanApprovalGate(300000);
        this.wa = new WebAuthn();
        this.four = new FourEyesGate(gate, wa, 0.5, fourEyes, "localhost", Set.of("http://localhost", "http://127.0.0.1"));
        this.scheduler = Executors.newScheduledThreadPool(1);
        anchoring.startHourly(scheduler);
        scheduler.scheduleAtFixedRate(() -> raft.tick(System.currentTimeMillis()), 20, 20, TimeUnit.MILLISECONDS);
        this.http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 64);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.createContext("/", this::handle);
        http.start();
    }

    public int port() { return http.getAddress().getPort(); }

    private boolean allowed(HttpExchange ex, RbacPolicy.Action action) {
        if (rbac == null) return true;
        return rbac.authorize(ex.getRequestHeaders().getFirst("X-Organism-Token"), action);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            byte[] raw = ex.getRequestBody().readNBytes(65537);
            if (raw.length > 65536) { send(ex, 413, "{\"error\":\"body too large\"}"); return; }
            String body = new String(raw, StandardCharsets.UTF_8);
            switch (path) {
                case "/health" -> {
                    if (!method.equals("GET")) { send(ex, 405, err("GET only")); return; }
                    send(ex, 200, "{\"ok\":true,\"role\":\"" + raft.role() + "\",\"commit\":" + raft.commitIndex() + "}");
                }
                case "/raft/status" -> {
                    if (!allowed(ex, RbacPolicy.Action.VIEW)) { send(ex, 403, err("forbidden")); return; }
                    send(ex, 200, "{\"id\":" + Json.str(raft.id) + ",\"role\":\"" + raft.role() + "\",\"term\":" + raft.term()
                            + ",\"commit\":" + raft.commitIndex() + ",\"lastIndex\":" + raft.lastIndex()
                            + ",\"leader\":" + Json.str(raft.leaderId() == null ? "" : raft.leaderId()) + "}");
                }
                case "/propose" -> {
                    if (!method.equals("POST") || !allowed(ex, RbacPolicy.Action.MUTATE)) { send(ex, 403, err("forbidden")); return; }
                    int sep = body.indexOf('|');
                    String event = sep < 0 ? "EVENT" : body.substring(0, sep);
                    String detail = sep < 0 ? body : body.substring(sep + 1);
                    long idx = RaftAuditBridge.propose(raft, event, detail);
                    if (idx < 0) send(ex, 503, "{\"error\":\"not leader\"}");
                    else send(ex, 200, "{\"index\":" + idx + "}");
                }
                case "/audit/anchor" -> {
                    if (!method.equals("POST") || !allowed(ex, RbacPolicy.Action.MUTATE)) { send(ex, 403, err("forbidden")); return; }
                    byte[] root = anchoring.anchorNow();
                    List<byte[]> tokens = anchors.load();
                    byte[] last = tokens.get(tokens.size() - 1);
                    send(ex, 200, "{\"root\":\"" + HexFormat.of().formatHex(root) + "\",\"token\":\""
                            + Base64.getEncoder().encodeToString(last) + "\",\"serial\":" + Tsa.serialOf(last) + "}");
                }
                case "/audit/root" -> {
                    if (!allowed(ex, RbacPolicy.Action.VIEW)) { send(ex, 403, err("forbidden")); return; }
                    List<byte[]> leaves = anchoring.leaves();
                    byte[] root = new MerkleTree(leaves).root();
                    send(ex, 200, "{\"root\":\"" + HexFormat.of().formatHex(root) + "\",\"events\":" + leaves.size()
                            + ",\"anchors\":" + anchors.load().size() + ",\"verified\":" + anchoring.verifyLatest(tsaPub) + "}");
                }
                case "/tsa/pub" -> sendText(ex, 200, GenomeArtifact.b64(tsaPub));
                case "/crl" -> {
                    if (!allowed(ex, RbacPolicy.Action.VIEW)) { send(ex, 403, err("forbidden")); return; }
                    sendText(ex, 200, ca == null ? "" : String.join("\n", ca.crlLines()));
                }
                case "/crl/revoke" -> {
                    if (!method.equals("POST") || !allowed(ex, RbacPolicy.Action.MUTATE)) { send(ex, 403, err("forbidden")); return; }
                    if (ca == null) { send(ex, 409, err("no CA configured")); return; }
                    ca.revoke(new BigInteger(q.getOrDefault("serial", "0"), 16));
                    sendText(ex, 200, "REVOKED");
                }
                case "/envelope" -> {
                    if (!method.equals("POST") || !allowed(ex, RbacPolicy.Action.VIEW)) { send(ex, 403, err("forbidden")); return; }
                    Map<String, Object> m = JsonReader.obj(JsonReader.parse(body));
                    Envelope.Decision d = envelope.evaluate(JsonReader.str(m, "region"),
                            doubles(JsonReader.map(m, "old")), doubles(JsonReader.map(m, "new")), JsonReader.dbl(m, "impact"));
                    send(ex, 200, "{\"decision\":\"" + d + "\"}");
                }
                case "/sprt/new" -> {
                    if (!method.equals("POST") || !allowed(ex, RbacPolicy.Action.MUTATE)) { send(ex, 403, err("forbidden")); return; }
                    String sid = q.get("id");
                    if (sid == null) { send(ex, 400, err("id required")); return; }
                    sprtSessions.put(sid, new Sprt(
                            Double.parseDouble(q.getOrDefault("alpha", "0.05")),
                            Double.parseDouble(q.getOrDefault("beta", "0.1")),
                            Double.parseDouble(q.getOrDefault("delta", "0.3")),
                            Double.parseDouble(q.getOrDefault("sigma", "0.5")),
                            Double.parseDouble(q.getOrDefault("clip", "2.0")),
                            Long.parseLong(q.getOrDefault("maxN", "10000"))));
                    send(ex, 200, "{\"created\":" + Json.str(sid) + "}");
                }
                case "/sprt" -> {
                    String sid = q.get("id");
                    Sprt s = sid == null ? null : sprtSessions.get(sid);
                    if (s == null) { send(ex, 404, err("unknown sprt session")); return; }
                    if (method.equals("POST")) {
                        if (!allowed(ex, RbacPolicy.Action.MUTATE)) { send(ex, 403, err("forbidden")); return; }
                        Map<String, Object> m = JsonReader.obj(JsonReader.parse(body));
                        s.add(JsonReader.dbl(m, "control"), JsonReader.dbl(m, "treatment"));
                    } else if (!allowed(ex, RbacPolicy.Action.VIEW)) { send(ex, 403, err("forbidden")); return; }
                    send(ex, 200, "{\"decision\":\"" + s.decision() + "\",\"n\":" + s.samples() + ",\"llr\":" + Json.num(s.llr()) + "}");
                }
                case "/webauthn/register" -> {
                    if (!method.equals("POST") || !allowed(ex, RbacPolicy.Action.APPROVE)) { send(ex, 403, err("forbidden")); return; }
                    Map<String, Object> m = JsonReader.obj(JsonReader.parse(body));
                    wa.register(JsonReader.str(m, "credentialId"), JsonReader.str(m, "user"),
                            Base64.getDecoder().decode(JsonReader.str(m, "coseKey")));
                    send(ex, 200, "{\"registered\":true}");
                }
                case "/mutations/submit" -> {
                    if (!method.equals("POST") || !allowed(ex, RbacPolicy.Action.MUTATE)) { send(ex, 403, err("forbidden")); return; }
                    Map<String, Object> m = JsonReader.obj(JsonReader.parse(body));
                    String agentId = JsonReader.str(m, "agentId");
                    Map<String, Double> params = doubles(JsonReader.map(m, "params"));
                    double risk = JsonReader.dbl(m, "risk");
                    String pid = four.submit(agentId, params, risk,
                            p -> RaftAuditBridge.propose(raft, "MUTATION_APPLIED", "agent=" + agentId + " params=" + p));
                    RaftAuditBridge.propose(raft, "MUTATION_PENDING", "agent=" + agentId + " risk=" + risk + " proposal=" + pid);
                    send(ex, 200, "{\"id\":" + Json.str(pid) + ",\"fourEyes\":" + four.requiresFourEyes(pid) + "}");
                }
                case "/approve" -> {
                    if (!method.equals("POST") || !allowed(ex, RbacPolicy.Action.APPROVE)) { send(ex, 403, err("forbidden")); return; }
                    String pid = q.get("id"), cred = q.get("cred");
                    if (pid == null || cred == null) { send(ex, 400, err("id and cred required")); return; }
                    Map<String, Object> m = JsonReader.obj(JsonReader.parse(body));
                    byte[] cdj = Base64.getDecoder().decode(JsonReader.str(m, "clientDataJSON"));
                    byte[] auth = Base64.getDecoder().decode(JsonReader.str(m, "authenticatorData"));
                    byte[] sig = Base64.getDecoder().decode(JsonReader.str(m, "signature"));
                    HumanApprovalGate.Status st = four.approve(pid, cred, cdj, auth, sig);
                    if (st == null) { send(ex, 404, err("unknown proposal")); return; }
                    send(ex, 200, "{\"status\":\"" + st + "\",\"approvals\":" + four.approvalsCount(pid) + "}");
                }
                case "/decide" -> {
                    if (!method.equals("POST") || !allowed(ex, RbacPolicy.Action.APPROVE)) { send(ex, 403, err("forbidden")); return; }
                    String pid = q.get("id");
                    if (pid == null) { send(ex, 400, err("id required")); return; }
                    if (four.enabled() && four.requiresFourEyes(pid)) { send(ex, 409, err("four-eyes signatures required")); return; }
                    boolean approve = "approve".equals(q.getOrDefault("decision", "approve"));
                    HumanApprovalGate.Status st = gate.decide(pid, approve, q.getOrDefault("who", "admin"));
                    if (st == null) send(ex, 404, err("unknown proposal"));
                    else send(ex, 200, "{\"status\":\"" + st + "\"}");
                }
                case "/mutations/pending" -> {
                    if (!allowed(ex, RbacPolicy.Action.VIEW)) { send(ex, 403, err("forbidden")); return; }
                    StringBuilder sb = new StringBuilder("[");
                    boolean first = true;
                    for (var p : gate.all()) {
                        if (p.status != HumanApprovalGate.Status.PENDING) continue;
                        if (!first) sb.append(',');
                        first = false;
                        sb.append("{\"id\":").append(Json.str(p.id)).append(",\"agent\":").append(Json.str(p.agentId))
                                .append(",\"risk\":").append(Json.num(p.risk))
                                .append(",\"approvals\":").append(four.approvalsCount(p.id))
                                .append(",\"fourEyes\":").append(four.requiresFourEyes(p.id))
                                .append(",\"card\":").append(Json.str(FourEyesGate.card(p.id, p.agentId, p.params, p.risk))).append('}');
                    }
                    send(ex, 200, sb.append(']').toString());
                }
                case "/mutations/status" -> {
                    if (!method.equals("GET") || !allowed(ex, RbacPolicy.Action.VIEW)) { send(ex, 403, err("forbidden")); return; }
                    String pid = q.get("id");
                    if (pid == null) { send(ex, 400, err("id required")); return; }
                    HumanApprovalGate.Proposal p = gate.get(pid);
                    if (p == null) { send(ex, 404, err("unknown proposal")); return; }
                    send(ex, 200, "{\"id\":" + Json.str(p.id) + ",\"status\":\"" + p.status
                            + "\",\"approvals\":" + four.approvalsCount(pid)
                            + ",\"fourEyes\":" + four.requiresFourEyes(pid)
                            + ",\"decidedBy\":" + Json.str(p.decidedBy) + "}");
                }
                default -> send(ex, 404, err("not found"));
            }
        } catch (SecurityException se) {
            send(ex, 403, err(se.getMessage()));
        } catch (IllegalStateException ise) {
            send(ex, 409, err(ise.getMessage()));
        } catch (IllegalArgumentException iae) {
            send(ex, 400, err(iae.getMessage()));
        } catch (Exception e) {
            send(ex, 500, err(String.valueOf(e.getMessage())));
        } finally {
            ex.close();
        }
    }

    private static Map<String, Double> doubles(Map<String, Object> m) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            if (!(e.getValue() instanceof Number n)) throw new IllegalArgumentException("param not a number: " + e.getKey());
            out.put(e.getKey(), n.doubleValue());
        }
        return out;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return q;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) q.put(URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    private static String err(String msg) { return "{\"error\":" + Json.str(msg == null ? "error" : msg) + "}"; }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }

    private static void sendText(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }

    @Override public void close() {
        http.stop(0);
        scheduler.shutdownNow();
        if (tcp != null) tcp.close();
        audit.close();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> a = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String k = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) a.put(k, args[++i]);
            else a.put(k, "true");
        }
        return a;
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> a = parseArgs(args);
        int port = Integer.parseInt(a.getOrDefault("port", "7470"));
        Path dir = Path.of(a.getOrDefault("dir", "organism-control"));
        String id = a.getOrDefault("id", "cp1");
        List<String> peers = a.containsKey("peers") ? List.of(a.get("peers").split(",")) : List.of();
        boolean fourEyes = a.containsKey("four-eyes");
        Envelope env = a.containsKey("envelope")
                ? Envelope.fromLines(Files.readAllLines(Path.of(a.get("envelope"))))
                : new Envelope(List.of(), Set.of());
        JdkCa ca = a.containsKey("ca-dir") ? new JdkCa(Path.of(a.get("ca-dir")), a.getOrDefault("ca-pass", "changeit")) : null;
        if (ca != null) ca.init();
        RbacPolicy rbac = a.containsKey("rbac-file") ? RbacPolicy.fromFile(Path.of(a.get("rbac-file"))) : null;
        ControlPlaneServer s = new ControlPlaneServer(port, dir, id, peers, fourEyes, env, ca, rbac);
        Runtime.getRuntime().addShutdownHook(new Thread(s::close));
        System.out.println("ORGANISM CONTROL PLANE on 127.0.0.1:" + s.port() + " dir=" + dir + " fourEyes=" + fourEyes);
        Thread.currentThread().join();
    }
}
