package dev.organism.selftest;

import dev.organism.api.OrganismApi;
import dev.organism.evolution.EvolutionController;
import dev.organism.evolution.EvolutionEngine;
import dev.organism.evolution.EvolutionJournal;
import dev.organism.evolution.HumanApprovalGate;
import dev.organism.evolution.MutationGuard;
import dev.organism.evolution.ParetoFront;
import dev.organism.genome.Genome;
import dev.organism.immune.ImmuneAgent;
import dev.organism.migration.MigrationManager;
import dev.organism.net.Membership;
import dev.organism.obs.ObservabilityExporter;
import dev.organism.security.AuditLog;
import dev.organism.security.RbacPolicy;
import dev.organism.security.TransportSecurity;
import dev.organism.symbiont.SymbiontWrapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Продакшн-слой: security / immune / gate / controller / pareto / migration / obs / api / symbiont. */
public final class ProductionSelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-66s %s%n", ok ? "OK " : "FAIL", name, detail);
    }
    private static boolean await(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return cond.getAsBoolean();
    }

    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }

    static void run(String[] args) throws Exception {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
        System.out.println("\n  ORGANISM production selftest (security, immune, symbiont, migration, obs, api)\n");
        Path tmp = Files.createTempDirectory("organism-prod-selftest");
        transportSecurity();
        auditLog(tmp);
        rbac(tmp);
        immune();
        gate();
        controllerEngine(tmp);
        pareto();
        migration(tmp);
        obs();
        api(tmp, args);
        symbiont(tmp);
        System.out.println(failed == 0 ? "\n  ALL GREEN\n" : "\n  " + failed + " FAILED\n");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void transportSecurity() throws Exception {
        var psk = "a".repeat(64);
        var ts = new TransportSecurity(psk);
        byte[] plain = "hello gossip".getBytes(StandardCharsets.UTF_8);
        byte[] frame = ts.wrap(plain);
        check("gcm roundtrip", Arrays.equals(ts.unwrap(frame, frame.length), plain), "");
        check("gcm replay dropped", ts.unwrap(frame, frame.length) == null, "");
        byte[] tampered = frame.clone();
        tampered[tampered.length - 1] ^= 1;
        check("gcm tamper dropped", ts.unwrap(tampered, tampered.length) == null, "");
        check("gcm wrong key dropped", new TransportSecurity("b".repeat(64)).unwrap(frame, frame.length) == null, "");
        var shortWin = new TransportSecurity(psk, 1, 16);
        byte[] stale = shortWin.wrap(plain);
        Thread.sleep(10);
        check("gcm stale timestamp dropped", shortWin.unwrap(stale, stale.length) == null, "");
        var pbkdf2 = new TransportSecurity("passphrase-not-hex");
        byte[] f2 = pbkdf2.wrap(plain);
        check("pbkdf2 psk roundtrip", Arrays.equals(pbkdf2.unwrap(f2, f2.length), plain), "");
    }

    static void auditLog(Path tmp) throws Exception {
        Path f = tmp.resolve("audit.jsonl");
        try (var al = new AuditLog(f, "secret")) {
            al.log("E1", "d1");
            al.log("E2", "quote\" backslash\\");
            al.log("E3", "d3");
        }
        check("audit verify ok", AuditLog.verify(f, "secret"), "");
        check("audit wrong secret fails", !AuditLog.verify(f, "other"), "");
        List<String> lines = Files.readAllLines(f);
        Path reorder = tmp.resolve("reorder.jsonl");
        var rl = new ArrayList<>(lines);
        rl.set(0, lines.get(1)); rl.set(1, lines.get(0));
        Files.write(reorder, rl);
        Files.copy(AuditLog.anchorOf(f), AuditLog.anchorOf(reorder));
        check("audit reorder detected", !AuditLog.verify(reorder, "secret"), "");
        Path cut = tmp.resolve("cut.jsonl");
        Files.write(cut, List.of(lines.get(0), lines.get(2)));
        Files.copy(AuditLog.anchorOf(f), AuditLog.anchorOf(cut));
        check("audit middle-cut detected", !AuditLog.verify(cut, "secret"), "");
        Path trunc = tmp.resolve("trunc.jsonl");
        Files.write(trunc, List.of(lines.get(0), lines.get(1)));
        Files.copy(AuditLog.anchorOf(f), AuditLog.anchorOf(trunc));
        check("audit tail-truncate detected (anchor)", !AuditLog.verify(trunc, "secret"), "");
        Path tamper = tmp.resolve("tamper.jsonl");
        var tl = new ArrayList<>(lines);
        tl.set(0, lines.get(0).replace("d1", "dX"));
        Files.write(tamper, tl);
        check("audit byte tamper detected", !AuditLog.verify(tamper, "secret"), "");
        try (var al2 = new AuditLog(f, "secret")) { al2.log("E4", "resumed"); }
        check("audit resume chain continues", AuditLog.verify(f, "secret") && Files.readAllLines(f).size() == 4, "");
    }

    static void rbac(Path tmp) throws Exception {
        String admin = "adm-" + UUID.randomUUID(), op = "op-" + UUID.randomUUID(), view = "view-" + UUID.randomUUID();
        Path rf = tmp.resolve("rbac.txt");
        Files.writeString(rf, "# tokens\n" + RbacPolicy.sha256Hex(admin) + ":ADMIN\n"
                + RbacPolicy.sha256Hex(op) + ":OPERATOR\n" + RbacPolicy.sha256Hex(view) + ":VIEWER\n");
        var rb = RbacPolicy.fromFile(rf);
        check("rbac admin approves", rb.authorize(admin, RbacPolicy.Action.APPROVE), "");
        check("rbac operator cannot approve", !rb.authorize(op, RbacPolicy.Action.APPROVE), "");
        check("rbac operator mutates", rb.authorize(op, RbacPolicy.Action.MUTATE), "");
        check("rbac viewer views only", rb.authorize(view, RbacPolicy.Action.VIEW) && !rb.authorize(view, RbacPolicy.Action.MUTATE), "");
        check("rbac unknown token rejected", rb.roleOf("nope") == null, "");
    }

    static void immune() {
        var events = new ArrayList<ImmuneAgent.Event>();
        var im = new ImmuneAgent(100, 2, 3, 2, 10, events::add);
        im.observe(1, "n1", 500);
        im.observe(2, "n1", 500);
        im.observe(3, "n1", 500);
        check("immune quarantine after N ticks", im.isQuarantined("n1"), "events=" + events.size());
        im.observe(4, "n1", 50);
        check("immune no instant recovery", im.isQuarantined("n1"), "");
        im.observe(5, "n1", 50);
        check("immune recovery after M healthy ticks", !im.isQuarantined("n1")
                && events.get(events.size() - 1).reason().equals("recovery"), "");
        im.forget(6, "n1");
        check("immune forget on node loss", im.snapshot().isEmpty()
                && events.get(events.size() - 1).reason().equals("forget"), "");
        im.observe(10, "n2", 50);
        im.sweep(21);
        check("immune ttl sweep forgets stale node", im.snapshot().isEmpty(), "");
    }

    static void gate() {
        var clock = new AtomicLong(1000);
        var gate = new HumanApprovalGate(500, clock::get);
        var applied = new ArrayList<Map<String, Double>>();
        String id = gate.submit("a1", Map.of("poolSize", 20.0), 0.5, applied::add);
        check("gate pending", gate.pendingCount() == 1, "");
        check("gate approve applies mutation", gate.decide(id, true, "admin") == HumanApprovalGate.Status.APPROVED
                && applied.size() == 1 && applied.get(0).get("poolSize") == 20.0, "");
        check("gate double decide idempotent", gate.decide(id, true, "admin") == HumanApprovalGate.Status.APPROVED
                && applied.size() == 1, "");
        String id2 = gate.submit("a2", Map.of("poolSize", 30.0), 0.5, applied::add);
        clock.set(2000);
        check("gate ttl sweep expires", gate.sweep() == 1 && gate.get(id2).status == HumanApprovalGate.Status.EXPIRED, "");
        check("gate expired cannot approve", gate.decide(id2, true, "admin") == HumanApprovalGate.Status.EXPIRED
                && applied.size() == 1, "");
        String id3 = gate.submit("a3", Map.of("poolSize", 40.0), 0.5, applied::add);
        check("gate reject does not apply", gate.decide(id3, false, "admin") == HumanApprovalGate.Status.REJECTED
                && applied.size() == 1, "");
        check("gate unknown id null", gate.decide("nope", true, "admin") == null, "");
    }

    static void controllerEngine(Path tmp) {
        var genome = Genome.fromFile(Path.of("genome.yaml"));
        var membership = new Membership("n1:7100", 42, (to, msg) -> { }, (t, n, f, to, i) -> { });
        var journal = new EvolutionJournal(tmp.resolve("evo.jsonl"));
        var gate = new HumanApprovalGate(300000);
        var metrics = new ObservabilityExporter();
        var auditEvents = new ArrayList<String>();
        var controller = new EvolutionController(gate, (ev, d) -> auditEvents.add(ev), metrics, 0.10);
        var engine = new EvolutionEngine(genome, membership, journal, new MutationGuard(), 42, controller);
        var base = new TreeMap<String, Double>();
        base.put("poolSize", 10.0); base.put("queueLimit", 500.0); base.put("cacheMb", 128.0); base.put("timeoutMs", 500.0);
        engine.registerAgent("a", base);
        var b2 = new TreeMap<>(base); b2.put("timeoutMs", 600.0);
        engine.registerAgent("b", b2);
        engine.registerAgent("c", base);
        controller.bind(engine);
        for (int i = 0; i < 600; i++) {
            engine.updateFitness("a", 1.0);
            engine.updateFitness("b", 0.5);
            engine.tick();
        }
        check("engine+controller 600 ticks no crash", engine.snapshot().size() == 3, "");
        long pendingBefore = gate.pendingCount();
        var risky = new TreeMap<>(base); risky.put("poolSize", 50.0);
        controller.accept(new EvolutionEngine.MutationDecision("c", risky, 1.0));
        check("risky mutation gated + canary rolled back", gate.pendingCount() == pendingBefore + 1
                && engine.paramsOf("c").get("poolSize") == 10.0, "");
        String pid = null;
        for (var p : gate.all()) if (p.agentId.equals("c") && p.status == HumanApprovalGate.Status.PENDING) pid = p.id;
        check("gate proposal exists for c", pid != null, "");
        gate.decide(pid, true, "admin");
        check("approved mutation really applied to engine", engine.paramsOf("c").get("poolSize") == 50.0, "");
        var safe = new TreeMap<>(base); safe.put("timeoutMs", 510.0);
        controller.accept(new EvolutionEngine.MutationDecision("c", safe, 1.0));
        check("low-risk mutation auto path", auditEvents.contains("MUTATION_AUTO"), "");
        check("controller metrics recorded", metrics.value("organism_mutations_total") > 0, "");
        journal.close();
    }

    static void pareto() {
        var pf = ParetoFront.fromGenome(Genome.fromFile(Path.of("genome.yaml")));
        var a = new ParetoFront.Point("A", Map.of("p99", 40.0, "usd", 200.0));
        var b = new ParetoFront.Point("B", Map.of("p99", 60.0, "usd", 100.0));
        var c = new ParetoFront.Point("C", Map.of("p99", 80.0, "usd", 300.0));
        var front = pf.front(List.of(a, b, c));
        check("pareto front excludes dominated", front.contains(a) && front.contains(b) && !front.contains(c), "size=" + front.size());
        check("pareto MIN scalarization: lower p99 is better",
                pf.scalarize(a.values()) > pf.scalarize(c.values()), "");
        var pf2 = new ParetoFront(Map.of("rps", ParetoFront.Direction.MAX), Map.of("rps", 10000.0));
        var hi = new ParetoFront.Point("H", Map.of("rps", 12000.0));
        var lo = new ParetoFront.Point("L", Map.of("rps", 9000.0));
        check("pareto MAX direction: throughput higher dominates", pf2.dominates(hi, lo) && !pf2.dominates(lo, hi), "");
        check("pareto MAX scalarization", pf2.scalarize(hi.values()) > pf2.scalarize(lo.values()), "");
    }

    static void migration(Path tmp) throws Exception {
        var mm = new MigrationManager(tmp.resolve("snaps"), "c".repeat(64));
        String agent = "agent/../../evil";
        Path p1 = mm.snapshot(agent, "state-v1".getBytes(StandardCharsets.UTF_8));
        check("snapshot name sanitized", !p1.getFileName().toString().contains("/")
                && p1.getFileName().toString().startsWith("agent_"), p1.getFileName().toString());
        check("snapshot roundtrip", Arrays.equals(mm.loadLatest(agent), "state-v1".getBytes(StandardCharsets.UTF_8)), "");
        Thread.sleep(3);
        mm.snapshot(agent, "state-v2".getBytes(StandardCharsets.UTF_8));
        Thread.sleep(3);
        mm.snapshot(agent, "state-v3".getBytes(StandardCharsets.UTF_8));
        check("loadLatest picks newest by ts", Arrays.equals(mm.loadLatest(agent), "state-v3".getBytes(StandardCharsets.UTF_8)), "");
        byte[] raw = Files.readAllBytes(p1);
        raw[raw.length - 1] ^= 1;
        Files.write(p1, raw);
        boolean tamperCaught = false;
        try { mm.read(p1); } catch (SecurityException e) { tamperCaught = true; }
        check("snapshot tamper detected (HMAC)", tamperCaught, "");
        boolean wrongKey = false;
        try { new MigrationManager(tmp.resolve("snaps"), "d".repeat(64)).loadLatest(agent); }
        catch (SecurityException e) { wrongKey = true; }
        check("snapshot wrong key rejected", wrongKey, "");
        check("missing agent snapshot null", mm.loadLatest("ghost") == null, "");
    }

    static void obs() {
        var ex = new ObservabilityExporter();
        ex.inc("test_counter", 2, "k", "v");
        ex.inc("test_counter", 3, "k", "v");
        ex.gauge("test_gauge", 42, "node", "n\"1\\x\ny");
        String s1 = ex.scrape(), s2 = ex.scrape();
        check("scrape idempotent (no drain)", s1.equals(s2) && ex.value("test_counter", "k", "v") == 5.0, "");
        check("prometheus counter line", s1.contains("# TYPE test_counter counter") && s1.contains("test_counter{k=\"v\"} 5"), "");
        check("prometheus label escaping", s1.contains("n\\\"1\\\\x\\ny"), "");
        String otlp = ex.otlpJson("organism-test");
        check("otlp json structure", otlp.contains("resourceMetrics") && otlp.contains("test_counter") && otlp.contains("isMonotonic"), "");
    }

    static HttpResponse<String> req(HttpClient hc, String method, String url, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) b.header("Authorization", "Bearer " + token);
        return hc.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void api(Path tmp, String[] args) throws Exception {
        String admin = "adm-" + UUID.randomUUID(), op = "op-" + UUID.randomUUID(), view = "view-" + UUID.randomUUID();
        Path rf = tmp.resolve("api-rbac.txt");
        Files.writeString(rf, RbacPolicy.sha256Hex(admin) + ":ADMIN\n" + RbacPolicy.sha256Hex(op) + ":OPERATOR\n"
                + RbacPolicy.sha256Hex(view) + ":VIEWER\n");
        var rb = RbacPolicy.fromFile(rf);
        var metrics = new ObservabilityExporter();
        metrics.gauge("organism_up", 1);
        var gate = new HumanApprovalGate(300000);
        var applied = new ArrayList<Map<String, Double>>();
        String pid = gate.submit("a1", Map.of("poolSize", 20.0), 0.9, applied::add);
        var api = new OrganismApi(0, rb, metrics, () -> "{\"agents\":[]}", () -> "{\"objectives\":[]}", gate, () -> { }, null, null);
        String base = "http://127.0.0.1:" + api.port();
        var hc = HttpClient.newHttpClient();
        check("api /health open", req(hc, "GET", base + "/health", null).statusCode() == 200, "");
        check("api /metrics no token 401", req(hc, "GET", base + "/metrics", null).statusCode() == 401, "");
        check("api /metrics bad token 401", req(hc, "GET", base + "/metrics", "garbage").statusCode() == 401, "");
        var m = req(hc, "GET", base + "/metrics", view);
        check("api /metrics viewer 200", m.statusCode() == 200 && m.body().contains("organism_up"), "");
        check("api /mutations viewer 403", req(hc, "GET", base + "/mutations", view).statusCode() == 403, "");
        var mu = req(hc, "GET", base + "/mutations", op);
        check("api /mutations operator 200 + proposal", mu.statusCode() == 200 && mu.body().contains(pid), "");
        check("api /decide operator 403", req(hc, "POST", base + "/decide?id=" + pid + "&decision=approve", op).statusCode() == 403, "");
        var d = req(hc, "POST", base + "/decide?id=" + pid + "&decision=approve", admin);
        check("api /decide admin approves + applies", d.statusCode() == 200 && d.body().contains("APPROVED") && applied.size() == 1, "");
        check("api /decide unknown 404", req(hc, "POST", base + "/decide?id=nope&decision=approve", admin).statusCode() == 404, "");
        check("api /decide GET 405", req(hc, "GET", base + "/decide", admin).statusCode() == 405, "");
        check("api POST /mutations operator tick", req(hc, "POST", base + "/mutations", op).statusCode() == 200, "");
        api.close();
        var api2 = new OrganismApi(0, null, metrics, null, null, null, null, null, null);
        String b2 = "http://127.0.0.1:" + api2.port();
        check("api fail-closed without rbac", req(hc, "GET", b2 + "/metrics", admin).statusCode() == 403
                && req(hc, "GET", b2 + "/health", null).statusCode() == 200, "");
        api2.close();
        if (args.length >= 2) {
            var api3 = new OrganismApi(0, rb, metrics, null, null, null, null, args[0], args[1]);
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) { }
                public void checkServerTrusted(X509Certificate[] c, String a) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            var tlsClient = HttpClient.newBuilder().sslContext(sc).build();
            var h = req(tlsClient, "GET", "https://127.0.0.1:" + api3.port() + "/health", null);
            check("api TLS (PKCS12) health", h.statusCode() == 200 && h.body().contains("UP"), "");
            api3.close();
        }
    }

    static void symbiont(Path tmp) throws Exception {
        var w = new SymbiontWrapper("sleep 30", 0, 0, tmp.resolve("sym1.log"), 50, 3, (e, d) -> { });
        w.start();
        check("symbiont running", await(() -> w.state() == SymbiontWrapper.State.RUNNING, 3000) && w.pid() > 0, "");
        w.close();
        check("symbiont graceful stop", w.state() == SymbiontWrapper.State.STOPPED, "");
        var w2 = new SymbiontWrapper("sleep 30", 0, 256, tmp.resolve("sym2.log"), 50, 3, (e, d) -> { });
        w2.start();
        boolean run2 = await(() -> w2.state() == SymbiontWrapper.State.RUNNING, 3000);
        check("symbiont sandboxed start", run2 && w2.sandbox() != SymbiontWrapper.Sandbox.NONE, "sandbox=" + w2.sandbox());
        if (run2 && Files.exists(Path.of("/proc/" + w2.pid() + "/limits"))) {
            String limits = Files.readString(Path.of("/proc/" + w2.pid() + "/limits"));
            boolean enforced = false;
            for (String line : limits.split("\n"))
                if (line.startsWith("Max address space") && line.contains("268435456")) enforced = true;
            check("symbiont memory limit ENFORCED (ulimit -v)", enforced || w2.sandbox() == SymbiontWrapper.Sandbox.SYSTEMD_SCOPE, "");
        }
        w2.close();
        var events = new ArrayList<String>();
        var w3 = new SymbiontWrapper("exit 1", 0, 0, tmp.resolve("sym3.log"), 20, 2, (e, d) -> events.add(e));
        w3.start();
        check("symbiont crash loop -> circuit open", await(() -> w3.state() == SymbiontWrapper.State.CIRCUIT_OPEN, 5000)
                && events.contains("SYMBIONT_CIRCUIT_OPEN") && w3.restarts() == 3, "restarts=" + w3.restarts());
        w3.close();
    }
}
