package dev.organism;

/** Намерения клеток. Решают клетки (децентрализованно), физически применяет Organism детерминированно. */
sealed interface Intent {
    record Bud(ProcessingCell parent, String node) implements Intent {}
    record Apoptosis(ProcessingCell cell) implements Intent {}
    record Quarantine(ProcessingCell cell) implements Intent {}
    record Release(ProcessingCell cell) implements Intent {}
    record Kill(ProcessingCell cell, String why) implements Intent {}
    record Germinate(Substrate.Spore spore, String guardian) implements Intent {}
    record Replicate(int shard, String from, String to) implements Intent {}
    record Adopt(ProcessingCell winner, Phenotype from, Phenotype to) implements Intent {}
}
