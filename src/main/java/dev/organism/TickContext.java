package dev.organism;

/** Контекст тика — передаётся клеткам через ScopedValue (Java 25: final), а не ThreadLocal. */
record TickContext(long tick, Genome genome, Substrate sub, Gate gate, boolean evolve,
                   double clusterP99, double clusterAvail) {}
