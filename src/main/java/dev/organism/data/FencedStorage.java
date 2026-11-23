package dev.organism.data;

import dev.organism.storage.StorageHost;

/**
 * Opt-in обёртка над ЗАМОРОЖЕННЫМ StorageHost: запись только с текущей эпохой.
 * StorageHost API не изменён; без обёртки — legacy-поведение. Handoff должен идти под fence.lock(resource).
 */
public final class FencedStorage {
    private final StorageHost delegate;
    private final EpochFence fence;
    private final String resource;
    private final long epoch;

    public FencedStorage(StorageHost delegate, EpochFence fence, String resource, long epoch) {
        this.delegate = delegate;
        this.fence = fence;
        this.resource = resource;
        this.epoch = epoch;
    }

    public long epoch() { return epoch; }

    public long write(int shard, byte[] data) {
        synchronized (fence.lock(resource)) {
            fence.assertCurrent(resource, epoch);
            return delegate.write(shard, data);
        }
    }

    public long version(int shard) { return delegate.version(shard); }
}
