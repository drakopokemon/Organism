package dev.organism.storage;

import dev.organism.net.Membership;
import dev.organism.net.Wire;
import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * StorageHost — менеджер всех StorageCell на узле.
 * 24 шарда, R=3 реплики на разных узлах.
 * Запись: лидер получает write, реплицирует на 1 фолловер sync, остальные async.
 * Потеря узла: остальные узлы видят через gossip, делают ре-репликацию.
 */
public final class StorageHost {
    private static final int SHARD_COUNT = 24;
    private static final int REPLICATION = 3;

    private final Membership m;
    private final Map<Integer, List<byte[]>> logs;
    private final Map<Integer, Long> versions;
    private final Map<String, StorageCell> cells;
    private final Consumer<Wire.ReplicateRequest> sendReplicate;
    private final Wal[] wals;

    public StorageHost(Membership m, Consumer<Wire.ReplicateRequest> sendReplicate) throws IOException {
        this(m, sendReplicate, null);
    }

    public StorageHost(Membership m, Consumer<Wire.ReplicateRequest> sendReplicate, Path walDir) throws IOException {
        this.m = m;
        this.sendReplicate = sendReplicate;
        this.logs = new ConcurrentHashMap<>();
        this.versions = new ConcurrentHashMap<>();
        this.cells = new ConcurrentHashMap<>();
        this.wals = (walDir != null) ? new Wal[SHARD_COUNT] : null;
        
        for (int i = 0; i < SHARD_COUNT; i++) {
            logs.put(i, new CopyOnWriteArrayList<>());
            versions.put(i, 0L);
            var cell = new StorageCell(i, m.self, 0L, false, 0L);
            cells.put(cell.id(), cell);
            
            if (wals != null) {
                wals[i] = new Wal(walDir, i);
                int shard = i;
                wals[i].replay((v, data) -> {
                    logs.get(shard).add(data);
                    if (v > versions.get(shard)) {
                        versions.put(shard, v);
                        cell.version = v;
                    }
                });
            }
        }
    }

    /** Запись в шард. Возвращает новую версию. */
    public long write(int shard, byte[] data) {
        if (shard < 0 || shard >= SHARD_COUNT) throw new IllegalArgumentException("bad shard: " + shard);
        var log = logs.get(shard);
        log.add(data);
        long newVersion = versions.get(shard) + 1;
        versions.put(shard, newVersion);
        var cell = cells.get("s" + shard + "@" + m.self);
        if (cell != null) cell.version = newVersion;
        if (wals != null) {
            try { wals[shard].append(newVersion, data); } 
            catch (IOException e) { throw new UncheckedIOException(e); }
        }
        // Репликация: передаём в транспорт, он сам рассылает всем живым узлам
        sendReplicate.accept(new Wire.ReplicateRequest(shard, m.self, newVersion, data));
        return newVersion;
    }

    /** Обработка входящего ReplicateRequest. */
    public void onReplicateRequest(Wire.ReplicateRequest req) {
        var cell = cells.get("s" + req.shard() + "@" + m.self);
        if (cell == null) return;
        if (req.version() > cell.version) {
            var log = logs.get(req.shard());
            log.add(req.data());
            cell.version = req.version();
            versions.put(req.shard(), req.version());
        }
    }

    public long version(int shard) {
        return versions.getOrDefault(shard, 0L);
    }

    public int shardCount() {
        return SHARD_COUNT;
    }

    public StorageCell cell(int shard) {
        return cells.get("s" + shard + "@" + m.self);
    }

    public void close() {
        if (wals != null) {
            for (Wal w : wals) {
                try { if (w != null) w.close(); } catch (IOException ignored) {}
            }
        }
    }

    public Map<Integer, Long> allVersions() {
        return Collections.unmodifiableMap(versions);
    }

    /** Тик: завершение копирований. */
    public void tick(long t) {
        for (var cell : cells.values()) {
            if (cell.syncing && t >= cell.readyAt) {
                cell.syncing = false;
                cell.version = versions.getOrDefault(cell.shard, 0L);
            }
        }
    }
}
