package dev.organism.exec;

import dev.organism.supplychain.BinaryVerifier;
import dev.organism.supplychain.GenomeArtifact;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;

/** Selftest столпа 3: supply-chain gate, рестарты/backoff, эпохи в env, graceful drain, isolation fail-closed. */
public final class SupervisorSelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-62s %s%n", ok ? "OK " : "FAIL", name, detail);
    }

    static String javaBin() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    static String cp() { return System.getProperty("java.class.path"); }

    static boolean awaitLog(Path p, String needle, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            try { if (Files.exists(p) && Files.readString(p).contains(needle)) return true; }
            catch (Exception ignored) { }
            Thread.sleep(20);
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("org-sup");
        KeyPair kp = GenomeArtifact.generateKeyPair();
        Path blob = dir.resolve("cell.bin");
        Files.write(blob, new byte[]{9, 9, 9});
        Path sig = dir.resolve("cell.sig");
        Files.write(sig, GenomeArtifact.sign(Files.readAllBytes(blob), kp.getPrivate()));

        Path log1 = dir.resolve("ok.log");
        NativeSupervisor ok = new NativeSupervisor("ok", List.of(javaBin(), "-cp", cp(), "dev.organism.exec.TestChild", "exit0"))
                .verify(BinaryVerifier.Mode.ED25519, blob, sig, kp.getPublic()).logFile(log1);
        ok.start();
        check("supervised exit0 stops cleanly", ok.awaitStopped(15000) && ok.state() == NativeSupervisor.State.STOPPED, "state=" + ok.state());
        check("epoch injected into env", Files.readString(log1).contains("EPOCH=1"), "");
        check("epoch allocated", ok.epoch() == 1, "");

        Files.write(blob, new byte[]{9, 9, 8});
        NativeSupervisor bad = new NativeSupervisor("bad", List.of(javaBin(), "-cp", cp(), "dev.organism.exec.TestChild", "exit0"))
                .verify(BinaryVerifier.Mode.ED25519, blob, sig, kp.getPublic());
        boolean refused = false;
        try { bad.start(); } catch (SecurityException e) { refused = true; }
        check("tampered binary refused (fail-closed)", refused && bad.state() == NativeSupervisor.State.NEW, "");

        Path counter = dir.resolve("counter");
        Path log2 = dir.resolve("fail.log");
        NativeSupervisor flaky = new NativeSupervisor("flaky", List.of(javaBin(), "-cp", cp(), "dev.organism.exec.TestChild", "fail2", counter.toString()))
                .maxRestarts(5).backoffMs(20).logFile(log2);
        flaky.start();
        check("flaky child restarted to success", flaky.awaitStopped(20000) && flaky.state() == NativeSupervisor.State.STOPPED, "state=" + flaky.state());
        check("restart count = 2", flaky.restarts() == 2, "restarts=" + flaky.restarts());
        String log2s = Files.readString(log2);
        check("epochs increment across restarts", log2s.contains("EPOCH=1") && log2s.contains("EPOCH=2") && log2s.contains("EPOCH=3"), "");

        Path sleepLog = dir.resolve("sleep.log");
        NativeSupervisor sleeper = new NativeSupervisor("sleeper", List.of(javaBin(), "-cp", cp(), "dev.organism.exec.TestChild", "sleep"))
                .drainGraceMs(5000).logFile(sleepLog);
        sleeper.start();
        check("sleeper child started", awaitLog(sleepLog, "READY=", 0) || awaitLog(sleepLog, "READY", 10000), "");
        Thread.sleep(250);
        check("graceful drain via SIGTERM", sleeper.drain(), "");
        check("drain logged DRAINED", Files.readString(sleepLog).contains("DRAINED"), "");

        Path stubLog = dir.resolve("stub.log");
        NativeSupervisor stubborn = new NativeSupervisor("stubborn", List.of(javaBin(), "-cp", cp(), "dev.organism.exec.TestChild", "sleep"))
                .drainGraceMs(0).logFile(stubLog);
        stubborn.start();
        check("stubborn child started", awaitLog(stubLog, "EPOCH=", 10000), "");
        Thread.sleep(250);
        check("zero grace -> forcible kill", !stubborn.drain(), "");

        NativeSupervisor nobody = new NativeSupervisor("n", List.of("/app/cell")).dropNobody(true);
        check("nobody drop argv", nobody.buildArgv().equals(List.of("runuser", "-u", "nobody", "--", "/app/cell")), "");
        NativeSupervisor crun = new NativeSupervisor("c", List.of("/app/cell")).crun("/bundle");
        check("crun argv", crun.buildArgv().equals(List.of("crun", "run", "--bundle", "/bundle", "c")), "");
        boolean crunRefused = false;
        try { crun.start(); } catch (Exception e) { crunRefused = true; }
        check("crun absent -> fail-closed", crunRefused || BinaryVerifier.toolAvailable("crun"), "");
        crun.close();

        System.out.println(failed == 0 ? "SUPERVISOR SELFTEST PASSED" : "SUPERVISOR SELFTEST FAILED: " + failed);
        if (failed > 0) System.exit(1);
    }
}
