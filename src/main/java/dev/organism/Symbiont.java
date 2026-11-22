package dev.organism;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Symbiont Wrapper — управляет жизненным циклом РЕАЛЬНОГО внешнего процесса (Python / Go / Node.js / что угодно,
 * что слушает HTTP на порту {port} и отвечает на /health): запуск, health-check, перезапуск с экспоненциальной
 * паузой, проксирование запросов. Эволюционируют параметры обёртки (timeout, retries), а не сам процесс.
 */
public final class Symbiont implements AutoCloseable {
    public enum State { STARTING, HEALTHY, UNHEALTHY, BACKOFF, STOPPED }
    public record Reply(boolean ok, int status, double ms) {}

    final String name; private final List<String> cmd;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
    volatile State state = State.STOPPED; volatile int port;
    private volatile Process proc; private volatile boolean running;
    final AtomicInteger restarts = new AtomicInteger();

    public Symbiont(String name, List<String> cmd) { this.name = name; this.cmd = List.copyOf(cmd); }

    public void start() {
        running = true;
        Thread.ofVirtual().name("symbiont-" + name).start(this::supervise);
    }

    private void supervise() {
        int backoff = 200, bad = 0;
        while (running) {
            try {
                if (proc == null || !proc.isAlive()) {
                    if (proc != null) {                       // процесс умер — пауза и перезапуск
                        state = State.BACKOFF; Thread.sleep(backoff);
                        backoff = Math.min(5000, backoff * 2); restarts.incrementAndGet();
                    }
                    if (!running) break;
                    launch(); state = State.STARTING; bad = 0;
                }
                if (ping()) { state = State.HEALTHY; bad = 0; backoff = 200; }
                else {
                    bad++;
                    if (state == State.HEALTHY) state = State.UNHEALTHY;
                    if (bad >= (state == State.STARTING ? 40 : 4)) { proc.destroyForcibly(); bad = 0; }   // завис — убиваем
                }
                Thread.sleep(250);
            } catch (InterruptedException e) { return; }
            catch (IOException e) { try { Thread.sleep(1000); } catch (InterruptedException ie) { return; } }
        }
    }

    private void launch() throws IOException {
        try (var s = new ServerSocket(0)) { port = s.getLocalPort(); }
        var real = new ArrayList<String>();
        for (var a : cmd) real.add(a.replace("{port}", Integer.toString(port)));
        proc = new ProcessBuilder(real).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private boolean ping() {
        try {
            var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                    .timeout(Duration.ofMillis(300)).build(), HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (Exception e) { return false; }
    }

    boolean available() { return state == State.HEALTHY || state == State.UNHEALTHY; }

    /** Один запрос к симбионту с таймаутом (retries решает пул). */
    Reply call(String path, int timeoutMs) {
        long t0 = System.nanoTime();
        try {
            var r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofMillis(timeoutMs)).build(), HttpResponse.BodyHandlers.discarding());
            return new Reply(r.statusCode() == 200, r.statusCode(), (System.nanoTime() - t0) / 1e6);
        } catch (Exception e) {
            return new Reply(false, 0, (System.nanoTime() - t0) / 1e6);
        }
    }

    /** kill -9 для демонстрации сбоя. */
    void crash() { var p = proc; if (p != null) p.destroyForcibly(); }
    boolean isProcessAlive() { var p = proc; return p != null && p.isAlive(); }

    @Override public void close() {
        running = false; state = State.STOPPED;
        var p = proc; if (p != null) p.destroyForcibly();
    }

    /** Команда по умолчанию: тот же JVM запускает EchoService как «внешний» сервис. */
    static List<String> defaultCommand() {
        String javaBin = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(javaBin, "--enable-preview", "-cp", System.getProperty("java.class.path"), "dev.organism.EchoService", "{port}");
    }
}
