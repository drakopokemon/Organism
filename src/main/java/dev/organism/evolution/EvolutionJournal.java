package dev.organism.evolution;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

public final class EvolutionJournal {
    private final BufferedWriter writer;

    public EvolutionJournal(Path logPath) {
        try {
            Files.createDirectories(logPath.getParent() != null ? logPath.getParent() : Path.of("."));
            this.writer = Files.newBufferedWriter(logPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize EvolutionJournal", e);
        }
    }

    public void log(long tick, String agentId, String event, Map<String, Double> oldParams, Map<String, Double> newParams, double oldFitness, double newFitness) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"tick\":").append(tick)
          .append(",\"agent\":\"").append(agentId).append("\"")
          .append(",\"event\":\"").append(event).append("\"")
          .append(",\"old_fitness\":").append(oldFitness)
          .append(",\"new_fitness\":").append(newFitness)
          .append(",\"old_params\":").append(mapToJson(oldParams))
          .append(",\"new_params\":").append(mapToJson(newParams))
          .append("}");
        try {
            writer.write(sb.toString());
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            System.err.println("Failed to write to EvolutionJournal: " + e.getMessage());
        }
    }

    private String mapToJson(Map<String, Double> map) {
        if (map == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Double> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            System.err.println("Failed to close EvolutionJournal: " + e.getMessage());
        }
    }
}