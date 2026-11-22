# ORGANISM M2 - evolution + immune on real network

M1 added SWIM-lite membership. M2 adds processing cells with real evolution.

## Architecture v2

    TissueKernel (one process per node)
     +-- Membership   (SWIM-lite: ping/ping-req/suspect/dead, transitive facts, BudRequest)
     +-- CellHost     (cell lifecycle + evolution + immune)
     |   +-- Cell[]   (HTTP workers on Virtual Threads, genes physically change behavior)
     |   +-- Evolver  ((1+1)-ES, canary, stat threshold, rollback, cooldown)
     |   +-- LocalImmune (quarantine -> release or kill+respawn)
     +-- admin HTTP :8101 (/cells, /health)
     +-- gossip UDP :7100

## Cluster launch (3 terminals, JDK 21+)

    javac -d out $(find src/main/java -name '*.java')
    java -cp out dev.organism.net.TissueKernel --port 7101 --cells-port 8101 --seeds 127.0.0.1:7102,127.0.0.1:7103 &
    java -cp out dev.organism.net.TissueKernel --port 7102 --cells-port 8102 --seeds 127.0.0.1:7101 &
    java -cp out dev.organism.net.TissueKernel --port 7103 --cells-port 8103 --seeds 127.0.0.1:7101 &
    # load:
    java -cp out dev.organism.cell.LoadGen --seeds 127.0.0.1:8101,127.0.0.1:8102,127.0.0.1:8103 --rps 200 --seconds 60 --target-p99 600

## What happens

1. Each kernel starts with 2 cells (pool=2, queue=512, cache=64MB, timeout=500ms).
2. Evolution tries to mutate genes in turn (pool, queueLimit, cacheMb, timeoutMs),
   spawns a canary cell, compares against control group for 10 ticks.
3. If canary beats control + ACCEPT threshold - mutation is fixed on all control cells.
4. Immune watches each cell's p99: 3 consecutive "sick" ticks -> quarantine (weight=0,
   LoadGen stops sending traffic), 8 ticks without recovery -> kill+respawn.
5. On dropRate > 2%: if local RAM is free -> bud locally, otherwise BudRequest over
   gossip to the node with max freeMem.

## Unit tests (CI)

- NetSelfTest: 8 checks of SWIM-lite (incl. partition+heal, determinism, transitive facts)
- CellSelfTest: 5 checks of evolution+immune on stubs (adopt/reject mutations,
  determinism, quarantine/kill/respawn)
- Main --selftest: 13 checks of simulation (genome invariants, data on node failures, symbiont)

## M2 boundaries

- No real Storage cells yet (M3).
- No mTLS on wire - trusted network only (M3).
- No GraalVM native image (M3).
- LoadGen chaos test not in CI (detached JVM processes) - run locally.
