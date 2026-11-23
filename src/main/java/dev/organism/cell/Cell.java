package dev.organism.cell;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/** Настоящая Processing-клетка: HTTP-воркер, гены физически меняют поведение. */
public final class Cell implements AutoCloseable, CellCtl {
    public record Genes(int pool, int queueLimit, int cacheMb, int timeoutMs) {
        public Genes with(String g, int v) {
            return switch (g) {
                case "pool" -> new Genes(v, queueLimit, cacheMb, timeoutMs);
                case "queueLimit" -> new Genes(pool, v, cacheMb, timeoutMs);
                case "cacheMb" -> new Genes(pool, queueLimit, v, timeoutMs);
                case "timeoutMs" -> new Genes(pool, queueLimit, cacheMb, v);
                default -> this;
            };
        }
        public int get(String g) {
            return switch (g) {
                case "pool" -> pool; case "queueLimit" -> queueLimit;
                case "cacheMb" -> cacheMb; case "timeoutMs" -> timeoutMs;
                default -> throw new IllegalArgumentException(g);
            };
        }
    }

    public final String id;
    public final Genes genes;
    public final boolean canary;
    public volatile int weight = 1;
    private volatile boolean quarantined;
    public volatile long served, dropped, errored;
    public volatile long drained;
    private final java.util.concurrent.atomic.AtomicInteger inflight = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean draining;

    private final HttpServer srv;
    private final ExecutorService exec;
    private final Semaphore queue, workers;
    private final LinkedHashMap<Integer, byte[]> cache;
    private final int cacheEntries;
    private final long[] lat = new long[2048];
    private int latN, latW;

    public Cell(String id, Genes g, boolean canary) throws IOException {
        this.id = id; this.genes = g; this.canary = canary;
        this.queue = new Semaphore(g.queueLimit());
        this.workers = new Semaphore(g.pool());
        this.cacheEntries = Math.max(16, g.cacheMb() * 16);
        this.cache = new LinkedHashMap<>(16, 0.75f, true);
        this.exec = Executors.newVirtualThreadPerTaskExecutor();
        this.srv = HttpServer.create(new InetSocketAddress(0), 512);
        srv.setExecutor(exec);
        srv.createContext("/work", ex -> {
            long t0 = System.nanoTime();
            if (draining) { drained++; reply(ex, 503, null); return; }
            if (quarantined || !queue.tryAcquire()) { dropped++; reply(ex, 503, null); return; }
            inflight.incrementAndGet();
            try {
                workers.acquire();
                try {
                    int key = ThreadLocalRandom.current().nextInt(4096);
                    boolean hit;
                    synchronized (cache) {
                        hit = cache.containsKey(key);
                        if (!hit) {
                            cache.put(key, new byte[64]);
                            while (cache.size() > cacheEntries) cache.remove(cache.keySet().iterator().next());
                        }
                    }
                    LockSupport.parkNanos(hit ? 1_000_000L : 18_000_000L);
                    long el = (System.nanoTime() - t0) / 1_000_000;
                    recordLat(el);
                    served++;
                    if (el > genes.timeoutMs()) { errored++; reply(ex, 504, null); }
                    else reply(ex, 200, new byte[]{1});
                } finally { workers.release(); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reply(ex, 500, null);
            } finally { inflight.decrementAndGet(); queue.release(); }
        });
        srv.createContext("/health", ex -> reply(ex, 200, new byte[]{1}));
        srv.start();
    }

    public int port() { return srv.getAddress().getPort(); }
    @Override public String id() { return id; }
    @Override public boolean quarantined() { return quarantined; }
    @Override public void quarantine(boolean q) { quarantined = q; weight = q ? 0 : 1; }
    @Override public long served() { return served; }
    @Override public double dropRate() { long t = served + dropped; return t == 0 ? 0 : dropped / (double) t; }

    private void recordLat(long ms) { lat[latW++ & 2047] = ms; latN = Math.min(latN + 1, 2048); }
    @Override public synchronized double p99() {
        if (latN == 0) return 0;
        long[] c = new long[latN];
        System.arraycopy(lat, 0, c, 0, latN);
        Arrays.sort(c);
        return c[(int) ((latN - 1) * 0.99)];
    }

    public boolean draining() { return draining; }
    public int inflight() { return inflight.get(); }

    /**
     * Graceful drain (6.2): новые запросы получают 503 (счётчик drained, не dropped),
     * in-flight дорабатывают до graceMs, затем сервер закрывается. Возвращает фактические ms дренажа.
     * Аддитивно: без вызова drain() поведение клетки идентично прежнему.
     */
    public long drain(long graceMs) {
        draining = true; weight = 0;
        long t0 = System.nanoTime();
        long deadline = t0 + Math.max(0, graceMs) * 1_000_000L;
        while (inflight.get() > 0 && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000L);
        close();
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static void reply(HttpExchange ex, int code, byte[] body) throws IOException {
        if (body == null) ex.sendResponseHeaders(code, -1);
        else { ex.sendResponseHeaders(code, body.length); ex.getResponseBody().write(body); }
        ex.close();
    }

    @Override public void close() { srv.stop(0); exec.shutdownNow(); }
}
