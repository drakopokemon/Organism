package dev.organism;

import java.util.*;

/**
 * Слой данных. Ключевое пространство разбито на шарды; у каждого шарда R реплик (StorageCell) в РАЗНЫХ зонах.
 * Репликация полусинхронная: запись подтверждена, когда она на лидере и на одном фолловере; остальные догоняют
 * с лагом (eventual consistency). Смерть узла = потеря диска. Ре-репликацию запускают ImmuneCell-ы узлов
 * ПО СВОЕМУ gossip-представлению (с задержкой обнаружения), а не центральный контроллер.
 */
final class Storage {
    static final int SHARD_MB = 128, COPY_TICKS = 4, MAX_COPIES_PER_TICK = 4;
    static final double WRITE_RATIO = 0.05;   // доля трафика, превращающаяся в записи

    static final class Shard {
        final int id; long version; double wacc; boolean lost;
        final TreeMap<String, StorageCell> replicas = new TreeMap<>();
        Shard(int id) { this.id = id; }
    }
    record Stats(int shards, int healthy, int degraded, int lost, int copying, double lag, long writesLost, long repaired, String map) {}

    final Substrate sub; final Genome genome; final int replication;
    final TreeMap<Integer, Shard> shards = new TreeMap<>();
    long writesLost, repaired; private int copies;

    Storage(Substrate sub, Genome genome) {
        this.sub = sub; this.genome = genome;
        var cfg = genome.storage(); this.replication = cfg.replication();
        var byZone = new TreeMap<String, List<String>>();
        for (var n : sub.nodes.values())
            if (genome.zoneAllowed(n.spec.zone())) byZone.computeIfAbsent(n.spec.zone(), z -> new ArrayList<>()).add(n.spec.id());
        var zones = new ArrayList<>(byZone.keySet()); int z = zones.size();
        for (int i = 0; i < cfg.shards(); i++) {
            var sh = new Shard(i); shards.put(i, sh);
            for (int k = 0; k < replication; k++) {
                boolean placed = false;
                for (int zz = 0; zz < z && !placed; zz++) {
                    var zl = byZone.get(zones.get((i + k + zz) % z));
                    for (int j = 0; j < zl.size() && !placed; j++) {
                        String n = zl.get((i / z + k / z + j) % zl.size());
                        if (!sh.replicas.containsKey(n)) { add(sh, n, 0, false, 0); placed = true; }
                    }
                }
            }
        }
    }

    private StorageCell add(Shard sh, String node, long version, boolean syncing, long readyAt) {
        var c = new StorageCell(sh.id, node, version, syncing, readyAt);
        sh.replicas.put(node, c); sub.nodes.get(node).store.add(c);
        return c;
    }
    private boolean alive(String node) { return sub.nodes.get(node).alive; }
    private void drop(Shard sh, StorageCell r) { sh.replicas.remove(r.node); sub.nodes.get(r.node).store.remove(r); }

    /** Физика данных за тик: запись, догоняющая репликация, завершение копирований, сборка мусора. */
    void step(long t, double lambda, List<Event> ev) {
        copies = 0;
        for (var sh : shards.values()) {
            if (sh.lost) continue;
            var ready = new ArrayList<StorageCell>();
            for (var r : sh.replicas.values()) if (alive(r.node) && !r.syncing) ready.add(r);
            if (ready.isEmpty()) continue;
            for (var r : new ArrayList<>(sh.replicas.values()))
                if (r.syncing && alive(r.node) && t >= r.readyAt) {
                    r.syncing = false; r.version = sh.version; ready.add(r); repaired++;
                    ev.add(Event.of(t, "REPLICA_READY", r.id(), "shard", "s" + sh.id));
                }
            sh.wacc += lambda * WRITE_RATIO / shards.size();
            long w = (long) sh.wacc; sh.wacc -= w; sh.version += w;
            for (int i = 0; i < ready.size(); i++) {      // лидер + 1 фолловер синхронно, остальные с лагом
                var r = ready.get(i);
                if (i < 2) r.version = sh.version; else r.version += (long) Math.ceil((sh.version - r.version) * 0.5);
            }
            if (ready.size() >= replication)               // здоровый шард: чистим реплики на мёртвых узлах
                for (var r : new ArrayList<>(sh.replicas.values())) if (!alive(r.node)) drop(sh, r);
        }
    }

    /** Узел умер: диск потерян. Шард теряется, только если не осталось ни одной готовой живой реплики. */
    void nodeDown(String node, long t, List<Event> ev) {
        sub.nodes.get(node).store.clear();
        for (var sh : shards.values()) {
            if (sh.lost) continue;
            var mine = sh.replicas.get(node); if (mine == null) continue;
            if (mine.syncing) sh.replicas.remove(node);   // копирование оборвалось
            long best = -1; boolean any = false;
            for (var r : sh.replicas.values()) if (alive(r.node) && !r.syncing) { any = true; best = Math.max(best, r.version); }
            if (!any) {
                sh.lost = true;
                for (var r : sh.replicas.values()) sub.nodes.get(r.node).store.remove(r);
                sh.replicas.clear();
                ev.add(Event.of(t, "DATA_LOST", "s" + sh.id, "version", sh.version));
            } else if (best < sh.version) {
                writesLost += sh.version - best;
                ev.add(Event.of(t, "WRITES_LOST", "s" + sh.id, "writes", sh.version - best));
                sh.version = best;
            }
        }
    }

    /** Узел вернулся с ПУСТЫМ диском: старые реплики недействительны. */
    void nodeRevived(String node) {
        for (var sh : shards.values()) sh.replicas.remove(node);
    }

    /** Вызывает ImmuneCell узла: решает ПО ЛОКАЛЬНОМУ представлению, нужна ли ре-репликация. */
    void inspect(String node, Substrate.NodeState me, long t, List<Intent> out) {
        int budget = 2;
        for (var r : new ArrayList<>(me.store)) {
            if (budget == 0) break;
            var sh = shards.get(r.shard);
            if (r.syncing || sh == null || sh.lost) continue;
            int live = 0; String leader = null;
            for (var x : sh.replicas.values()) if (me.isUp(x.node, t)) {
                live++;
                if (!x.syncing && (leader == null || x.node.compareTo(leader) < 0)) leader = x.node;
            }
            if (live >= replication || !node.equals(leader)) continue;
            String target = pickTarget(me, sh, t);
            if (target != null) { out.add(new Intent.Replicate(sh.id, node, target)); budget--; }
        }
    }

    private String pickTarget(Substrate.NodeState me, Shard sh, long t) {
        var used = new HashSet<String>();
        for (var r : sh.replicas.values()) if (me.isUp(r.node, t)) used.add(sub.nodes.get(r.node).spec.zone());
        String best = null; double bs = -1e9;
        for (var s : me.view.values()) {
            var a = s.a();
            if (!me.isUp(a.id(), t) || !genome.zoneAllowed(a.zone()) || a.freeDisk() < SHARD_MB || sh.replicas.containsKey(a.id())) continue;
            double sc = (used.contains(a.zone()) ? 0 : 10) + a.freeDisk() / (double) SHARD_MB * 0.1;   // разнообразие зон важнее
            if (sc > bs) { bs = sc; best = a.id(); }
        }
        return best;
    }

    void replicate(Intent.Replicate rp, long t, List<Event> ev) {
        var sh = shards.get(rp.shard());
        if (sh == null || sh.lost || copies >= MAX_COPIES_PER_TICK) return;
        var tgt = sub.nodes.get(rp.to()); var src = sub.nodes.get(rp.from());
        var sr = sh.replicas.get(rp.from());
        if (!tgt.alive || !src.alive || sr == null || sr.syncing || sh.replicas.containsKey(rp.to())) return;
        if (!genome.zoneAllowed(tgt.spec.zone()) || sub.freeDisk(tgt) < SHARD_MB) return;
        int aliveCnt = 0; for (var r : sh.replicas.values()) if (alive(r.node)) aliveCnt++;
        if (aliveCnt >= replication) return;           // другой иммунный клеточный «опекун» уже починил
        add(sh, rp.to(), 0, true, t + COPY_TICKS); copies++;
        ev.add(Event.of(t, "REPLICATE", "s" + sh.id, "from", rp.from(), "to", rp.to()));
    }

    Stats stats() {
        int healthy = 0, degraded = 0, lost = 0, copying = 0; double lag = 0; int lagN = 0;
        var map = new StringBuilder();
        for (var sh : shards.values()) {
            if (sh.lost) { lost++; map.append('x'); continue; }
            int ready = 0; boolean syncing = false;
            for (var r : sh.replicas.values()) if (alive(r.node)) {
                if (r.syncing) { syncing = true; copying++; } else { ready++; lag += sh.version - r.version; lagN++; }
            }
            if (ready >= replication) { healthy++; map.append('h'); }
            else { degraded++; map.append(syncing ? 'r' : 'd'); }
        }
        return new Stats(shards.size(), healthy, degraded, lost, copying, lagN == 0 ? 0 : lag / lagN, writesLost, repaired, map.toString());
    }
}
