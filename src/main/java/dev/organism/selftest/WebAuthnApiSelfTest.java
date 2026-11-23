package dev.organism.selftest;

import dev.organism.api.OrganismApi;
import dev.organism.control.FourEyesGate;
import dev.organism.evolution.HumanApprovalGate;
import dev.organism.genome.Genome;
import dev.organism.net.TissueKernel;
import dev.organism.security.RbacPolicy;
import dev.organism.security.webauthn.WebAuthn;
import dev.organism.supplychain.GenomeArtifact;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 6.3 selftest (детерминированный, синтетический EC-ключ, без браузера/железа):
 * - legacy /decide (без WebAuthn) не изменился;
 * - enforce: approve без assertion -> 403 fail-closed; валидная FIDO2-подпись карточки -> APPROVED, who=webauthn:user;
 * - rollback signCount / битая подпись / чужой origin / чужой challenge / неизвестный credential -> 403;
 * - reject остаётся RBAC; register/challenge/status под RBAC;
 * - optional-режим: и legacy approve, и подпись работают;
 * - столп 1: TissueKernel.loadVerifiedArtifact — подписанный CBOR-геном, чужой ключ/без ключа -> fail-closed.
 */
public final class WebAuthnApiSelfTest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Map<String, Double> PARAMS = Map.of("poolSize", 24.0);

    public static void main(String[] args) throws Exception {
        Path rbacFile = Files.createTempFile("org-rbac", ".txt");
        Files.writeString(rbacFile, RbacPolicy.sha256Hex("admin-token") + ":ADMIN\n"
                + RbacPolicy.sha256Hex("view-token") + ":VIEWER\n");
        RbacPolicy rbac = RbacPolicy.fromFile(rbacFile);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair alice = kpg.generateKeyPair();

        // 1) LEGACY: без enableWebAuthn поведение старое (одиночный RBAC approve, risk 0.9)
        HumanApprovalGate g0 = new HumanApprovalGate(60000);
        try (OrganismApi api = new OrganismApi(0, rbac, null, () -> "{}", () -> "{}", g0, null,
                null, null, null, null, false)) {
            String id = g0.submit("agent-x", PARAMS, 0.9, m -> { });
            var r = req(api.port(), "POST", "/decide?id=" + id + "&decision=approve", "admin-token");
            ok(r.statusCode() == 200 && jstr(r.body(), "status").equals("APPROVED"), "legacy approve must stay 200/APPROVED");
            ok(g0.get(id).decidedBy.startsWith("sha256:"), "legacy who must stay sha256(token)");
            String id2 = g0.submit("agent-x", PARAMS, 0.9, m -> { });
            var r2 = req(api.port(), "POST", "/decide?id=" + id2 + "&decision=approve&cred=ignored&cd=x&ad=x&sig=x", "admin-token");
            ok(r2.statusCode() == 200, "legacy api must ignore assertion params");
        }
        System.out.println("[1/4] legacy default unchanged OK");

        // 2) ENFORCE: FIDO2 обязателен для approve
        HumanApprovalGate gate = new HumanApprovalGate(60000);
        WebAuthn wa = new WebAuthn();
        AtomicInteger hooks = new AtomicInteger();
        try (OrganismApi api = new OrganismApi(0, rbac, null, () -> "{}", () -> "{}", gate, null,
                null, null, null, null, false)) {
            api.enableWebAuthn(wa, true, "localhost", Set.of("http://localhost"), (cid, user) -> hooks.incrementAndGet());
            int port = api.port();
            var st = req(port, "GET", "/webauthn/status", "view-token");
            ok(st.statusCode() == 200 && st.body().contains("\"enforced\":true"), "status VIEW for viewer");
            ok(req(port, "POST", "/webauthn/register?id=c&user=u&cose=x", null).statusCode() == 401, "register no token -> 401");
            ok(req(port, "POST", "/webauthn/register?id=c&user=u&cose=x", "view-token").statusCode() == 403, "register viewer -> 403");
            String cose = b64u(WebAuthn.coseFromEc((ECPublicKey) alice.getPublic()));
            var reg = req(port, "POST", "/webauthn/register?id=cred-1&user=alice&cose=" + enc(cose), "admin-token");
            ok(reg.statusCode() == 200 && hooks.get() == 1, "register admin + persist hook");
            ok(req(port, "POST", "/webauthn/register?id=cred-bad&user=alice&cose=AAAA", "admin-token").statusCode() == 400, "bad COSE -> 400");

            String p2 = gate.submit("agent-x", PARAMS, 0.9, m -> { });
            ok(req(port, "POST", "/decide?id=" + p2 + "&decision=approve", "admin-token").statusCode() == 403, "enforce: approve without assertion -> 403");
            ok(gate.get(p2).status == HumanApprovalGate.Status.PENDING, "enforce: proposal stays PENDING");
            byte[] challenge = WebAuthn.challengeFor(FourEyesGate.card(p2, "agent-x", PARAMS, 0.9));
            var ch = req(port, "GET", "/webauthn/challenge?id=" + p2, "admin-token");
            ok(ch.statusCode() == 200 && jstr(ch.body(), "challenge").equals(b64u(challenge)), "challenge = SHA-256(canonical card)");
            String[] a1 = assertion(alice, "localhost", "http://localhost", challenge, 1);
            var okR = req(port, "POST", decide(p2, "cred-1", a1), "admin-token");
            ok(okR.statusCode() == 200 && jstr(okR.body(), "status").equals("APPROVED"), "valid FIDO2 assertion -> APPROVED");
            ok(gate.get(p2).decidedBy.equals("webauthn:alice"), "who = webauthn:alice (human signature, not token hash)");

            String p3 = gate.submit("agent-x", PARAMS, 0.9, m -> { });
            byte[] ch3 = WebAuthn.challengeFor(FourEyesGate.card(p3, "agent-x", PARAMS, 0.9));
            ok(req(port, "POST", decide(p3, "cred-1", assertion(alice, "localhost", "http://localhost", ch3, 1)), "admin-token").statusCode() == 403, "signCount rollback -> 403");
            ok(req(port, "POST", decide(p3, "cred-1", assertion(alice, "localhost", "http://localhost", ch3, 2)), "admin-token").statusCode() == 200, "monotonic signCount -> 200");

            String p4 = gate.submit("agent-x", PARAMS, 0.9, m -> { });
            byte[] ch4 = WebAuthn.challengeFor(FourEyesGate.card(p4, "agent-x", PARAMS, 0.9));
            String[] tampered = assertion(alice, "localhost", "http://localhost", ch4, 3);
            byte[] sig = Base64.getUrlDecoder().decode(tampered[2]); sig[sig.length - 1] ^= 1;
            tampered[2] = b64u(sig);
            ok(req(port, "POST", decide(p4, "cred-1", tampered), "admin-token").statusCode() == 403, "tampered signature -> 403");

            String p5 = gate.submit("agent-x", PARAMS, 0.9, m -> { });
            byte[] ch5 = WebAuthn.challengeFor(FourEyesGate.card(p5, "agent-x", PARAMS, 0.9));
            ok(req(port, "POST", decide(p5, "cred-1", assertion(alice, "localhost", "http://evil.example", ch5, 3)), "admin-token").statusCode() == 403, "foreign origin -> 403");

            String p6 = gate.submit("agent-x", PARAMS, 0.9, m -> { });
            ok(req(port, "POST", decide(p6, "cred-1", assertion(alice, "localhost", "http://localhost", ch5, 3)), "admin-token").statusCode() == 403, "challenge of another card -> 403");

            String p7 = gate.submit("agent-x", PARAMS, 0.9, m -> { });
            var rej = req(port, "POST", "/decide?id=" + p7 + "&decision=reject", "admin-token");
            ok(rej.statusCode() == 200 && jstr(rej.body(), "status").equals("REJECTED"), "reject stays RBAC (safe direction)");

            String p8 = gate.submit("agent-x", PARAMS, 0.9, m -> { });
            byte[] ch8 = WebAuthn.challengeFor(FourEyesGate.card(p8, "agent-x", PARAMS, 0.9));
            ok(req(port, "POST", decide(p8, "cred-unknown", assertion(alice, "localhost", "http://localhost", ch8, 3)), "admin-token").statusCode() == 403, "unknown credential -> 403");
            String p9 = gate.submit("agent-x", PARAMS, 0.9, m -> { });
            ok(req(port, "POST", "/decide?id=" + p9 + "&decision=approve&cred=cred-1&ad=x&sig=x", "admin-token").statusCode() == 403, "missing clientDataJSON -> 403");
        }
        System.out.println("[2/4] enforce fail-closed + tamper/rollback/origin/challenge OK");

        // 3) OPTIONAL: enforce=false — оба пути
        HumanApprovalGate g2 = new HumanApprovalGate(60000);
        WebAuthn wa2 = new WebAuthn();
        wa2.register("cred-2", "bob", WebAuthn.coseFromEc((ECPublicKey) alice.getPublic()));
        try (OrganismApi api = new OrganismApi(0, rbac, null, () -> "{}", () -> "{}", g2, null,
                null, null, null, null, false)) {
            api.enableWebAuthn(wa2, false, "localhost", Set.of("http://localhost"), null);
            String id = g2.submit("agent-x", PARAMS, 0.9, m -> { });
            ok(req(api.port(), "POST", "/decide?id=" + id + "&decision=approve", "admin-token").statusCode() == 200, "optional: plain approve works");
            ok(g2.get(id).decidedBy.startsWith("sha256:"), "optional: plain who = sha256");
            String id2 = g2.submit("agent-x", PARAMS, 0.9, m -> { });
            byte[] ch = WebAuthn.challengeFor(FourEyesGate.card(id2, "agent-x", PARAMS, 0.9));
            ok(req(api.port(), "POST", decide(id2, "cred-2", assertion(alice, "localhost", "http://localhost", ch, 1)), "admin-token").statusCode() == 200, "optional: signed approve works");
            ok(g2.get(id2).decidedBy.equals("webauthn:bob"), "optional: signed who = webauthn:bob");
        }
        System.out.println("[3/4] optional mode OK");

        // 4) Столп 1: подписанный геном, fail-closed
        Genome g = Genome.defaultGenome();
        KeyPair k1 = GenomeArtifact.generateKeyPair(), k2 = GenomeArtifact.generateKeyPair();
        Path art = Files.createTempFile("genome", ".cbor");
        Files.write(art, GenomeArtifact.artifact(g, k1.getPrivate()));
        Genome back = TissueKernel.loadVerifiedArtifact(art.toString(), GenomeArtifact.b64(k1.getPublic()));
        ok(back.objectives.size() == g.objectives.size() && back.constraints.size() == g.constraints.size(), "signed genome roundtrip");
        expectSecurity(() -> TissueKernel.loadVerifiedArtifact(art.toString(), GenomeArtifact.b64(k2.getPublic())), "foreign key");
        expectSecurity(() -> TissueKernel.loadVerifiedArtifact(art.toString(), null), "missing --genome-pub");
        Path forged = Files.createTempFile("genome-forged", ".cbor");
        Files.write(forged, GenomeArtifact.artifact(g, k2.getPrivate()));
        expectSecurity(() -> TissueKernel.loadVerifiedArtifact(forged.toString(), GenomeArtifact.b64(k1.getPublic())), "forged signature");
        System.out.println("[4/4] signed genome artifact fail-closed OK");
        System.out.println("WEBAUTHN API SELFTEST OK");
    }

    static void expectSecurity(ThrowingRunnable r, String what) {
        try { r.run(); } catch (SecurityException e) { return; } catch (Exception e) {
            fail(what + ": expected SecurityException, got " + e); return;
        }
        fail(what + ": expected fail-closed SecurityException");
    }

    interface ThrowingRunnable { void run() throws Exception; }

    static String decide(String id, String cred, String[] a) {
        return "/decide?id=" + id + "&decision=approve&cred=" + enc(cred)
                + "&cd=" + enc(a[0]) + "&ad=" + enc(a[1]) + "&sig=" + enc(a[2]);
    }

    static String[] assertion(KeyPair kp, String rpId, String origin, byte[] challenge, long count) throws Exception {
        byte[] cd = ("{\"type\":\"webauthn.get\",\"challenge\":\"" + b64u(challenge) + "\",\"origin\":\"" + origin + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] ad = new byte[37];
        System.arraycopy(WebAuthn.sha256(rpId.getBytes(StandardCharsets.UTF_8)), 0, ad, 0, 32);
        ad[32] = 0x05; // UP | UV
        ad[33] = (byte) (count >>> 24); ad[34] = (byte) (count >>> 16); ad[35] = (byte) (count >>> 8); ad[36] = (byte) count;
        byte[] signed = new byte[ad.length + 32];
        System.arraycopy(ad, 0, signed, 0, ad.length);
        System.arraycopy(WebAuthn.sha256(cd), 0, signed, ad.length, 32);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(kp.getPrivate()); s.update(signed);
        return new String[]{b64u(cd), b64u(ad), b64u(s.sign())};
    }

    static HttpResponse<String> req(int port, String method, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) b.header("Authorization", "Bearer " + token);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String jstr(String json, String key) {
        String n = "\"" + key + "\":\"";
        int i = json.indexOf(n);
        if (i < 0) return "";
        int p = i + n.length(), e = json.indexOf('"', p);
        return e < 0 ? "" : json.substring(p, e);
    }

    static String b64u(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    static void ok(boolean cond, String msg) { if (!cond) fail(msg); }
    static void fail(String msg) { System.err.println("WEBAUTHN API SELFTEST FAIL: " + msg); System.exit(1); }
}
