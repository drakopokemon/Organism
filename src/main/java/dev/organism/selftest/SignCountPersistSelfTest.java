package dev.organism.selftest;

import dev.organism.security.webauthn.WebAuthn;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/** 6.6: signCount переживает рестарт (append-only journal + restore), клон ключа после рестарта отвергается. */
public final class SignCountPersistSelfTest {
    private static int failed;
    private static void check(String name, boolean ok) {
        if (!ok) failed++;
        System.out.printf("  %s %s%n", ok ? "OK " : "FAIL", name);
    }
    private static String b64u(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static byte[] b64d(String s) { return Base64.getUrlDecoder().decode(s); }
    private static String[] assertion(KeyPair kp, String rpId, String origin, byte[] challenge, long count) throws Exception {
        byte[] cd = ("{\"type\":\"webauthn.get\",\"challenge\":\"" + b64u(challenge) + "\",\"origin\":\"" + origin + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] ad = new byte[37];
        System.arraycopy(WebAuthn.sha256(rpId.getBytes(StandardCharsets.UTF_8)), 0, ad, 0, 32);
        ad[32] = 0x05;
        ad[33] = (byte) (count >>> 24); ad[34] = (byte) (count >>> 16); ad[35] = (byte) (count >>> 8); ad[36] = (byte) count;
        byte[] signed = new byte[ad.length + 32];
        System.arraycopy(ad, 0, signed, 0, ad.length);
        System.arraycopy(WebAuthn.sha256(cd), 0, signed, ad.length, 32);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(kp.getPrivate()); s.update(signed);
        return new String[]{b64u(cd), b64u(ad), b64u(s.sign())};
    }
    private static long verify(WebAuthn wa, String[] a, byte[] ch) {
        return wa.verifyAssertion("cred-1", b64d(a[0]), b64d(a[1]), b64d(a[2]), ch, Set.of("http://localhost"), "localhost", false);
    }
    public static void main(String[] args) throws Exception {
        System.out.println("\n  ORGANISM 6.6 signCount persistence selftest\n");
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        byte[] cose = WebAuthn.coseFromEc((ECPublicKey) kp.getPublic());
        byte[] ch = WebAuthn.sha256("mutation-card-1".getBytes(StandardCharsets.UTF_8));
        WebAuthn wa = new WebAuthn();
        wa.register("cred-1", "alice", cose);
        List<String> journal = new ArrayList<>();
        wa.onSignCount((cid, count) -> journal.add(cid + "=" + count));
        check("verify count=1", verify(wa, assertion(kp, "localhost", "http://localhost", ch, 1), ch) == 1);
        String[] a2 = assertion(kp, "localhost", "http://localhost", ch, 2);
        check("verify count=2", verify(wa, a2, ch) == 2);
        check("CountHook fired twice", journal.size() == 2 && journal.get(1).equals("cred-1=2"));
        Path jf = Files.createTempDirectory("organism-signcount").resolve("wa.creds.counts");
        Files.writeString(jf, String.join("\n", journal) + "\n");
        WebAuthn restarted = new WebAuthn();
        restarted.register("cred-1", "alice", cose);
        for (String ln : Files.readAllLines(jf)) {
            int eq = ln.lastIndexOf('=');
            restarted.restoreSignCount(ln.substring(0, eq), Long.parseLong(ln.substring(eq + 1)));
        }
        check("restored signCount=2", restarted.credential("cred-1").signCount() == 2);
        boolean cloneRejected = false;
        try { verify(restarted, a2, ch); } catch (SecurityException e) { cloneRejected = true; }
        check("cloned key after restart rejected", cloneRejected);
        WebAuthn noRestore = new WebAuthn();
        noRestore.register("cred-1", "alice", cose);
        check("pre-6.6 hole reproduced (no restore accepts replay)", verify(noRestore, a2, ch) == 2);
        boolean rollbackRejected = false;
        try { restarted.restoreSignCount("cred-1", 1); } catch (SecurityException e) { rollbackRejected = true; }
        check("restore rollback rejected", rollbackRejected);
        boolean unknownRejected = false;
        try { restarted.restoreSignCount("nope", 5); } catch (IllegalArgumentException e) { unknownRejected = true; }
        check("restore unknown credential rejected", unknownRejected);
        System.out.println(failed == 0 ? "\n  ALL GREEN\n" : "\n  " + failed + " FAILED\n");
        System.exit(failed == 0 ? 0 : 1);
    }
}
