package dev.organism.rollout;

import dev.organism.cell.Cell;
import dev.organism.cell.CellHost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Флот поверх ЗАМОРОЖЕННОГО CellHost (использует только аддитивные методы 6.2).
 * Поколение = набор id клеток; старое поколение уходит через graceful drain, новое рождается иммутабельно.
 * baseline фиксируется в момент старта канарейки -- раскатка не раздувает флот вдвое.
 */
public final class HostFleet implements RolloutManager.Fleet {
    private final CellHost host;
    private final Set<String> newGen = ConcurrentHashMap.newKeySet();
    private volatile Cell.Genes base;
    private volatile Cell.Genes rollbackBase;
    private volatile String controlId = "";
    private volatile int baseline = 1;

    public HostFleet(CellHost host, Cell.Genes base) {
        this.host = host;
        this.base = base;
        this.rollbackBase = base;
    }

    public static Map<String, Double> params(Cell.Genes g) {
        var m = new TreeMap<String, Double>();
        m.put("poolSize", (double) g.pool());
        m.put("queueLimit", (double) g.queueLimit());
        m.put("cacheMb", (double) g.cacheMb());
        m.put("timeoutMs", (double) g.timeoutMs());
        return m;
    }

    public Map<String, Double> currentParams() { return params(base); }
    public int newGenerationSize() { return news().size(); }

    private List<Cell> news() {
        var l = new ArrayList<Cell>();
        for (Cell c : host.cellsView()) if (newGen.contains(c.id)) l.add(c);
        return l;
    }

    private List<Cell> olds() {
        var l = new ArrayList<Cell>();
        for (Cell c : host.cellsView()) if (!newGen.contains(c.id)) l.add(c);
        return l;
    }

    @Override public int size() { return host.cellCount(); }

    @Override public void spawnCanary(Map<String, Double> p) {
        var old = olds();
        baseline = Math.max(1, old.size());
        rollbackBase = base;
        if (controlId.isEmpty() && !old.isEmpty()) controlId = old.get(0).id;
        Cell c = host.spawnCell(CellFleet.genes(base, p), true);
        newGen.add(c.id);
    }

    @Override public void weights(double newGenPct, double controlPct) {
        var news = news();
        var ctrl = new ArrayList<Cell>();
        var rest = new ArrayList<Cell>();
        for (Cell c : olds()) { if (controlPct > 0 && c.id.equals(controlId)) ctrl.add(c); else rest.add(c); }
        int[] w = new TrafficSplit(newGenPct, controlPct).weights(news.size(), ctrl.size(), rest.size());
        int i = 0;
        for (Cell c : news) set(c, w[i++]);
        for (Cell c : ctrl) set(c, w[i++]);
        for (Cell c : rest) set(c, w[i++]);
    }

    private static void set(Cell c, int w) { if (!c.quarantined()) c.weight = Math.max(0, w); }

    @Override public double canaryP99() { double p = 0; for (Cell c : news()) p = Math.max(p, c.p99()); return p; }

    @Override public double controlP99() {
        for (Cell c : olds()) if (c.id.equals(controlId)) return c.p99();
        var o = olds();
        return o.isEmpty() ? 0 : o.get(0).p99();
    }

    @Override public double canaryDropRate() { double d = 0; for (Cell c : news()) d = Math.max(d, c.dropRate()); return d; }

    @Override public void promote(Map<String, Double> p, double sharePct) {
        Cell.Genes g = CellFleet.genes(base, p);
        int target = Math.max(1, (int) Math.round(sharePct / 100.0 * baseline));
        var news = news();
        while (news.size() < target) {
            Cell c = host.spawnCell(g, false);
            newGen.add(c.id);
            news.add(c);
        }
        base = g;
    }

    @Override public void drainOld(long graceMs) {
        for (Cell c : olds()) { c.drain(graceMs); host.removeCell(c); }
        controlId = "";
    }

    @Override public void killCanary() {
        for (Cell c : news()) { c.drain(0); host.removeCell(c); }
        newGen.clear();
    }

    @Override public void rollback() {
        for (Cell c : news()) c.weight = 0;
        for (Cell c : olds()) if (!c.quarantined()) c.weight = 1;
        base = rollbackBase;
    }
}
