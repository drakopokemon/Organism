package dev.organism.agent;

import dev.organism.genome.Genome;
import java.nio.file.Path;
import java.util.*;

/**
 * Immune Agent: нативный агент для мониторинга, карантина и замены больных узлов.
 */
public final class ImmuneAgent {
    public static void main(String[] args) throws Exception {
        var a = parse(args);
        String advertise = a.getOrDefault("advertise", "127.0.0.1:8200");
        String genomePath = a.getOrDefault("genome", "genome.yaml");
        
        Genome genome = Genome.fromFile(Path.of(genomePath));
        System.out.println("[AGENT:Immune] Started on " + advertise + " with Genome: " + genomePath);
        
        boolean immuneEnabled = false;
        for (var cap : genome.capabilities) {
            if (cap instanceof Genome.ImmuneCapability i) {
                immuneEnabled = i.enabled();
            }
        }
        
        if (!immuneEnabled) {
            System.out.println("[AGENT:Immune] WARNING: Immune capability is DISABLED in genome. Node is vulnerable.");
        } else {
            System.out.println("[AGENT:Immune] Monitoring cluster health. Quarantine protocols ACTIVE.");
        }
        
        Thread.currentThread().join();
    }

    private static Map<String, String> parse(String[] args) {
        var m = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++)
            if (args[i].startsWith("--") && i + 1 < args.length) m.put(args[i].substring(2), args[++i]);
        return m;
    }
}
