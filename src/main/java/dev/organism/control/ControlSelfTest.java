package dev.organism.control;

import dev.organism.evolution.HumanApprovalGate;
import dev.organism.security.webauthn.WebAuthn;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Детерминированный selftest столпов 2/6: Envelope, SPRT (log-latency), WebAuthn (синтетический EC), Four-eyes. */
public final class ControlSelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-62s %s%n", ok ? "OK " : "FAIL", name, detail);
    }

    public static void main(String[] args) throws Exception {
        Envelope env = Envelope.fromLines(List.of("poolSize:8:24:arm:0.5", "cacheMb:64:1024:*:0.3", "!unsafeMode"));
        double impact = Envelope.impact(0.2, 0.05, 0.8);
        check("impact formula = 0.245", Math.abs(impact - 0.245) < 1e-9, "impact=" + impact);
        check("inside envelope -> auto", env.evaluate("arm", Map.of("poolSize", 16.0), Map.of("poolSize", 24.0), impact) == Envelope.Decision.AUTO_APPROVE, "");
        check("outside bounds -> human", env.evaluate("arm", Map.of("poolSize", 16.0), Map.of("poolSize", 32.0), impact) == Envelope.Decision.NEEDS_HUMAN, "");
        check("wrong region -> human", env.evaluate("x86", Map.of("poolSize", 16.0), Map.of("poolSize", 24.0), impact) == Envelope.Decision.NEEDS_HUMAN, "");
        check("forbidden -> reject", env.evaluate("arm", Map.of(), Map.of("unsafeMode", 1.0), 0.1) == Envelope.Decision.REJECT, "");
        check("unknown param -> human", env.evaluate("arm", Map.of(), Map.of("mystery", 5.0), 0.1) == Envelope.Decision.NEEDS_HUMAN, "");
        check("wildcard region", env.evaluate("x86", Map.of("cacheMb", 64.0), Map.of("cacheMb", 512.0), 0.2) == Envelope.Decision.AUTO_APPROVE, "");
        check("impact over bound -> human", env.evaluate("x86", Map.of("cacheMb", 64.0), Map.of("cacheMb", 512.0), 0.9) == Envelope.Decision.NEEDS_HUMAN, "");
        check("empty envelope -> human (legacy path)", new Envelope(List.of(), Set.of()).evaluate("arm", Map.of("poolSize", 16.0), Map.of("poolSize", 20.0), 0.1) == Envelope.Decision.NEEDS_HUMAN, "");

        AtomicLong clock = new AtomicLong(0);
        HumanApprovalGate gate = new HumanApprovalGate(300000, clock::get);
        List<Map<String, Double>> applied = new ArrayList<>();
        String pid = gate.submit("a1", Map.of("poolSize", 24.0), 0.245, applied::add);
        EnvelopeAutoApprover auto = new EnvelopeAutoApprover(gate, env, "arm");
        check("envelope auto-approve applies", auto.tryAuto(pid, Map.of("poolSize", 16.0)) && applied.size() == 1, "");
        String pid2 = gate.submit("a1", Map.of("poolSize", 32.0), 0.9, applied::add);
        check("out-of-envelope stays pending", !auto.tryAuto(pid2, Map.of("poolSize", 16.0)) && gate.get(pid2).status == HumanApprovalGate.Status.PENDING, "");

        Random rnd = new Random(2026);
        Sprt better = new Sprt(0.05, 0.1, 0.3, 0.5, 2.0, 5000);
        int n1 = 0;
        while (better.decision() == Sprt.Decision.CONTINUE && n1 < 5000) {
            double c = Math.exp(Math.log(50) + 0.5 * rnd.nextGaussian());
            double t = Math.exp(Math.log(35) + 0.5 * rnd.nextGaussian());
            better.add(c, t);
            n1++;
        }
        check("SPRT detects improvement early", better.decision() == Sprt.Decision.BETTER && n1 < 500, "n=" + n1);
        Sprt same = new Sprt(0.05, 0.1, 0.3, 0.5, 2.0, 5000);
        int n2 = 0;
        while (same.decision() == Sprt.Decision.CONTINUE && n2 < 5000) {
            double c = Math.exp(Math.log(50) + 0.5 * rnd.nextGaussian());
            double t = Math.exp(Math.log(50) + 0.5 * rnd.nextGaussian());
            same.add(c, t);
            n2++;
        }
        check("SPRT no false improvement", same.decision() == Sprt.Decision.NOT_BETTER, "d=" + same.decision() + " n=" + n2);
        Sprt worse = new Sprt(0.05, 0.1, 0.3, 0.5, 2.0, 5000);
        for (int i = 0; i < 200 && worse.decision() == Sprt.Decision.CONTINUE; i++) worse.add(40, 60);
        check("SPRT rejects regression", worse.decision() == Sprt.Decision.NOT_BETTER, "");
        Sprt outlier = new Sprt(0.05, 0.1, 0.3, 0.5, 1.0, 5000);
        outlier.addLogDiff(1000);
        check("heavy-tail outlier bounded", outlier.decision() == Sprt.Decision.CONTINUE && outlier.llr() >= -1.5, "llr=" + outlier.llr());
        for (int i = 0; i < 100 && outlier.decision() == Sprt.Decision.CONTINUE; i++) outlier.add(50, 35);
        check("recovers after outlier", outlier.decision() == Sprt.Decision.BETTER, "");
        Sprt incon = new Sprt(0.05, 0.1, 0.3, 0.5, 2.0, 5);
        for (int i = 0; i < 5; i++) incon.addLogDiff(0);
        check("maxN fail-closed INCONCLUSIVE", incon.decision() == Sprt.Decision.INCONCLUSIVE, "d=" + incon.decision());

        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair alice = ec.generateKeyPair();
        KeyPair bob = ec.generateKeyPair();
        WebAuthn wa = new WebAuthn();
        wa.register("cred-alice", "alice", WebAuthn.coseFromEc((ECPublicKey) alice.getPublic()));
        wa.register("cred-bob", "bob", WebAuthn.coseFromEc((ECPublicKey) bob.getPublic()));
        String rpId = "organism.local";
        Set<String> origins = Set.of("https://organism.local");
        String card = FourEyesGate.card("m1", "a1", Map.of("poolSize", 50.0), 0.9);
        byte[] challenge = WebAuthn.challengeFor(card);
        byte[] auth = authData(rpId, 0x05, 1);
        byte[] cdj = clientData(challenge, "https://organism.local");
        byte[] sig = signEs256(alice.getPrivate(), auth, cdj);
        check("webauthn ES256 assertion ok", wa.verifyAssertion("cred-alice", cdj, auth, sig, challenge, origins, rpId, true) == 1, "");
        boolean rej = false;
        try { wa.verifyAssertion("cred-alice", cdj, authData(rpId, 0x05, 1), sig, challenge, origins, rpId, false); } catch (SecurityException e) { rej = true; }
        check("webauthn signCount rollback rejected", rej, "");
        rej = false;
        byte[] cdjBad = clientData(WebAuthn.challengeFor("other card"), "https://organism.local");
        byte[] auth2 = authData(rpId, 0x05, 2);
        try { wa.verifyAssertion("cred-alice", cdjBad, auth2, signEs256(alice.getPrivate(), auth2, cdjBad), challenge, origins, rpId, false); } catch (SecurityException e) { rej = true; }
        check("webauthn wrong challenge rejected", rej, "");
        rej = false;
        byte[] cdjOrig = clientData(challenge, "https://evil.example");
        try { wa.verifyAssertion("cred-alice", cdjOrig, auth2, signEs256(alice.getPrivate(), auth2, cdjOrig), challenge, origins, rpId, false); } catch (SecurityException e) { rej = true; }
        check("webauthn evil origin rejected", rej, "");
        rej = false;
        byte[] authRp = authData("evil.example", 0x05, 3);
        try { wa.verifyAssertion("cred-alice", cdj, authRp, signEs256(alice.getPrivate(), authRp, cdj), challenge, origins, rpId, false); } catch (SecurityException e) { rej = true; }
        check("webauthn rpId mismatch rejected", rej, "");
        rej = false;
        byte[] auth3 = authData(rpId, 0x05, 3);
        byte[] sig3 = signEs256(alice.getPrivate(), auth3, cdj);
        sig3[sig3.length - 1] ^= 1;
        try { wa.verifyAssertion("cred-alice", cdj, auth3, sig3, challenge, origins, rpId, false); } catch (SecurityException e) { rej = true; }
        check("webauthn tampered sig rejected", rej, "");
        rej = false;
        byte[] authNoUv = authData(rpId, 0x01, 4);
        try { wa.verifyAssertion("cred-alice", cdj, authNoUv, signEs256(alice.getPrivate(), authNoUv, cdj), challenge, origins, rpId, true); } catch (SecurityException e) { rej = true; }
        check("webauthn UV-required rejected", rej, "");
        KeyPair ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        wa.register("cred-ed", "carol", WebAuthn.coseFromEd(ed.getPublic()));
        byte[] authE = authData(rpId, 0x05, 1);
        check("webauthn EdDSA assertion ok", wa.verifyAssertion("cred-ed", cdj, authE, signEd(ed.getPrivate(), authE, cdj), challenge, origins, rpId, false) == 1, "");
        rej = false;
        try { wa.verifyAssertion("nope", cdj, authE, sig, challenge, origins, rpId, false); } catch (SecurityException e) { rej = true; }
        check("webauthn unknown credential rejected", rej, "");

        AtomicLong clock2 = new AtomicLong(0);
        HumanApprovalGate gate2 = new HumanApprovalGate(300000, clock2::get);
        List<Map<String, Double>> applied2 = new ArrayList<>();
        FourEyesGate four = new FourEyesGate(gate2, wa, 0.5, true, rpId, origins);
        String risky = four.submit("a1", Map.of("poolSize", 50.0), 0.9, applied2::add);
        check("four-eyes tracked for risk>0.5", four.requiresFourEyes(risky), "");
        byte[] chR = WebAuthn.challengeFor(FourEyesGate.card(risky, "a1", Map.of("poolSize", 50.0), 0.9));
        byte[] cdjR = clientData(chR, "https://organism.local");
        byte[] authA = authData(rpId, 0x05, 5);
        var st1 = four.approve(risky, "cred-alice", cdjR, authA, signEs256(alice.getPrivate(), authA, cdjR));
        check("one signature not enough", st1 == HumanApprovalGate.Status.PENDING && applied2.isEmpty(), "");
        byte[] authA2 = authData(rpId, 0x05, 6);
        var st2 = four.approve(risky, "cred-alice", cdjR, authA2, signEs256(alice.getPrivate(), authA2, cdjR));
        check("same human twice not enough", st2 == HumanApprovalGate.Status.PENDING && four.approvalsCount(risky) == 1, "");
        byte[] authB = authData(rpId, 0x05, 1);
        var st3 = four.approve(risky, "cred-bob", cdjR, authB, signEs256(bob.getPrivate(), authB, cdjR));
        check("two distinct humans approve + apply", st3 == HumanApprovalGate.Status.APPROVED && applied2.size() == 1, "");
        check("decidedBy records both humans", gate2.get(risky).decidedBy.contains("alice") && gate2.get(risky).decidedBy.contains("bob"), gate2.get(risky).decidedBy);
        String risky2 = four.submit("a2", Map.of("poolSize", 40.0), 0.8, applied2::add);
        byte[] authW = authData(rpId, 0x05, 7);
        rej = false;
        try { four.approve(risky2, "cred-alice", cdjR, authW, signEs256(alice.getPrivate(), authW, cdjR)); } catch (SecurityException e) { rej = true; }
        check("signature bound to exact card", rej && four.approvalsCount(risky2) == 0, "");
        String low = four.submit("a3", Map.of("poolSize", 20.0), 0.3, applied2::add);
        check("low risk not tracked", !four.requiresFourEyes(low), "");
        check("low risk single approve works", gate2.decide(low, true, "admin") == HumanApprovalGate.Status.APPROVED, "");
        HumanApprovalGate gate3 = new HumanApprovalGate(300000, clock2::get);
        FourEyesGate off = new FourEyesGate(gate3, wa, 0.5, false, rpId, origins);
        String legacy = off.submit("a4", Map.of("poolSize", 50.0), 0.9, applied2::add);
        check("disabled four-eyes keeps single approve", !off.requiresFourEyes(legacy) && gate3.decide(legacy, true, "admin") == HumanApprovalGate.Status.APPROVED, "");

        System.out.println(failed == 0 ? "CONTROL SELFTEST PASSED" : "CONTROL SELFTEST FAILED: " + failed);
        if (failed > 0) System.exit(1);
    }

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

    static byte[] clientData(byte[] challenge, String origin) {
        String ch = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);
        return ("{\"type\":\"webauthn.get\",\"challenge\":\"" + ch + "\",\"origin\":\"" + origin + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] signedBytes(byte[] auth, byte[] cdj) {
        byte[] h = WebAuthn.sha256(cdj);
        byte[] s = new byte[auth.length + h.length];
        System.arraycopy(auth, 0, s, 0, auth.length);
        System.arraycopy(h, 0, s, auth.length, h.length);
        return s;
    }

    static byte[] signEs256(PrivateKey k, byte[] auth, byte[] cdj) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(k);
        s.update(signedBytes(auth, cdj));
        return s.sign();
    }

    static byte[] signEd(PrivateKey k, byte[] auth, byte[] cdj) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(k);
        s.update(signedBytes(auth, cdj));
        return s.sign();
    }
}
