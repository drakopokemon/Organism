package dev.organism.selftest;

import dev.organism.api.OrganismApi;
import dev.organism.genome.Genome;
import dev.organism.genome.GenomeParser;
import dev.organism.obs.ObservabilityExporter;
import dev.organism.security.RbacPolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Genome YAML parser + GUI serving + /cluster /audit + rate-limit hardening. */
public final class UiGenomeSelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-64s %s%n", ok ? "OK " : "FAIL", name, detail);
    }
    static HttpResponse<String> req(HttpClient hc, String method, String url, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(5))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) b.header("Authorization", "Bearer " + token);
        return hc.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    public static void main(String[] args) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); System.exit(1); }
    }
    static void run() throws Exception {
        System.out.println("\n  ORGANISM ui+genome selftest\n");
        Path tmp = Files.createTempDirectory("organism-ui-genome");
        Path gy = tmp.resolve("genome.yaml");
        Files.writeString(gy, String.join("\n",
            "objectives:",
            "  - latency_p99_ms: 50.0",
            "  - cost_usd_per_month: 300.0",
            "  - throughput_rps: 10000.0",
            "constraints:",
            "  - allowed_regions: eu-west-1,eu-central-1",
            "  - max_memory_mb_per_agent: 512",
            "  - forbidden_apis: legacy-v1,deprecated-auth",
            "capabilities:",
            "  - processing: 2,16",
            "  - storage: 24,3",
            "  - balancing: least-connections",
            "  - immune: true",
            "mutation_policy:",
            "  pool_size: 2,16,2",
            "  queue_limit: 64,2048,64",
            "  cache_mb: 32,512,32",
            "  timeout_ms: 100,2000,100",
            ""));
        Genome g = GenomeParser.parse(gy);
        check("genome objectives parsed (latency/cost/throughput)", g.objectives.size() == 3
                && g.objectives.get(0) instanceof Genome.LatencyObjective l && l.targetP99Ms() == 50.0
                && g.objectives.get(2) instanceof Genome.ThroughputObjective t && t.targetRps() == 10000.0, "");
        check("genome constraints parsed (2 regions + ram + forbidden)", g.constraints.size() == 4
                && g.constraints.get(2) instanceof Genome.ResourceConstraint r && r.maxRamMb() == 512
                && g.constraints.get(3) instanceof Genome.ForbiddenApisConstraint, "");
        check("genome capabilities parsed (proc/storage/balancing/immune)", g.capabilities.size() == 4
                && g.capabilities.get(1) instanceof Genome.StorageCapability st && st.shards() == 24 && st.replication() == 3
                && g.capabilities.get(2) instanceof Genome.BalancingCapability ba && ba.algorithm().equals("least-connections"), "");
        check("genome mutation policy parsed (min,max,step)", g.mutationPolicy.poolSizeMin() == 2.0
                && g.mutationPolicy.poolSizeMax() == 16.0 && g.mutationPolicy.poolSizeStep() == 2.0
                && g.mutationPolicy.timeoutMsStep() == 100.0, "");
        Path bad = tmp.resolve("bad.yaml");
        Files.writeString(bad, "objectives:\n  - latency_p99_ms: 50\nmutation_policy:\n  pool_size: 1,2\n");
        boolean threw = false;
        try { GenomeParser.parse(bad); } catch (IllegalArgumentException e) { threw = true; }
        check("genome malformed fails closed", threw, "");
        Genome def = Genome.fromFile(tmp.resolve("missing.yaml"));
        check("genome missing file -> typed default", def.objectives.size() == 2, "");

        String admin = "adm-" + UUID.randomUUID();
        Path rf = tmp.resolve("rbac.txt");
        Files.writeString(rf, RbacPolicy.sha256Hex(admin) + ":ADMIN\n");
        var rb = RbacPolicy.fromFile(rf);
        var metrics = new ObservabilityExporter();
        metrics.gauge("organism_up", 1);
        String cluster = "{\"self\":\"n1\",\"nodes\":[{\"node\":\"n1\",\"status\":\"ALIVE\",\"self\":true,\"health\":\"HEALTHY\",\"p99\":10,\"cells\":2,\"memFree\":500}]}";
        String audit = "{\"verified\":true,\"events\":[{\"seq\":0,\"event\":\"KERNEL_START\",\"detail\":\"x\"}]}";
        var api = new OrganismApi(0, rb, metrics, () -> "{}", () -> "{}", null, null, null, null,
                () -> cluster, () -> audit, true);
        String base = "http://127.0.0.1:" + api.port();
        var hc = HttpClient.newHttpClient();
        var ui = req(hc, "GET", base + "/", null);
        check("GUI served at / (open)", ui.statusCode() == 200 && ui.body().contains("ORGANISM"), "");
        check("GUI has CSP header", ui.headers().firstValue("Content-Security-Policy").isPresent(), "");
        check("GUI nosniff header", ui.headers().firstValue("X-Content-Type-Options").orElse("").equals("nosniff"), "");
        var cl = req(hc, "GET", base + "/cluster", admin);
        check("/cluster VIEW authed", cl.statusCode() == 200 && cl.body().contains("nodes"), "");
        check("/cluster no token 401", req(hc, "GET", base + "/cluster", null).statusCode() == 401, "");
        var au = req(hc, "GET", base + "/audit", admin);
        check("/audit VIEW authed + verified", au.statusCode() == 200 && au.body().contains("verified"), "");
        int limited = 0;
        for (int i = 0; i < 250; i++) {
            if (req(hc, "GET", base + "/health", null).statusCode() == 429) limited++;
        }
        check("rate limiter returns 429 under flood", limited > 0, "429s=" + limited);
        api.close();
        System.out.println(failed == 0 ? "\n  ALL GREEN\n" : "\n  " + failed + " FAILED\n");
        System.exit(failed == 0 ? 0 : 1);
    }
}
