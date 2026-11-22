package dev.organism.storage;

/**
 * StorageCell — одна реплика одного шарда на узле.
 * version: последняя применённая запись.
 * syncing: идёт копирование с живой реплики (читать/писать ещё нельзя).
 * readyAt: тик когда копирование завершится.
 */
public final class StorageCell {
    public final int shard;
    public final String node;
    public volatile long version;
    public volatile boolean syncing;
    public volatile long readyAt;

    public StorageCell(int shard, String node, long version, boolean syncing, long readyAt) {
        this.shard = shard;
        this.node = node;
        this.version = version;
        this.syncing = syncing;
        this.readyAt = readyAt;
    }

    public String id() { return "s" + shard + "@" + node; }
}
