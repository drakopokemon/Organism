package dev.organism.cell;

/** Контракт клетки для эволюции и иммунитета. Тестируется стабами без сети. */
public interface CellCtl {
    String id();
    double p99();
    double dropRate();
    long served();
    boolean quarantined();
    void quarantine(boolean q);
}
