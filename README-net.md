# ORGANISM M3 - distributed storage + chaos recovery

M1: SWIM-lite membership. M2: cells + evolution + immune. M3: distributed storage layer.

## Architecture v3

    TissueKernel (one process per node)
     +-- Membership      (SWIM-lite: ping/ping-req/suspect/dead, transitive facts, BudRequest)
     +-- CellHost        (cell lifecycle + evolution + immune)
     |   +-- Cell[]      (HTTP workers on Virtual Threads, genes physically change behavior)
     |   +-- Evolver     ((1+1)-ES, canary, stat threshold, rollback, cooldown)
     |   +-- LocalImmune (quarantine -> release or kill+respawn)
     +-- StorageHost     (24 shards, R=3 replication, append-log)
     |   +-- StorageCell (one replica per shard per node)
     +-- admin HTTP :8101 (/cells, /health)
     +-- gossip UDP :7100

## What's new in M3

### Storage layer
- 24 shards, R=3 replication factor
- Append-log per shard (in-memory, real data)
- Write: leader receives write, replicates to 1 follower sync, others async
- Node failure: other nodes detect via gossip, re-replicate lost shards
- `StorageSelfTest`: deterministic checks (replication, multi-write sync, determinism)

### Chaos testing
- `bench/chaos.sh`: 3 kernels + LoadGen + `kill -9` on one kernel mid-run
- Verifies: errRate < 15%, p99 < target, recovery events (QUARANTINE/BUD/IMMUNE)
- Exit code 0 = pass, 1 = fail

### GraalVM native image
- `mvn -Pnative package` → `target/organism` (simulation main)
- `mvn -Pnative-kernel package` → `target/tissue-kernel` (real kernel)
- Standalone binaries, no JVM required, ~5-100ms startup, ~40-120MB idle memory

## Cluster launch (3 terminals, JDK 21+)

    javac -d out $(find src/main/java -name '*.java')
    java -cp out dev.organism.net.TissueKernel --port 7101 --cells-port 8101 --seeds 127.0.0.1:7102,127.0.0.1:7103 &
    java -cp out dev.organism.net.TissueKernel --port 7102 --cells-port 8102 --seeds 127.0.0.1:7101 &
    java -cp out dev.organism.net.TissueKernel --port 7103 --cells-port 8103 --seeds 127.0.0.1:7101 &
    # load:
    java -cp out dev.organism.cell.LoadGen --seeds 127.0.0.1:8101,127.0.0.1:8102,127.0.0.1:8103 --rps 200 --seconds 60 --target-p99 600

## Chaos test

    ./bench/chaos.sh

Expected: 3 kernels start, LoadGen runs 60 sec with spike at 40%, one kernel killed at 20 sec, remaining 2 recover (QUARANTINE/BUD events), LoadGen finishes with errRate < 0.15 and p99 < 600ms.

## Unit tests (CI)

- `NetSelfTest`: 8 checks of SWIM-lite (incl. partition+heal, determinism, transitive facts, ReplicateRequest roundtrip)
- `CellSelfTest`: 5 checks of evolution+immune on stubs
- `StorageSelfTest`: 4 checks of storage (replication, multi-write sync, determinism, 24 shards)
- `Main --selftest`: 13 checks of simulation (genome invariants, data on node failures, symbiont)

## M3 boundaries

- No mTLS on wire - trusted network only (M4).
- Storage is in-memory (no disk persistence) - M4.
- No full chaos test in CI (detached JVM processes) - run locally with `bench/chaos.sh`.
- GraalVM native image profile exists but not built in CI (requires GraalVM setup).

## Next: M4

- mTLS or shared secret on wire
- Disk persistence for Storage (WAL + snapshots)
- Chaos test in CI (Docker containers with network partition simulation)
- Video demo: 90 sec showing real cluster, kill -9, recovery, evolution
