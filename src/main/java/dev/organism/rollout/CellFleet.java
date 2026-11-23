package dev.organism.rollout;

import dev.organism.cell.Cell;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Реальный флот in-process клеток для RolloutManager: поколения иммутабельны (новый параметр = новая клетка),
 * веса кормят взвешенный роутер (LoadGen читает /cells), старое поколение уходит через graceful drain.
 * CellHost/StorageHost/Wire НЕ тронуты: это отдельный opt-in путь раскатки.
 */
public final class CellFleet implements RolloutManager.Fleet, AutoCloseable {
    private final String prefix;
    private final Cell.Genes base;
    private final int fleetSize;
    private final List<Cell> oldGen = new CopyOnWriteArrayList<>();
    private final List<Cell> newGen = new CopyOnWriteArrayList<>();
    private volatile Cell control;
    private int seq;

    public CellFleet(String prefix, Cell.Genes base, int initial) {
        this.prefix = prefix;
        this.base = base;
        this.fleetSize = Math.max(1, initial);
        for (int i = 0; i < this.fleetSize; i++) oldGen.add(cell(base, false));
        control = oldGen.get(0);
    }

    private Cell cell(Cell.Genes g, boolean canary) {
        try { return new Cell(prefix + "-" + (seq++) + (canary ? "-canary" : ""), g, canary); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** poolSize (имя из генома) -> pool (имя гена клетки). Неизвестные ключи игнорируются. */
    public static Cell.Genes genes(Cell.Genes base, Map<String, Double> p) {
        Cell.Genes g = base;
        for (var e : p.entrySet()) {
            String gene = e.getKey().equals("poolSize") ? "pool" : e.getKey();
            int v = (int) Math.round(e.getValue());
            switch (gene) {
                case "pool", "queueLimit", "cacheMb", "timeoutMs" -> g = g.with(gene, v);
                default -> { }
            }
        }
        return g;
    }

    @Override public int size() { return oldGen.size() + newGen.size(); }

    @Override public void spawnCanary(Map<String, Double> params) { newGen.add(cell(genes(base, params), true)); }

    @Override public void weights(double newGenPct, double controlPct) {
        List<Cell> ctrl = new ArrayList<>();
        List<Cell> rest = new ArrayList<>();
        for (Cell c : oldGen) { if (c == control && controlPct > 0) ctrl.add(c); else rest.add(c); }
        int[] w = new TrafficSplit(newGenPct, controlPct).weights(newGen.size(), ctrl.size(), rest.size());
        int i = 0;
        for (Cell c : newGen) setWeight(c, w[i++]);
        for (Cell c : ctrl) setWeight(c, w[i++]);
        for (Cell c : rest) setWeight(c, w[i++]);
    }

    private static void setWeight(Cell c, int w) { if (!c.quarantined()) c.weight = Math.max(0, w); }

    @Override public double canaryP99() { double p = 0; for (Cell c : newGen) p = Math.max(p, c.p99()); return p; }
    @Override public double controlP99() { Cell c = control; return c == null ? 0 : c.p99(); }
    @Override public double canaryDropRate() { double d = 0; for (Cell c : newGen) d = Math.max(d, c.dropRate()); return d; }

    @Override public void promote(Map<String, Double> params, double sharePct) {
        int target = Math.max(1, (int) Math.round(sharePct / 100.0 * fleetSize));
        Cell.Genes g = genes(base, params);
        while (newGen.size() < target) newGen.add(cell(g, false));
    }

    @Override public void drainOld(long graceMs) {
        for (Cell c : oldGen) c.drain(graceMs);
        oldGen.clear();
        control = null;
    }

    @Override public void killCanary() {
        for (Cell c : newGen) c.drain(0);
        newGen.clear();
    }

    @Override public void rollback() {
        for (Cell c : newGen) c.weight = 0;
        for (Cell c : oldGen) if (!c.quarantined()) c.weight = 1;
    }

    public List<Cell> oldGeneration() { return List.copyOf(oldGen); }
    public List<Cell> newGeneration() { return List.copyOf(newGen); }
    public Cell controlCell() { return control; }
    public int fleetSize() { return fleetSize; }

    @Override public void close() {
        for (Cell c : newGen) c.close();
        for (Cell c : oldGen) c.close();
        newGen.clear();
        oldGen.clear();
    }
}
