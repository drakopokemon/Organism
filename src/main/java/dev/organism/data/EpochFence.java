package dev.organism.data;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Столп 4: Fencing Tokens (эпохи) НАД Wire — Wire sealed заморожен, эпохи живут в обёртках/env, не в протоколе.
 * Stateful handoff: allocate() -> N+1, новый агент стартует с ORGANISM_EPOCH=N+1 (см. NativeSupervisor),
 * старый агент с эпохой N отвергается ДО записи. lock(resource) — общий монитор handoff'а и записи:
 * TOCTOU-окно закрыто, два писателя одновременно невозможны (split-brain исключён на уровне storage-обёртки).
 */
public final class EpochFence {
    public static final class StaleEpochException extends RuntimeException {
        public final long expected, got;
        public StaleEpochException(String resource, long expected, long got) {
            super("stale epoch on " + resource + ": current=" + expected + " writer=" + got);
            this.expected = expected;
            this.got = got;
        }
    }

    private final ConcurrentHashMap<String, Long> epochs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public long allocate(String resource) { return epochs.merge(resource, 1L, Long::sum); }
    public long current(String resource) { return epochs.getOrDefault(resource, 0L); }
    public Object lock(String resource) { return locks.computeIfAbsent(resource, k -> new Object()); }
    public boolean check(String resource, long epoch) { return current(resource) == epoch; }

    public void assertCurrent(String resource, long epoch) {
        long c = current(resource);
        if (c != epoch) throw new StaleEpochException(resource, c, epoch);
    }
}
