package dev.organism.control;

import dev.organism.evolution.ControlPlaneUplink;
import dev.organism.evolution.HumanApprovalGate;
import dev.organism.obs.JsonReader;
import dev.organism.obs.ObservabilityExporter;
import dev.organism.security.RbacPolicy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 6.1 Uplink selftest: локальный HumanApprovalGate <-> ControlPlaneServer поверх loopback HTTP.
 * Детерминированный: фиксированные часы gate, без рандома, single-node raft.
 * Покрывает: mirror, remote approve/reject, метрики, RBAC fail-closed, CP down -> fail-open к локальному HITL.
 */
public final class UplinkSelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-62s %s%n", ok ? "OK " : "FAIL", name, detail);
    }

    static final HttpClient HC = HttpClient.newHttpClient();
    static HttpResponse<String> req(String method, String url, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HC.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String remoteIdByAgent(String base, String agent) throws Exception {
        Object arr = JsonReader.parse(req("GET", base + "/mutations/pending", null).body());
        for (Object o : (List<?>) arr) {
            Map<String, Object> m = JsonReader.obj(o);
            if (agent.equals(JsonReader.str(m, "agent"))) return JsonReader.str(m, "id");
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("\n  ORGANISM 6.1 uplink selftest (kernel gate <-> control plane)\n");
        Path dir = Files.createTempDirectory("org-uplink");
        Envelope env = Envelope.fromLines(List.of("poolSize:8:24:*:1.0"));
        ControlPlaneServer cp = new ControlPlaneServer(0, dir.resolve("cp"), "cp1", List.of(), false, env, null, null);
        String base = "http://127.0.0.1:" + cp.port();

        AtomicLong clock = new AtomicLong(1_000_000);
        HumanApprovalGate gate = new HumanApprovalGate(600_000, clock::get);
        List<Map<String, Double>> applied = Collections.synchronizedList(new ArrayList<>());
        List<String> audit = Collections.synchronizedList(new ArrayList<>());
        ObservabilityExporter metrics = new ObservabilityExporter();
        ControlPlaneClient client = new ControlPlaneClient(base, null, 1500);
        ControlPlaneUplink uplink = new ControlPlaneUplink(client, gate, (ev, d) -> audit.add(ev + "|" + d), metrics);

        check("control plane health", client.health(), base);

        String p1 = gate.submit("edge-1", Map.of("poolSize", 24.0), 0.9, applied::add);
        uplink.poll();
        String rid1 = remoteIdByAgent(base, "edge-1");
        check("risky proposal mirrored to control plane", uplink.mirroredCount() == 1 && rid1 != null, "remote=" + rid1);
        check("remote status PENDING", rid1 != null && client.status(rid1) != null
                && "PENDING".equals(client.status(rid1).status()), "");

        String dec = req("POST", base + "/decide?id=" + rid1 + "&decision=approve&who=alice", "").body();
        check("remote approve accepted", dec.contains("APPROVED"), dec);
        uplink.poll();
        check("remote APPROVED applied locally", gate.get(p1).status == HumanApprovalGate.Status.APPROVED
                && applied.size() == 1 && applied.get(0).equals(Map.of("poolSize", 24.0)),
                "status=" + gate.get(p1).status + " applied=" + applied);
        check("decidedBy carries control-plane origin", "control-plane:alice".equals(gate.get(p1).decidedBy),
                gate.get(p1).decidedBy);

        String p2 = gate.submit("edge-1", Map.of("cacheMb", 256.0), 0.8, applied::add);
        uplink.poll();
        String rid2 = remoteIdByAgent(base, "edge-1");
        req("POST", base + "/decide?id=" + rid2 + "&decision=reject&who=bob", "");
        uplink.poll();
        check("remote REJECTED rejected locally, nothing applied",
                gate.get(p2).status == HumanApprovalGate.Status.REJECTED && applied.size() == 1,
                "status=" + gate.get(p2).status);

        check("uplink up gauge", metrics.value("organism_control_uplink_up") == 1.0, "");
        check("mirrored counter", metrics.value("organism_control_mirrored_total") == 2.0, "");
        check("remote approved/rejected counters",
                metrics.value("organism_control_remote_approved_total") == 1.0
                        && metrics.value("organism_control_remote_rejected_total") == 1.0, "");

        boolean audited = false;
        for (int i = 0; i < 40 && !audited; i++) {
            String root = client.auditRoot();
            if (root != null && JsonReader.dbl(JsonReader.obj(JsonReader.parse(root)), "events") >= 2) audited = true;
            else Thread.sleep(50);
        }
        check("control plane Raft audit has PENDING+APPLIED events", audited, "");
        check("uplink audit trail written", audit.stream().anyMatch(s -> s.startsWith("CONTROL_PROPOSAL_MIRRORED"))
                && audit.stream().anyMatch(s -> s.startsWith("CONTROL_REMOTE_APPROVED")), "events=" + audit.size());

        Path dir2 = Files.createTempDirectory("org-uplink-rbac");
        Path rbacFile = dir2.resolve("rbac");
        Files.writeString(rbacFile, RbacPolicy.sha256Hex("uplink-token") + ":ADMIN\n");
        ControlPlaneServer cp2 = new ControlPlaneServer(0, dir2.resolve("cp"), "cp2", List.of(), false, env, null,
                RbacPolicy.fromFile(rbacFile));
        String base2 = "http://127.0.0.1:" + cp2.port();
        ControlPlaneClient noAuth = new ControlPlaneClient(base2, null, 1500);
        ControlPlaneClient auth = new ControlPlaneClient(base2, "uplink-token", 1500);
        check("RBAC: submit without token fail-closed", noAuth.submitMutation("edge-x", Map.of("poolSize", 12.0), 0.9) == null, "");
        check("RBAC: submit with ADMIN token works", auth.submitMutation("edge-x", Map.of("poolSize", 12.0), 0.9) != null, "");
        noAuth.close();
        auth.close();
        cp2.close();

        cp.close();
        String p3 = gate.submit("edge-1", Map.of("timeoutMs", 900.0), 0.7, applied::add);
        uplink.poll();
        check("CP down: poll survives, up gauge 0", metrics.value("organism_control_uplink_up") == 0.0
                && gate.get(p3).status == HumanApprovalGate.Status.PENDING, "");
        HumanApprovalGate.Status local = gate.decide(p3, true, "local-admin");
        check("CP down: local HITL still works (fail-open)", local == HumanApprovalGate.Status.APPROVED
                && applied.size() == 2 && applied.get(1).equals(Map.of("timeoutMs", 900.0)), "applied=" + applied);
        uplink.poll();
        check("resolved proposal dropped from mirror set", uplink.mirroredCount() == 0, "mirrored=" + uplink.mirroredCount());

        uplink.close();
        client.close();
        System.out.println(failed == 0 ? "\n  UPLINK SELFTEST PASSED" : "\n  UPLINK SELFTEST FAILED: " + failed);
        if (failed > 0) System.exit(1);
    }
}
