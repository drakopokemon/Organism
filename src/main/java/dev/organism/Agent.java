package dev.organism;

/** Клетки организма. Sealed: набор «тканей» закрыт и проверяется компилятором. */
public sealed interface Agent permits ProcessingCell, ImmuneCell, StorageCell {
    String id();
}
