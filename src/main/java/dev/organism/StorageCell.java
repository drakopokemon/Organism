package dev.organism;

/** Storage-клетка: одна реплика одного шарда на диске узла (жировая ткань организма). */
public final class StorageCell implements Agent {
    final int shard; final String node;
    long version;          // последняя применённая запись
    boolean syncing;       // идёт копирование с живой реплики — читать/писать ещё нельзя
    long readyAt;

    StorageCell(int shard, String node, long version, boolean syncing, long readyAt) {
        this.shard = shard; this.node = node; this.version = version; this.syncing = syncing; this.readyAt = readyAt;
    }
    public String id() { return "s" + shard + "@" + node; }
}
