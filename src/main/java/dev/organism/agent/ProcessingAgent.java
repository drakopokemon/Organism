package dev.organism.agent;

import dev.organism.genome.Genome;
import dev.organism.net.Membership;
import dev.organism.net.Wire;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Processing Agent: нативный агент для обработки запросов.
 * Запускается как отдельный процесс: java -cp out dev.organism.agent.ProcessingAgent --genome genome.yaml --port 8100 --seeds 127.0.0.1:7100
 */
public final class ProcessingAgent {
    public static void main(String[] args) throws Exception {
        var a = parse(args);
        int port = Integer.parseInt(a.getOrDefault("port", "8100"));
        String advertise = a.getOrDefault("advertise", "127.0.0.1:" + port);
        String genomePath = a.getOrDefault("genome", "genome.yaml");
        
        Genome genome = Genome.fromFile(java.nio.file.Path.of(genomePath));
        System.out.println("[AGENT:Processing] Started on " + advertise + " with Genome: " + genomePath);
        
        // Извлекаем параметры из генома
        int minInstances = 2, maxInstances = 16;
        for (var cap : genome.capabilities) {
            if (cap instanceof Genome.ProcessingCapability p) {
                minInstances = p.minInstances();
                maxInstances = p.maxInstances();
            }
        }
        
        System.out.println("[AGENT:Processing] Policy: instances [" + minInstances + ", " + maxInstances + "]");
        System.out.println("[AGENT:Processing] Ready to accept traffic. Awaiting mutation signals...");
        
        // В реальной реализации здесь был бы HTTP-сервер на Virtual Threads и логика мутации
        // Для MVP мы просто держим процесс живым и анонсируем себя
        Thread.currentThread().join(); 
    }

    private static Map<String, String> parse(String[] args) {
        var m = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++)
            if (args[i].startsWith("--") && i + 1 < args.length) m.put(args[i].substring(2), args[++i]);
        return m;
    }
}
