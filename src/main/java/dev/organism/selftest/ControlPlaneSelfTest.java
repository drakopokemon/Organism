package dev.organism.selftest;

import dev.organism.audit.Tsa;
import dev.organism.control.ControlPlaneServer;
import dev.organism.control.Envelope;
import dev.organism.control.FourEyesGate;
import dev.organism.obs.JsonReader;
import dev.organism.security.AuditLog;
import dev.organism.security.ca.JdkCa;
import dev.organism.security.webauthn.WebAuthn;
import dev.organism.supplychain.GenomeArtifact;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** End-to-end selftest Control Plane: raft->audit->TSA, envelope, SPRT, CRL, four-eyes WebAuthn поверх HTTP. */
public final class ControlPlaneSelfTest {
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

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("org-cp");
        JdkCa ca = new JdkCa(dir.resolve("ca"), "changeit");
        ca.init();
        Envelope env = Envelope.fromLines(List.of("poolSize:8:24:arm:0.5"));
        ControlPlaneServer s = new ControlPlaneServer(0, dir.resolve("cp"), "cp1", List.of(), true, env, ca, null);
        String base = "http://127.0.0.1:" + s.port();
        check("health open", req("GET", base + "/health", null).statusCode() == 200, "");
        check("raft single-node leader", req("GET", base + "/raft/status", null).body().contains("LEADER"), "");
        for (int i = 0; i < 5; i++)
            check("propose " + i, req("POST", base + "/propose", "EVT|payload-" + i).statusCode() == 200, "");
        String rootResp = req("GET", base + "/audit/root", null).body();
        check("audit root sees 5 events", rootResp.contains("\"events\":5"), rootResp);
        check("audit chain verifies", AuditLog.verify(dir.resolve("cp/audit.log"),
                Files.readString(dir.resolve("cp/audit.secret")).trim()), "");
        String anchor = req("POST", base + "/audit/anchor", "").body();
        check("anchor returns token", anchor.contains("\"token\""), "");
        PublicKey tsaPub = GenomeArtifact.publicFromB64(req("GET", base + "/tsa/pub", null).body().trim());
        Map<String, Object> ar = JsonReader.obj(JsonReader.parse(anchor));
        byte[] token = Base64.getDecoder().decode(JsonReader.str(ar, "token"));
        byte[] root = HexFormat.of().parseHex(JsonReader.str(ar, "root"));
        check("TSA token verifies via HTTP pub", Tsa.verify(token, tsaPub, root), "");
        check("audit/root verified flag", req("GET", base + "/audit/root", null).body().contains("\"verified\":true"), "");

        String envResp = req("POST", base + "/envelope",
                "{\"region\":\"arm\",\"old\":{\"poolSize\":16},\"new\":{\"poolSize\":24},\"impact\":0.245}").body();
        check("envelope AUTO_APPROVE", envResp.contains("AUTO_APPROVE"), envResp);
        String envResp2 = req("POST", base + "/envelope",
                "{\"region\":\"arm\",\"old\":{\"poolSize\":16},\"new\":{\"poolSize\":32},\"impact\":0.245}").body();
        check("envelope NEEDS_HUMAN", envResp2.contains("NEEDS_HUMAN"), "");

        req("POST", base + "/sprt/new?id=canary1", "");
        String d = "CONTINUE";
        for (int i = 0; i < 60 && d.equals("CONTINUE"); i++) {
            String r = req("POST", base + "/sprt?id=canary1", "{\"control\":50,\"treatment\":35}").body();
            d = JsonReader.str(JsonReader.obj(JsonReader.parse(r)), "decision");
        }
        check("SPRT over HTTP detects improvement", d.equals("BETTER"), "d=" + d);

        var cert = ca.issue("edge1", List.of("localhost"));
        check("crl empty initially", req("GET", base + "/crl", null).body().isBlank(), "");
        req("POST", base + "/crl/revoke?serial=" + cert.getSerialNumber().toString(16), "");
        check("crl lists revoked", req("GET", base + "/crl", null).body().contains(cert.getSerialNumber().toString(16)), "");
        check("revoked cert fails verify", !ca.verify(cert), "");

        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair alice = ec.generateKeyPair(), bob = ec.generateKeyPair();
        reg(base, "cred-a", "alice", WebAuthn.coseFromEc((ECPublicKey) alice.getPublic()));
        reg(base, "cred-b", "bob", WebAuthn.coseFromEc((ECPublicKey) bob.getPublic()));
        String sub = req("POST", base + "/mutations/submit",
                "{\"agentId\":\"a1\",\"params\":{\"poolSize\":50},\"risk\":0.9}").body();
        String pid = JsonReader.str(JsonReader.obj(JsonReader.parse(sub)), "id");
        check("risky mutation tracked four-eyes", sub.contains("\"fourEyes\":true"), sub);
        check("pending queue lists card", req("GET", base + "/mutations/pending", null).body().contains(pid), "");
        check("single decide refused under four-eyes",
                req("POST", base + "/decide?id=" + pid + "&decision=approve&who=admin", "").statusCode() == 409, "");
        byte[] ch = WebAuthn.challengeFor(FourEyesGate.card(pid, "a1", Map.of("poolSize", 50.0), 0.9));
        byte[] cdj = clientData(ch);
        String cdj64 = b64(cdj);
        byte[] auth1 = authData("localhost", 0x05, 1);
        String ap1 = req("POST", base + "/approve?id=" + pid + "&cred=cred-a",
                "{\"clientDataJSON\":\"" + cdj64 + "\",\"authenticatorData\":\"" + b64(auth1)
                        + "\",\"signature\":\"" + b64(sign(alice.getPrivate(), auth1, cdj)) + "\"}").body();
        check("first approval pending", ap1.contains("PENDING"), ap1);
        byte[] auth2 = authData("localhost", 0x05, 1);
        String ap2 = req("POST", base + "/approve?id=" + pid + "&cred=cred-b",
                "{\"clientDataJSON\":\"" + cdj64 + "\",\"authenticatorData\":\"" + b64(auth2)
                        + "\",\"signature\":\"" + b64(sign(bob.getPrivate(), auth2, cdj)) + "\"}").body();
        check("second approval approves", ap2.contains("APPROVED"), ap2);
        check("approval applied via raft audit",
                Files.readString(dir.resolve("cp/audit.log")).contains("MUTATION_APPLIED"), "");
        s.close();
        System.out.println(failed == 0 ? "CONTROLPLANE SELFTEST PASSED" : "CONTROLPLANE SELFTEST FAILED: " + failed);
        if (failed > 0) System.exit(1);
    }

    static void reg(String base, String cred, String user, byte[] cose) throws Exception {
        check("register " + cred, req("POST", base + "/webauthn/register",
                "{\"credentialId\":\"" + cred + "\",\"user\":\"" + user + "\",\"coseKey\":\"" + b64(cose) + "\"}").statusCode() == 200, "");
    }

    static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }

    static byte[] authData(String rpId, int flags, long count) {
        byte[] h = WebAuthn.sha256(rpId.getBytes(StandardCharsets.UTF_8));
        byte[] a = new byte[37];
        System.arraycopy(h, 0, a, 0, 32);
        a[32] = (byte) flags;
        a[33] = (byte) (count >>> 24);
        a[34] = (byte) (count >>> 16);
        a[35] = (byte) (count >>> 8);
        a[36] = (byte) count;
        return a;
    }

    static byte[] clientData(byte[] challenge) {
        String ch = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);
        return ("{\"type\":\"webauthn.get\",\"challenge\":\"" + ch + "\",\"origin\":\"http://localhost\"}").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] sign(PrivateKey k, byte[] auth, byte[] cdj) throws Exception {
        byte[] h = WebAuthn.sha256(cdj);
        byte[] s = new byte[auth.length + h.length];
        System.arraycopy(auth, 0, s, 0, auth.length);
        System.arraycopy(h, 0, s, auth.length, h.length);
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(k);
        sig.update(s);
        return sig.sign();
    }
}
