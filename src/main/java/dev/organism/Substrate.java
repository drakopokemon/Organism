package dev.organism;

import java.util.*;

/**
 * Среда: узлы + децентрализованное обнаружение (push-pull gossip с heartbeat-ами, SWIM-подобное
 * подозрение по таймауту). Центрального планировщика нет: клетка видит только ЛОКАЛЬНОЕ представление
 * своего узла о кластере и решает, куда «почковаться», сама.
 */
public final class Substrate {
    public record Node(String id, String zone, int cores, int memMb, double cost) {}
    record Announce(String id, String zone, int cores, int freeMem, int freeDisk, int cells, double cost, long hb) {}
    record Seen(Announce a, long tick) {}
    /** Спора: геном клетки + где лежат её копии. Переживает смерть узла. */
    public record Spore(String cellId, Phenotype genes, String home, List<String> hosts) {}

    static final int SUSPECT_AFTER = 6, FANOUT = 2, DISK_MB = 2048;

    public static final class NodeState {
        final Node spec; boolean alive = true; long hb;
        final TreeMap<String, Seen> view = new TreeMap<>();
        final List<ProcessingCell> cells = new ArrayList<>();
        volatile double headroom;   // свободная RAM на начало тика; клетки делят её поровну (нет гонок и переподписки)
        final List<StorageCell> store = new ArrayList<>();   // реплики шардов на диске узла
        final SplittableRandom rng;
        NodeState(Node spec, SplittableRandom rng) { this.spec = spec; this.rng = rng; }
        boolean isUp(String id, long now) { var s = view.get(id); return s != null && now - s.tick() <= SUSPECT_AFTER; }
    }

    final TreeMap<String, NodeState> nodes = new TreeMap<>();
    final TreeMap<String, Spore> spores = new TreeMap<>();

    Substrate(List<Node> specs, long seed) {
        int i = 0;
        for (var n : specs) nodes.put(n.id(), new NodeState(n, new SplittableRandom(seed * 7919 + i++)));
        bootstrap(0);
    }

    private void bootstrap(long t) {
        for (var n : nodes.values()) for (var m : nodes.values())
            n.view.put(m.spec.id(), new Seen(announce(m), t));
    }

    int freeMem(NodeState n) {
        double used = 0; for (var c : n.cells) used += World.memMb(c.genes);
        return (int) (n.spec.memMb() - used);
    }
    int freeDisk(NodeState n) { return DISK_MB - n.store.size() * Storage.SHARD_MB; }
    double activeCost() {
        double s = 0; for (var n : nodes.values()) if (n.alive && !n.cells.isEmpty()) s += n.spec.cost(); return s;
    }
    private Announce announce(NodeState n) {
        return new Announce(n.spec.id(), n.spec.zone(), n.spec.cores(), freeMem(n), freeDisk(n), n.cells.size(), n.spec.cost(), n.hb);
    }

    void gossip(long t) {
        var alive = nodes.values().stream().filter(n -> n.alive).toList();
        for (var n : alive) { n.hb++; n.view.put(n.spec.id(), new Seen(announce(n), t)); }
        for (var n : alive) {
            var keys = new ArrayList<>(n.view.keySet());
            keys.remove(n.spec.id());
            for (int k = 0; k < FANOUT && !keys.isEmpty(); k++) {
                var peer = nodes.get(keys.remove(n.rng.nextInt(keys.size())));
                if (peer.alive) { merge(n, peer, t); merge(peer, n, t); }   // мёртвый пир молчит
            }
        }
    }
    private void merge(NodeState into, NodeState from, long t) {
        for (var e : from.view.entrySet()) {
            var cur = into.view.get(e.getKey());
            if (cur == null || e.getValue().a().hb() > cur.a().hb()) into.view.put(e.getKey(), new Seen(e.getValue().a(), t));
        }
    }

    void kill(String id) { nodes.get(id).alive = false; }
    void revive(String id, long t) {
        var n = nodes.get(id); n.alive = true; n.view.clear();
        for (var m : nodes.values()) n.view.put(m.spec.id(), new Seen(announce(m), t));
    }

    /** Выбор узла по ЛОКАЛЬНОМУ представлению: свободный CPU, цена, зоны и бюджет из генома. */
    Optional<String> pickNode(NodeState me, Genome g, long t, Phenotype p) {
        var up = me.view.values().stream().filter(s -> me.isUp(s.a().id(), t)).map(Seen::a).toList();
        double projected = 0; for (var a : up) if (a.cells() > 0) projected += a.cost();
        String best = null; double bestScore = -1e9;
        for (var a : up) {
            if (!g.zoneAllowed(a.zone()) || a.freeMem() < World.memMb(p)) continue;
            double extra = a.cells() == 0 ? a.cost() : 0;
            if (projected + extra > g.maxCost()) continue;
            double score = Math.min(a.cores() / (a.cells() + 1.0), 1.5) - 3 * extra;
            if (score > bestScore) { bestScore = score; best = a.id(); }
        }
        return Optional.ofNullable(best);
    }
}
