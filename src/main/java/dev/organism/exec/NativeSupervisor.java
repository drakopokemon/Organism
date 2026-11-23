package dev.organism.exec;

import dev.organism.data.EpochFence;
import dev.organism.supplychain.BinaryVerifier;

import java.io.IOException;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Столп 3: Java-супервизор (эквивалент Go-демона), только JDK.
 * - Supply-chain gate: BinaryVerifier (Ed25519 native / opt-in cosign) FAIL-CLOSED до старта.
 * - Изоляция: opt-in crun (OCI без Docker daemon) или сброс прав в nobody через runuser.
 * - Параметры едут в env (ORGANISM_EPOCH/ORGANISM_CELL + пользовательские) — бинарник иммутабелен,
 *   мутации НЕ вшиваются в образ (спека 6.0, слой 1).
 * - Graceful draining: SIGTERM -> grace -> SIGKILL; рестарты с backoff и лимитом; watchdog на virtual thread.
 * Дефолт (без opt-in флагов): прямой exec без изоляции — legacy-поведение сохраняется.
 */
public final class NativeSupervisor implements AutoCloseable {
    public enum State { NEW, RUNNING, STOPPED, FAILED }

    private final String name;
    private final List<String> command;
    private final EpochFence fence = new EpochFence();
    private volatile BinaryVerifier.Mode verifyMode = BinaryVerifier.Mode.OFF;
    private volatile Path verifyBlob, verifySig, cosignPub;
    private volatile PublicKey verifyPub;
    private volatile boolean dropNobody, useCrun;
    private volatile String bundleDir;
    private volatile long drainGraceMs = 5000, backoffMs = 100;
    private volatile int maxRestarts = 3;
    private volatile Path logFile;
    private final Map<String, String> env = new LinkedHashMap<>();
    private volatile Process proc;
    private volatile State state = State.NEW;
    private volatile boolean stopping;
    private volatile int restarts;
    private volatile long epoch;

    public NativeSupervisor(String name, List<String> command) {
        this.name = name;
        this.command = List.copyOf(command);
    }

    public NativeSupervisor verify(BinaryVerifier.Mode mode, Path blob, Path sig, PublicKey pub) {
        this.verifyMode = mode; this.verifyBlob = blob; this.verifySig = sig; this.verifyPub = pub;
        return this;
    }
    public NativeSupervisor cosign(Path blob, Path sig, Path pubPem) {
        this.verifyMode = BinaryVerifier.Mode.COSIGN; this.verifyBlob = blob; this.verifySig = sig; this.cosignPub = pubPem;
        return this;
    }
    public NativeSupervisor dropNobody(boolean v) { this.dropNobody = v; return this; }
    public NativeSupervisor crun(String bundleDir) { this.useCrun = true; this.bundleDir = bundleDir; return this; }
    public NativeSupervisor drainGraceMs(long v) { this.drainGraceMs = v; return this; }
    public NativeSupervisor backoffMs(long v) { this.backoffMs = v; return this; }
    public NativeSupervisor maxRestarts(int v) { this.maxRestarts = v; return this; }
    public NativeSupervisor env(String k, String v) { this.env.put(k, v); return this; }
    public NativeSupervisor logFile(Path p) { this.logFile = p; return this; }

    List<String> buildArgv() {
        List<String> argv = new ArrayList<>();
        if (useCrun) {
            argv.add("crun"); argv.add("run"); argv.add("--bundle"); argv.add(bundleDir); argv.add(name);
        } else {
            if (dropNobody) { argv.add("runuser"); argv.add("-u"); argv.add("nobody"); argv.add("--"); }
            argv.addAll(command);
        }
        return argv;
    }

    public synchronized void start() throws IOException {
        if (state == State.RUNNING) return;
        if (verifyMode != BinaryVerifier.Mode.OFF
                && !BinaryVerifier.verify(verifyMode, verifyBlob, verifySig, verifyPub, cosignPub)) {
            throw new SecurityException("supervisor: binary verification FAILED (fail-closed) " + verifyBlob);
        }
        if (useCrun && !BinaryVerifier.toolAvailable("crun"))
            throw new IOException("supervisor: crun requested but not available (fail-closed)");
        if (dropNobody && !BinaryVerifier.toolAvailable("runuser"))
            throw new IOException("supervisor: runuser requested but not available (fail-closed)");
        stopping = false;
        restarts = 0;
        startProcess();
        state = State.RUNNING;
        Thread.ofVirtual().name("supervisor-" + name).start(this::watch);
    }

    private void startProcess() throws IOException {
        epoch = fence.allocate(name);
        ProcessBuilder pb = new ProcessBuilder(buildArgv());
        pb.environment().putAll(env);
        pb.environment().put("ORGANISM_EPOCH", Long.toString(epoch));
        pb.environment().put("ORGANISM_CELL", name);
        if (logFile != null) {
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile())).redirectErrorStream(true);
        } else {
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        proc = pb.start();
    }

    private void watch() {
        while (!stopping) {
            Process p = proc;
            if (p == null) return;
            int code;
            try { code = p.waitFor(); }
            catch (InterruptedException e) { return; }
            if (stopping) return;
            if (code == 0) { state = State.STOPPED; return; }
            if (restarts >= maxRestarts) { state = State.FAILED; return; }
            restarts++;
            try { Thread.sleep(backoffMs); }
            catch (InterruptedException e) { return; }
            if (stopping) return;
            try { startProcess(); }
            catch (IOException e) { state = State.FAILED; return; }
        }
    }

    /** Graceful draining: SIGTERM, ждём grace, затем SIGKILL. true = exited cleanly within grace. */
    public boolean drain() {
        stopping = true;
        Process p = proc;
        if (p == null || !p.isAlive()) { state = State.STOPPED; return true; }
        p.destroy();
        try {
            if (p.waitFor(drainGraceMs, TimeUnit.MILLISECONDS)) {
                state = State.STOPPED;
                return p.exitValue() == 0;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        p.destroyForcibly();
        try { p.waitFor(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        state = State.STOPPED;
        return false;
    }

    public State state() { return state; }
    public int restarts() { return restarts; }
    public long epoch() { return epoch; }
    public long pid() { Process p = proc; return p == null ? -1 : p.pid(); }

    public boolean awaitStopped(long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            State s = state;
            if (s == State.STOPPED || s == State.FAILED) return true;
            Thread.sleep(10);
        }
        return state == State.STOPPED || state == State.FAILED;
    }

    @Override public void close() { drain(); }
}
