package dev.organism;

import java.util.*;

/**
 * Иммунная клетка — по одной на узел. Видит только локальных соседей + медиану кластера.
 * Карантин -> диагностика -> замена из «стволовой» линии; плюс «опекун» проращивает споры погибших узлов.
 */
public final class ImmuneCell implements Agent {
    final String node;
    final List<Intent> out = new ArrayList<>();
    ImmuneCell(String node) { this.node = node; }
    public String id() { return "immune@" + node; }

    void inspect(Organism o, long t) {
        out.clear();
        var me = o.sub.nodes.get(node);
        if (!me.alive) return;
        double med = o.medianP99;
        for (var c : new ArrayList<>(me.cells)) {
            boolean bad = c.last.p99Ms() > Math.max(3 * med, 150);
            switch (c.state) {
                case ACTIVE -> {
                    c.anomalyTicks = bad ? c.anomalyTicks + 1 : 0;
                    if (c.anomalyTicks >= 3) out.add(new Intent.Quarantine(c));
                }
                case QUARANTINED -> {
                    c.quarTicks++;
                    c.okTicks = c.last.p99Ms() < Math.max(2 * med, 100) ? c.okTicks + 1 : 0;
                    if (c.okTicks >= 3) out.add(new Intent.Release(c));
                    else if (c.quarTicks >= 8) out.add(new Intent.Kill(c, "unrecovered"));
                }
                default -> {}
            }
        }
        for (var s : o.sub.spores.values()) {
            if (o.cells.containsKey(s.cellId()) || me.isUp(s.home(), t) || !s.hosts().contains(node)) continue;
            // детерминированная «выборность» опекуна: минимальный id среди живых (в моём представлении) хостов
            String guardian = s.hosts().stream().filter(h -> me.isUp(h, t)).min(String::compareTo).orElse(null);
            if (node.equals(guardian)) out.add(new Intent.Germinate(s, node));
        }
        if (o.store != null) o.store.inspect(node, me, t, out);   // ре-репликация данных по локальному представлению
    }
}
