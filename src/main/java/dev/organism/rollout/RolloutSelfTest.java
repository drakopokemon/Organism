package dev.organism.rollout;

import dev.organism.cell.Cell;
import dev.organism.cell.CellHost;
import dev.organism.net.Membership;
import dev.organism.control.FourEyesGate;
import dev.organism.control.Sprt;
import dev.organism.data.EpochFence;
import dev.organism.evolution.HumanApprovalGate;
import dev.organism.genome.Genome;
import dev.organism.obs.ObservabilityExporter;
import dev.organism.security.webauthn.WebAuthn;

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
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Детерминированный selftest 6.2: Impact-формула, сплит трафика, SPRT-канарейка,
 * approval/four-eyes, стадии раскатки, epoch handoff + graceful drain (на реальных клетках).
 */
public final class RolloutSelfTest {
    private static int failed, total;

    private static void check(String name, boolean ok, String detail) {
        total++;
        if (!ok) failed++;
        System.out.printf("  %s %-62s %s%n", ok ? "OK " : "FAIL", name, detail);
    }

    static final Genome.MutationPolicy POLICY =
            new Genome.MutationPolicy(8, 8, 48, 16, 16, 2048, 64, 64, 1024, 50, 100, 2000);

    static RolloutManager.Config cfg() {
        return new RolloutManager.Config(5, 5, new double[]{25, 50, 100}, 2, 250, 0.10, 120, 200, 0.05);
    }

    static Sprt sprt() { return new Sprt(0.05, 0.1, 0.2, 0.3, 2.0, 500); }

    /** Симуляционный флот с детерминированной латентностью (реальные клетки -- в отдельном блоке ниже). */
    static final class SimFleet implements RolloutManager.Fleet {
        final double canaryMs, controlMs, drop;
        int spawned, promoted, drains, kills, rollbacks;
        double newPct, ctrlPct, lastShare;
        long lastGrace = -1;
        SimFleet(double canaryMs, double controlMs, double drop) {
            this.canaryMs = canaryMs; this.controlMs = controlMs; this.drop = drop;
        }
        @Override public int size() { return 20; }
        @Override public void spawnCanary(Map<String, Double> p) { spawned++; }
        @Override public void weights(double n, double c) { newPct = n; ctrlPct = c; }
        @Override public double canaryP99() { return canaryMs; }
        @Override public double controlP99() { return controlMs; }
        @Override public double canaryDropRate() { return drop; }
        @Override public void promote(Map<String, Double> p, double share) { promoted++; lastShare = share; }
        @Override public void drainOld(long grace) { drains++; lastGrace = grace; }
        @Override public void killCanary() { kills++; }
        @Override public void rollback() { rollbacks++; newPct = 0; ctrlPct = 0; }
    }

    static RolloutManager mgr(SimFleet f, HumanApprovalGate gate, FourEyesGate four, EpochFence fence,
                              Impact.Criticality crit, List<String> audit, ObservabilityExporter mx,
                              long[] clock) {
        return new RolloutManager(f, sprt(), gate, four, fence, POLICY, crit, cfg(),
                (ev, d) -> audit.add(ev), mx, () -> clock[0]);
    }

    static void drive(RolloutManager m, RolloutManager.Phase target, int maxTicks) {
        for (int i = 0; i < maxTicks && m.phase() != target; i++) m.tick();
    }

    public static void main(String[] args) throws Exception {
        System.out.println("\n  ORGANISM rollout selftest (6.2: impact, canary split, SPRT, four-eyes, drain)\n");

        // --- Impact ---
        Impact spec = Impact.of(0.2, 0.05, 0.8);
        check("impact formula 0.3*step + 0.5*blast + 0.2*crit", Math.abs(spec.value() - 0.245) < 1e-9, "value=" + spec.value());
        check("impact 0.245 does not require four-eyes", !spec.fourEyes(), "");
        check("impact > 0.5 requires four-eyes", Impact.of(0.9, 0.9, 0.9).fourEyes(), "value=" + Impact.of(0.9, 0.9, 0.9).value());
        check("components clamped to [0,1]", Impact.of(5, -3, 2).value() == 0.3 + 0.2, "value=" + Impact.of(5, -3, 2).value());
        double step = Impact.stepSize(Map.of("poolSize", 16.0), Map.of("poolSize", 24.0), POLICY);
        check("stepSize normalized by policy range (16->24 in 8..48)", Math.abs(step - 0.2) < 1e-9, "step=" + step);
        Impact computed = Impact.compute(Map.of("poolSize", 16.0), Map.of("poolSize", 24.0), POLICY,
                Impact.Criticality.defaults(), 250, 5000);
        check("spec example end-to-end: 250/5000 nodes, crit 0.8", Math.abs(computed.value() - 0.245) < 1e-9,
                computed.json());

        Path ok = Files.createTempFile("crit-ok", ".cfg");
        Files.writeString(ok, "# criticality\npoolSize:0.8\n*:0.3\n");
        var crit = Impact.Criticality.fromFile(ok);
        check("criticality side-car parsed", crit.of(Map.of("poolSize", 1.0)) == 0.8 && crit.of(Map.of("zzz", 1.0)) == 0.3, "");
        Path bad = Files.createTempFile("crit-bad", ".cfg");
        Files.writeString(bad, "poolSize:1.5\n");
        boolean threw = false;
        try { Impact.Criticality.fromFile(bad); } catch (java.io.IOException e) { threw = true; }
        check("criticality fail-closed on out-of-range", threw, "");
        Files.deleteIfExists(ok);
        Files.deleteIfExists(bad);

        // --- Traffic split ---
        var split = new TrafficSplit(5, 5);
        int c = 0, k = 0, b = 0;
        for (long i = 0; i < 100000; i++) {
            switch (split.route(i)) { case CANARY -> c++; case CONTROL -> k++; case BASE -> b++; }
        }
        check("5% canary / 5% control / 90% base", Math.abs(c - 5000) < 400 && Math.abs(k - 5000) < 400,
                "canary=" + c + " control=" + k + " base=" + b);
        check("split is sticky/deterministic", split.route(12345) == split.route(12345), "");
        int[] w = split.weights(1, 1, 2);
        check("router weights 500/500/4500/4500", w[0] == 500 && w[1] == 500 && w[2] == 4500 && w[3] == 4500,
                java.util.Arrays.toString(w));
        int[] w100 = new TrafficSplit(100, 0).weights(2, 0, 0);
        check("full rollout: old generation gets zero weight", w100[0] == 5000 && w100[1] == 5000, java.util.Arrays.toString(w100));
        boolean badSplit = false;
        try { new TrafficSplit(60, 50); } catch (IllegalArgumentException e) { badSplit = true; }
        check("split > 100% rejected", badSplit, "");

        // --- Happy path: canary -> SPRT BETTER -> approval -> stages -> handoff + drain ---
        var audit = new ArrayList<String>();
        var mx = new ObservabilityExporter();
        var fence = new EpochFence();
        var gate = new HumanApprovalGate(300000, () -> 1000L);
        var f = new SimFleet(40, 50, 0);
        var m = mgr(f, gate, null, fence, Impact.Criticality.defaults(), audit, mx, new long[]{1000});
        m.start("a1", Map.of("poolSize", 16.0), Map.of("poolSize", 24.0), 250, 5000);
        check("canary spawned with 5/5 split", f.spawned == 1 && f.newPct == 5 && f.ctrlPct == 5, "phase=" + m.phase());
        drive(m, RolloutManager.Phase.AWAIT_APPROVAL, 100);
        check("SPRT BETTER -> pending human approval (impact 0.245 > 0.10)",
                m.phase() == RolloutManager.Phase.AWAIT_APPROVAL && !m.proposalId().isEmpty(), "id=" + m.proposalId());
        check("canary traffic untouched while awaiting approval", f.promoted == 0 && f.newPct == 5, "");
        gate.decide(m.proposalId(), true, "admin");
        check("approval starts staged rollout at 25%",
                m.phase() == RolloutManager.Phase.ROLLOUT && f.lastShare == 25 && f.newPct == 25, "share=" + f.lastShare);
        drive(m, RolloutManager.Phase.DONE, 30);
        check("stages 25 -> 50 -> 100 then DONE", m.phase() == RolloutManager.Phase.DONE && f.promoted == 3 && f.lastShare == 100,
                "promoted=" + f.promoted);
        check("epoch handoff N+1 before drain", m.epoch() == 1 && fence.current("agent:a1") == 1, "epoch=" + m.epoch());
        check("old generation drained with grace", f.drains == 1 && f.lastGrace == 250, "grace=" + f.lastGrace);
        check("audit chain has canary/handoff/done", audit.contains("ROLLOUT_CANARY_START")
                && audit.contains("ROLLOUT_HANDOFF") && audit.contains("ROLLOUT_DONE"), "events=" + audit.size());
        check("metrics: rollouts_total{done}=1, epoch gauge",
                mx.value("organism_rollouts_total", "result", "done") == 1
                        && mx.value("organism_rollout_epoch", "resource", "agent:a1") == 1, "");

        // --- Auto path inside envelope (low impact) ---
        var gate2 = new HumanApprovalGate(300000, () -> 1000L);
        var f2 = new SimFleet(40, 50, 0);
        var m2 = mgr(f2, gate2, null, new EpochFence(), new Impact.Criticality(Map.of("poolSize", 0.1), 0.1),
                new ArrayList<>(), null, new long[]{1000});
        m2.start("a2", Map.of("poolSize", 16.0), Map.of("poolSize", 17.0), 1, 5000);
        drive(m2, RolloutManager.Phase.DONE, 100);
        check("low impact auto-rolls without human (envelope)",
                m2.phase() == RolloutManager.Phase.DONE && gate2.pendingCount() == 0, "impact=" + m2.impact().value());

        // --- SPRT NOT_BETTER -> rollback ---
        var f3 = new SimFleet(60, 50, 0);
        var m3 = mgr(f3, new HumanApprovalGate(300000, () -> 1000L), null, new EpochFence(),
                Impact.Criticality.defaults(), new ArrayList<>(), null, new long[]{1000});
        m3.start("a3", Map.of("poolSize", 16.0), Map.of("poolSize", 24.0), 250, 5000);
        drive(m3, RolloutManager.Phase.ROLLED_BACK, 100);
        check("slower canary -> ROLLED_BACK, no promotion",
                m3.phase() == RolloutManager.Phase.ROLLED_BACK && f3.promoted == 0 && f3.drains == 0
                        && f3.rollbacks == 1 && f3.kills == 1, m3.lastReason());

        // --- SPRT INCONCLUSIVE (maxN) -> fail-closed rollback ---
        var f4 = new SimFleet(50, 50, 0);
        var m4 = new RolloutManager(f4, new Sprt(0.05, 0.1, 0.3, 0.5, 2.0, 5),
                new HumanApprovalGate(300000, () -> 1000L), null, new EpochFence(), POLICY,
                Impact.Criticality.defaults(), cfg(), null, null, () -> 1000L);
        m4.start("a4", Map.of("poolSize", 16.0), Map.of("poolSize", 24.0), 250, 5000);
        drive(m4, RolloutManager.Phase.ROLLED_BACK, 100);
        check("SPRT INCONCLUSIVE keeps control (fail-closed)",
                m4.phase() == RolloutManager.Phase.ROLLED_BACK && m4.lastReason().startsWith("SPRT_INCONCLUSIVE"), m4.lastReason());

        // --- Health guard ---
        var f5 = new SimFleet(40, 50, 0.2);
        var m5 = mgr(f5, new HumanApprovalGate(300000, () -> 1000L), null, new EpochFence(),
                Impact.Criticality.defaults(), new ArrayList<>(), null, new long[]{1000});
        m5.start("a5", Map.of("poolSize", 16.0), Map.of("poolSize", 24.0), 250, 5000);
        m5.tick();
        check("canary drop-rate aborts rollout immediately",
                m5.phase() == RolloutManager.Phase.ROLLED_BACK && m5.lastReason().startsWith("CANARY_DROP_RATE"), m5.lastReason());

        // --- Human reject ---
        var gate6 = new HumanApprovalGate(300000, () -> 1000L);
        var f6 = new SimFleet(40, 50, 0);
        var m6 = mgr(f6, gate6, null, new EpochFence(), Impact.Criticality.defaults(), new ArrayList<>(), null, new long[]{1000});
        m6.start("a6", Map.of("poolSize", 16.0), Map.of("poolSize", 24.0), 250, 5000);
        drive(m6, RolloutManager.Phase.AWAIT_APPROVAL, 100);
        gate6.decide(m6.proposalId(), false, "admin");
        m6.tick();
        check("human reject -> REJECTED + traffic back to old gen",
                m6.phase() == RolloutManager.Phase.REJECTED && f6.rollbacks == 1 && f6.promoted == 0, m6.lastReason());

        // --- Approval TTL expiry ---
        long[] clock7 = {1000};
        var gate7 = new HumanApprovalGate(1000, () -> clock7[0]);
        var f7 = new SimFleet(40, 50, 0);
        var m7 = mgr(f7, gate7, null, new EpochFence(), Impact.Criticality.defaults(), new ArrayList<>(), null, clock7);
        m7.start("a7", Map.of("poolSize", 16.0), Map.of("poolSize", 24.0), 250, 5000);
        drive(m7, RolloutManager.Phase.AWAIT_APPROVAL, 100);
        clock7[0] += 5000;
        m7.tick();
        check("expired approval -> REJECTED (no silent apply)",
                m7.phase() == RolloutManager.Phase.REJECTED && m7.lastReason().equals("APPROVAL_EXPIRED"), m7.lastReason());

        // --- Four-eyes: impact > 0.5 needs two different humans (FIDO2) ---
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair alice = ec.generateKeyPair();
        KeyPair bob = ec.generateKeyPair();
        WebAuthn wa = new WebAuthn();
        wa.register("cred-alice", "alice", WebAuthn.coseFromEc((ECPublicKey) alice.getPublic()));
        wa.register("cred-bob", "bob", WebAuthn.coseFromEc((ECPublicKey) bob.getPublic()));
        String rpId = "organism.local";
        var gate8 = new HumanApprovalGate(300000, () -> 1000L);
        var four = new FourEyesGate(gate8, wa, 0.5, true, rpId, Set.of("https://organism.local"));
        var f8 = new SimFleet(40, 50, 0);
        var m8 = mgr(f8, gate8, four, new EpochFence(), Impact.Criticality.defaults(), new ArrayList<>(), null, new long[]{1000});
        m8.start("a8", Map.of("poolSize", 16.0), Map.of("poolSize", 24.0), 4500, 5000);
        drive(m8, RolloutManager.Phase.AWAIT_APPROVAL, 100);
        check("blast 90% -> impact > 0.5 -> four-eyes tracked",
                m8.impact().fourEyes() && four.requiresFourEyes(m8.proposalId()), "impact=" + m8.impact().value());
        String card = FourEyesGate.card(m8.proposalId(), "a8", Map.of("poolSize", 24.0), m8.impact().value());
        byte[] cdj = clientData(WebAuthn.challengeFor(card), "https://organism.local");
        byte[] authA = authData(rpId, 0x05, 1);
        var st1 = four.approve(m8.proposalId(), "cred-alice", cdj, authA, signEs256(alice.getPrivate(), authA, cdj));
        m8.tick();
        check("one signature is not enough", st1 == HumanApprovalGate.Status.PENDING
                && m8.phase() == RolloutManager.Phase.AWAIT_APPROVAL && f8.promoted == 0, "status=" + st1);
        byte[] authB = authData(rpId, 0x05, 1);
        var st2 = four.approve(m8.proposalId(), "cred-bob", cdj, authB, signEs256(bob.getPrivate(), authB, cdj));
        check("second human signature applies rollout", st2 == HumanApprovalGate.Status.APPROVED
                && m8.phase() == RolloutManager.Phase.ROLLOUT, "status=" + st2);
        drive(m8, RolloutManager.Phase.DONE, 30);
        check("four-eyes rollout completes with drain", m8.phase() == RolloutManager.Phase.DONE && f8.drains == 1, "");

        // --- Real cells: weights, promotion, graceful drain ---
        var fleet = new CellFleet("rollout-test", new Cell.Genes(2, 512, 64, 500), 2);
        try {
            fleet.spawnCanary(Map.of("poolSize", 4.0));
            fleet.weights(5, 5);
            Cell canary = fleet.newGeneration().get(0);
            Cell control = fleet.controlCell();
            Cell rest = fleet.oldGeneration().get(1);
            check("real cells get 5/5/90 weights", canary.weight == 500 && control.weight == 500 && rest.weight == 9000,
                    canary.weight + "/" + control.weight + "/" + rest.weight);
            check("canary genes really differ (pool 2 -> 4)", canary.genes.pool() == 4 && control.genes.pool() == 2,
                    "canaryPool=" + canary.genes.pool());
            fleet.promote(Map.of("poolSize", 4.0), 100);
            check("promotion spawns immutable new generation", fleet.newGeneration().size() == 2 && fleet.oldGeneration().size() == 2,
                    "new=" + fleet.newGeneration().size());
            int deadPort = control.port();
            long t0 = System.nanoTime();
            fleet.drainOld(5000);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check("idle old generation drains immediately", ms < 1000 && fleet.oldGeneration().isEmpty(), "drainMs=" + ms);
            boolean refused = false;
            try {
                var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
                http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + deadPort + "/health"))
                        .timeout(Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) { refused = true; }
            check("drained cell no longer accepts traffic", refused, "port=" + deadPort);
            check("new generation survives drain", fleet.newGeneration().size() == 2 && fleet.size() == 2, "");
        } finally {
            fleet.close();
        }

        // --- HostFleet: раскатка поверх замороженного CellHost (аддитивный API) ---
        var membership = new Membership("127.0.0.1:7999", 7, (to, msg) -> { }, (t, node, from, to, inc) -> { });
        var host = new CellHost(membership, 0, 7, 120, (t, msg) -> { });
        try {
            host.pauseEvolver(true);
            var hf = new HostFleet(host, new Cell.Genes(2, 512, 64, 500));
            hf.spawnCanary(Map.of("poolSize", 4.0));
            hf.weights(5, 5);
            check("HostFleet: canary added to frozen CellHost", host.cellCount() == 3 && hf.newGenerationSize() == 1,
                    "cells=" + host.cellCount());
            int cw = 0, ow = 0;
            for (Cell hc : host.cellsView()) { if (hc.canary) cw = hc.weight; else ow += hc.weight; }
            check("HostFleet: 5% canary weights applied", cw == 500 && ow == 9500, "canary=" + cw + " old=" + ow);
            hf.promote(Map.of("poolSize", 4.0), 100);
            check("HostFleet: promotion respects baseline (no fleet doubling)",
                    hf.newGenerationSize() == 2 && host.cellCount() == 4, "cells=" + host.cellCount());
            hf.drainOld(200);
            check("HostFleet: old generation drained, fleet back to baseline",
                    host.cellCount() == 2 && hf.newGenerationSize() == 2, "cells=" + host.cellCount());
            hf.rollback();
            check("HostFleet: rollback restores base genes", hf.currentParams().get("poolSize") == 2.0,
                    "params=" + hf.currentParams());
        } finally {
            host.close();
        }

        System.out.println(failed == 0 ? "\nROLLOUT SELFTEST PASSED (" + total + " checks)"
                : "\nROLLOUT SELFTEST FAILED: " + failed + "/" + total);
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
        return ("{\"type\":\"webauthn.get\",\"challenge\":\"" + ch + "\",\"origin\":\"" + origin + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] signEs256(PrivateKey k, byte[] auth, byte[] cdj) throws Exception {
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
