package dev.organism;

import java.util.*;

/** Фенотип — конкретные значения генов клетки. Неизменяем: «мутация» = новый объект. */
public record Phenotype(SortedMap<String, Double> g) {
    public Phenotype { g = Collections.unmodifiableSortedMap(new TreeMap<>(g)); }
    public double get(String n) { return g.get(n); }
    public Phenotype with(String n, double v) { var m = new TreeMap<>(g); m.put(n, v); return new Phenotype(m); }
    @Override public String toString() { return g.toString(); }
}
