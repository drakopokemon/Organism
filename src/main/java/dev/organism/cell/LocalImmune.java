package dev.organism.cell;

import java.util.*;

/** Иммунитет узла: карантин -> release или kill+respawn. Чистая логика. */
public final class LocalImmune {
    public interface Factory {
        void kill(CellCtl c);
        CellCtl respawn();
    }
    private final Factory fx;
    private final Map<String, Integer> bad = new HashMap<>();
    private final Map<String, Integer> ok = new HashMap<>();
    private final Map<String, Integer> quar = new HashMap<>();
    private final List<String> log = new ArrayList<>();

    public LocalImmune(Factory fx) { this.fx = fx; }
    public List<String> events() { return log; }

    public void tick(long t, List<CellCtl> cells, double clusterMedian) {
        double sick = Math.max(3 * clusterMedian, 150);
        double well = Math.max(2 * clusterMedian, 100);
        for (var c : new ArrayList<>(cells)) {
            boolean isSick = c.p99() > sick && c.served() > 20;
            if (!c.quarantined()) {
                bad.merge(c.id(), isSick ? 1 : 0, (o, n) -> n == 1 ? o + 1 : 0);
                if (bad.getOrDefault(c.id(), 0) >= 3) {
                    c.quarantine(true);
                    quar.put(c.id(), 0);
                    log.add(t + " QUARANTINE " + c.id());
                }
            } else {
                boolean isWell = c.p99() < well;
                ok.merge(c.id(), isWell ? 1 : 0, (o, n) -> n == 1 ? o + 1 : 0);
                if (ok.getOrDefault(c.id(), 0) >= 3) {
                    c.quarantine(false);
                    bad.put(c.id(), 0); ok.put(c.id(), 0);
                    log.add(t + " RELEASE " + c.id());
                } else if (quar.merge(c.id(), 1, Integer::sum) >= 8) {
                    log.add(t + " IMMUNE_KILL " + c.id());
                    fx.kill(c);
                    var r = fx.respawn();
                    log.add(t + " IMMUNE_RESPAWN " + r.id());
                    bad.remove(c.id()); ok.remove(c.id()); quar.remove(c.id());
                }
            }
        }
    }
}
