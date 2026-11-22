package dev.organism.cell;

import com.sun.net.httpserver.HttpServer;
import dev.organism.net.Membership;
import dev.organism.net.Wire;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Хост клеток узла: жизненный цикл, эволюция, иммунитет, админ-HTTP, почкование. */
public final class CellHost implements AutoCloseable {
    private final List<Cell> cells = new CopyOnWriteArrayList<>();
    private final Membership m;
    private final Evolver evolver;
    private final LocalImmune immune;
    private final HttpServer admin;
    private final BiConsumer<Long, String> journal;
    private final double targetP99;
    private int cellSeq, evolverSeen, immuneSeen;

    public CellHost(Membership m, int httpPort, long seed, double targetP99,
                    BiConsumer<Long, String> journal) throws IOException {
        this.m = m; this.journal = journal; this.targetP99 = targetP99;
        var genes = new Cell.Genes(2, 512, 64, 500);
        this.evolver = new Evolver(genes, seed, new Evolver.Factory() {
            @Override public CellCtl spawn(Cell.Genes g, boolean canary) { var c = newCell(g, canary); cells.add(c); return c; }
            @Override public void kill(CellCtl c) { cells.remove(c); ((Cell) c).close(); }
            @Override public void applyAll(Cell.Genes g) {
                for (var c : new ArrayList<>(cells)) if (!c.canary) {
                    var n = newCell(g, false);
                    cells.add(n); cells.remove(c); c.close();
                }
            }
        }, targetP99);
        this.immune = new LocalImmune(new LocalImmune.Factory() {
            @Override public void kill(CellCtl c) { cells.remove(c); ((Cell) c).close(); }
            @Override public CellCtl respawn() { var c = newCell(evolver.current(), false); cells.add(c); return c; }
        });
        cells.add(newCell(genes, false));
        cells.add(newCell(genes, false));
        this.admin = HttpServer.create(new InetSocketAddress(httpPort), 0);
        admin.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        admin.createContext("/cells", ex -> {
            var sb = new StringBuilder();
            for (var c : cells) if (!c.quarantined())
                sb.append(c.id).append('\t').append(c.port()).append('\t').append(c.weight).append('\n');
            byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/tab-separated-values");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        admin.createContext("/health", ex -> {
            ex.sendResponseHeaders(200, 2); ex.getResponseBody().write(new byte[]{1, 10}); ex.close();
        });
        admin.start();
    }

    private Cell newCell(Cell.Genes g, boolean canary) {
        try {
            return new Cell(m.self + "#c" + (cellSeq++) + (canary ? "-canary" : ""), g, canary);
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public double freeMemMb() {
        var rt = Runtime.getRuntime();
        return (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / 1048576.0;
    }

    public double myP99() {
        double p = 0;
        for (var c : cells) if (!c.canary && !c.quarantined()) p = Math.max(p, c.p99());
        return p;
    }

    public double clusterMedian() {
        var v = new ArrayList<Double>();
        v.add(myP99());
        for (var e : m.view().entrySet()) {
            if (e.getValue() == Wire.Status.ALIVE && !e.getKey().equals(m.self)) {
                var f = m.facts(e.getKey());
                if (f != null && f.p99Ms() > 0) v.add(f.p99Ms());
            }
        }
        Collections.sort(v);
        return v.get(v.size() / 2);
    }

    public void tick(long t) {
        var controls = new ArrayList<CellCtl>();
        for (var c : cells) if (!c.canary && !c.quarantined()) controls.add(c);
        evolver.tick(t, controls);
        immune.tick(t, controls, clusterMedian());
        for (int d = evolverSeen; d < evolver.decisions().size(); d++) {
            var x = evolver.decisions().get(d);
            journal.accept(t, "EVOLVE " + x.gene() + " " + x.from() + "->" + x.to()
                    + (x.adopted() ? " ADOPTED " : " REVERTED ") + x.why());
        }
        evolverSeen = evolver.decisions().size();
        for (int i = immuneSeen; i < immune.events().size(); i++)
            journal.accept(t, immune.events().get(i));
        immuneSeen = immune.events().size();
        double drops = 0;
        for (var c : cells) if (!c.canary) drops = Math.max(drops, c.dropRate());
        if (drops > 0.02 && controls.size() < 8) {
            if (freeMemMb() > 300) {
                var c = newCell(evolver.current(), false);
                cells.add(c);
                journal.accept(t, "BUD_LOCAL cell=" + c.id + " drops=" + drops);
            } else {
                String best = null; double bm = 0;
                for (var e : m.view().entrySet()) {
                    if (e.getValue() != Wire.Status.ALIVE || e.getKey().equals(m.self)) continue;
                    var f = m.facts(e.getKey());
                    if (f != null && f.memFreeMb() > bm) { bm = f.memFreeMb(); best = e.getKey(); }
                }
                if (best != null && bm > 300) m.sendBudReq(best, drops);
            }
        }
        var rt = Runtime.getRuntime();
        m.setFacts(new Wire.NodeFacts(m.self, "local", m.incarnation(), t,
                rt.availableProcessors(), (int) freeMemMb(),
                (int) (new java.io.File(".").getUsableSpace() >> 20), 0.1, cells.size(), myP99()));
    }

    public int cellCount() { return cells.size(); }

    @Override public void close() {
        admin.stop(0);
        for (var c : cells) c.close();
    }
}
