package dev.organism.symbiont;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Symbiont Wrapper: ОДИН supervisor virtual-thread loop над внешним процессом.
 * Exponential backoff + jitter, maxRestarts -> CIRCUIT_OPEN, graceful stop (TERM -> 5s -> KILL),
 * TCP health-probe, IO-drain через redirect в лог-файл (pipe не переполнится).
 * Изоляция ЧЕСТНАЯ: systemd-run --scope MemoryMax (Linux + cgroup v2 + booted systemd),
 * fallback ulimit -v (KB), иначе SANDBOX_NONE в аудит и ОТКАЗ старта при mem>0 (fail-closed).
 */
public final class SymbiontWrapper implements AutoCloseable {
    public enum State { NEW, RUNNING, BACKOFF, CIRCUIT_OPEN, STOPPED, FAILED }
    public enum Sandbox { SYSTEMD_SCOPE, ULIMIT_V, NONE }

    private final String command;
    private final int healthPort, memoryLimitMb, maxRestarts;
    private final long baseBackoffMs, capBackoffMs, probeIntervalMs;
    private final Path logFile;
    private final BiConsumer<String, String> audit;
    private final AtomicInteger restarts = new AtomicInteger();
    private volatile State state = State.NEW;
    private volatile Process proc;
    private volatile boolean stopped;
    private volatile Sandbox sandbox = Sandbox.NONE;
    private Thread supervisor;

    public SymbiontWrapper(String command, int healthPort, int memoryLimitMb, Path logFile,
                           long baseBackoffMs, int maxRestarts, BiConsumer<String, String> audit) {
        this(command, healthPort, memoryLimitMb, logFile, baseBackoffMs, 30_000, 500, maxRestarts, audit);
    }

    public SymbiontWrapper(String command, int healthPort, int memoryLimitMb, Path logFile,
                           long baseBackoffMs, long capBackoffMs, long probeIntervalMs,
                           int maxRestarts, BiConsumer<String, String> audit) {
        this.command = command;
        this.healthPort = healthPort;
        this.memoryLimitMb = memoryLimitMb;
        this.logFile = logFile;
        this.baseBackoffMs = Math.max(1, baseBackoffMs);
        this.capBackoffMs = Math.max(this.baseBackoffMs, capBackoffMs);
        this.probeIntervalMs = Math.max(10, probeIntervalMs);
        this.maxRestarts = Math.max(0, maxRestarts);
        this.audit = audit != null ? audit : (e, d) -> {};
        try {
            if (logFile.getParent() != null) Files.createDirectories(logFile.getParent());
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public synchronized void start() {
        if (state != State.NEW) throw new IllegalStateException("already started: " + state);
        sandbox = detectSandbox(memoryLimitMb);
        if (memoryLimitMb > 0 && sandbox == Sandbox.NONE) {
            state = State.FAILED;
            audit.accept("SANDBOX_NONE", "memoryLimitMb=" + memoryLimitMb
                    + " requested, no isolation available; refusing to start unsandboxed");
            throw new IllegalStateException("memory limit requested but no sandbox available; refusing to run unsandboxed");
        }
        supervisor = Thread.ofVirtual().name("symbiont-supervisor").start(this::loop);
    }

    private void loop() {
        try {
            while (!stopped) {
                if (restarts.get() > maxRestarts) {
                    state = State.CIRCUIT_OPEN;
                    audit.accept("SYMBIONT_CIRCUIT_OPEN", "restarts=" + restarts.get() + " maxRestarts=" + maxRestarts);
                    return;
                }
                Process p = null;
                try {
                    ProcessBuilder pb = new ProcessBuilder(buildCommand());
                    pb.redirectErrorStream(true);
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
                    p = pb.start();
                } catch (IOException e) {
                    audit.accept("SYMBIONT_SPAWN_FAILED", String.valueOf(e.getMessage()));
                }
                if (p != null) {
                    proc = p;
                    state = State.RUNNING;
                    audit.accept("SYMBIONT_START", "pid=" + p.pid() + " sandbox=" + sandbox
                            + " memMb=" + memoryLimitMb + " cmd=" + command);
                    int fails = 0;
                    while (!stopped && p.isAlive()) {
                        Thread.sleep(probeIntervalMs);
                        if (healthPort > 0 && p.isAlive() && !stopped) {
                            if (tcpProbe(healthPort)) fails = 0;
                            else if (++fails >= 3) {
                                audit.accept("SYMBIONT_UNHEALTHY", "pid=" + p.pid() + " tcp probe failed x" + fails);
                                p.destroy();
                            }
                        }
                    }
                    if (stopped) { graceful(p); break; }
                    int code = p.waitFor();
                    audit.accept("SYMBIONT_EXIT", "pid=" + p.pid() + " code=" + code);
                }
                if (stopped) break;
                int n = restarts.incrementAndGet();
                if (n > maxRestarts) continue;
                state = State.BACKOFF;
                long back = Math.min(capBackoffMs, baseBackoffMs * (1L << Math.min(n - 1, 16)));
                back += ThreadLocalRandom.current().nextLong(Math.max(1, back / 5));
                audit.accept("SYMBIONT_BACKOFF", "attempt=" + n + " sleepMs=" + back);
                Thread.sleep(back);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            Process p = proc;
            if (p != null && p.isAlive()) graceful(p);
            if (state != State.CIRCUIT_OPEN && state != State.FAILED) state = State.STOPPED;
            audit.accept("SYMBIONT_STOP", "restarts=" + restarts.get() + " state=" + state);
        }
    }

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        if (memoryLimitMb > 0 && sandbox == Sandbox.SYSTEMD_SCOPE) {
            cmd.add("systemd-run"); cmd.add("--scope"); cmd.add("--quiet");
            cmd.add("-p"); cmd.add("MemoryMax=" + memoryLimitMb + "M");
            cmd.add("-p"); cmd.add("MemorySwapMax=0");
            cmd.add("--");
            cmd.add("sh"); cmd.add("-c"); cmd.add(command);
            return cmd;
        }
        if (memoryLimitMb > 0 && sandbox == Sandbox.ULIMIT_V) {
            cmd.add("sh"); cmd.add("-c");
            cmd.add("ulimit -v " + (memoryLimitMb * 1024L) + "; exec " + command);
            return cmd;
        }
        cmd.add("sh"); cmd.add("-c"); cmd.add(command);
        return cmd;
    }

    static Sandbox detectSandbox(int memMb) {
        if (memMb <= 0) return Sandbox.NONE;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean linux = os.contains("linux");
        if (linux && Files.isReadable(Path.of("/sys/fs/cgroup/cgroup.controllers"))
                && Files.isDirectory(Path.of("/run/systemd/system")) && hasBin("systemd-run") && systemdRunWorks())
            return Sandbox.SYSTEMD_SCOPE;
        if ((linux || os.contains("mac")) && hasBin("sh")) return Sandbox.ULIMIT_V;
        return Sandbox.NONE;
    }

    private static boolean hasBin(String bin) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "command -v " + bin)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }


    /** Не верим наличию бинарника: проверяем что scope реально создаётся (polkit/dbus), bounded 4s. */
    private static boolean systemdRunWorks() {
        try {
            Process p = new ProcessBuilder("systemd-run", "--scope", "--quiet", "true")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(4, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    private static boolean tcpProbe(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) { return false; }
    }

    private void graceful(Process p) {
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    public State state() { return state; }
    public int restarts() { return restarts.get(); }
    public Sandbox sandbox() { return sandbox; }
    public long pid() { Process p = proc; return p == null ? -1 : p.pid(); }

    @Override public void close() {
        stopped = true;
        Thread t = supervisor;
        if (t != null) t.interrupt();
        Process p = proc;
        if (p != null && p.isAlive()) graceful(p);
        if (t != null) {
            try { t.join(Duration.ofSeconds(6)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
