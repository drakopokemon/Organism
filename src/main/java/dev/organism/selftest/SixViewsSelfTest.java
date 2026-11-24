package dev.organism.selftest;

import dev.organism.api.OrganismApi;
import dev.organism.obs.ObservabilityExporter;
import dev.organism.security.RbacPolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** 6.4: opt-in read-only views /rollout /control /anchor + GUI-панели. Дефолт = 404/скрыто, поведение не меняется. */
public final class SixViewsSelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-64s %s%n", ok ? "OK " : "FAIL", name, detail);
    }
    private static HttpResponse<String> req(HttpClient hc, String url, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(5)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return hc.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
    public static void main(String[] args) throws Exception {
        System.out.println("\n  ORGANISM 6.4 six-views selftest\n");
        Path tmp = Files.createTempDirectory("organism-six-views");
        String admin = "adm-" + UUID.randomUUID();
        Path rf = tmp.resolve("rbac.txt");
        Files.writeString(rf, RbacPolicy.sha256Hex(admin) + ":ADMIN\n");
        var rb = RbacPolicy.fromFile(rf);
        var metrics = new ObservabilityExporter();
        var api = new OrganismApi(0, rb, metrics, () -> "{}", () -> "{}", null, null, null, null,
                () -> "{}", () -> "{}", true);
        String base = "http://127.0.0.1:" + api.port();
        var hc = HttpClient.newHttpClient();
        check("default /rollout 404 (opt-in)", req(hc, base + "/rollout", admin).statusCode() == 404, "");
        check("default /control 404 (opt-in)", req(hc, base + "/control", admin).statusCode() == 404, "");
        check("default /anchor 404 (opt-in)", req(hc, base + "/anchor", admin).statusCode() == 404, "");
        var ui = req(hc, base + "/", null);
        check("GUI has 6.4 panels markup", ui.statusCode() == 200
                && ui.body().contains("id=\"rolloutCard\"") && ui.body().contains("id=\"controlCard\"")
                && ui.body().contains("id=\"anchorCard\""), "");
        check("GUI panels hidden by default", ui.body().contains("id=\"rolloutCard\" style=\"display:none\""), "");
        api.enableSixViews(() -> "{\"phase\":\"CANARY\",\"impact\":{\"impact\":0.245}}",
                () -> "{\"up\":true,\"mirrored\":2}", null);
        var ro = req(hc, base + "/rollout", admin);
        check("/rollout VIEW authed 200", ro.statusCode() == 200 && ro.body().contains("CANARY"), "");
        check("/rollout no token 401", req(hc, base + "/rollout", null).statusCode() == 401, "");
        var co = req(hc, base + "/control", admin);
        check("/control VIEW authed 200", co.statusCode() == 200 && co.body().contains("mirrored"), "");
        check("/anchor null supplier 404", req(hc, base + "/anchor", admin).statusCode() == 404, "");
        check("/rollout POST 405", hc.send(HttpRequest.newBuilder(URI.create(base + "/rollout"))
                .header("Authorization", "Bearer " + admin).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode() == 405, "");
        api.close();
        System.out.println(failed == 0 ? "\n  ALL GREEN\n" : "\n  " + failed + " FAILED\n");
        System.exit(failed == 0 ? 0 : 1);
    }
}
