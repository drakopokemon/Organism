package dev.organism;

import dev.organism.Genome.*;
import java.util.List;
import java.util.Set;

/** Готовые геномы. */
public final class Genomes {
    private Genomes() {}

    /** Веб-API: p99 < 120 мс, дёшево, данные только в ЕС, не больше $3/ч. */
    public static Genome shopApi() {
        return new Genome("shop-api",
            List.of(new Objective.P99Latency(120, 0.5),
                    new Objective.CostPerMReq(0.50, 0.3),
                    new Objective.Availability(0.99, 0.2)),
            List.of(new Constraint.ForbiddenZone("us-1"),
                    new Constraint.MaxCostPerHour(3.0),
                    new Constraint.MaxMemPerCellMb(640),
                    new Constraint.Replicas(3, 60),
                    new Constraint.Storage(24, 3)),
            Set.of(Capability.values()),
            List.of(new Gene("pool",       1,   8,    1,   2,   Risk.LOW),
                    new Gene("cacheMb",    32,  1024, 32,  64,  Risk.LOW),
                    new Gene("timeoutMs",  100, 2000, 50,  500, Risk.LOW),
                    new Gene("batch",      1,   16,   1,   1,   Risk.LOW),
                    new Gene("queueLimit", 16,  2048, 16,  512, Risk.HIGH)));
    }
}
