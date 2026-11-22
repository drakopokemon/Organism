package dev.organism.evolution;

import dev.organism.genome.Genome;
import java.util.Map;

public final class MutationGuard {
    public boolean validate(Map<String, Double> params, Genome genome) {
        if (params == null || genome == null || genome.mutationPolicy() == null) return false;
        
        Genome.MutationPolicy p = genome.mutationPolicy();
        
        if (params.containsKey("poolSize")) {
            double v = params.get("poolSize");
            if (v < p.poolSizeMin() || v > p.poolSizeMax()) return false;
        }
        if (params.containsKey("queueLimit")) {
            double v = params.get("queueLimit");
            if (v < p.queueLimitMin() || v > p.queueLimitMax()) return false;
        }
        if (params.containsKey("cacheMb")) {
            double v = params.get("cacheMb");
            if (v < p.cacheMbMin() || v > p.cacheMbMax()) return false;
        }
        if (params.containsKey("timeoutMs")) {
            double v = params.get("timeoutMs");
            if (v < p.timeoutMsMin() || v > p.timeoutMsMax()) return false;
        }
        
        return true;
    }
}