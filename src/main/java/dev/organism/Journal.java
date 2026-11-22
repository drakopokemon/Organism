package dev.organism;

import java.io.*;
import java.nio.file.*;
import java.util.Map;

/** Append-only JSONL: каждое решение организма трассируемо и (при том же seed) воспроизводимо. */
final class Journal implements Closeable {
    private final BufferedWriter w;
    Journal(Path p) throws IOException { w = p == null ? null : Files.newBufferedWriter(p); }
    synchronized void write(Map<String, Object> m) {
        if (w == null) return;
        try { w.write(Json.of(m)); w.newLine(); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    @Override public void close() throws IOException { if (w != null) w.close(); }
}
