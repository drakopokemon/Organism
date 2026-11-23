package dev.organism.raft;

import dev.organism.supplychain.Cbor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Хранилища Raft-лога: in-memory (детерминированные тесты) и append-only файл (прод, durable hard state). */
public final class RaftStore {
    private RaftStore() {}

    public static final class Mem implements RaftNode.Store {
        final List<RaftNode.Entry> log = new ArrayList<>();
        long term;
        String votedFor;
        @Override public List<RaftNode.Entry> load() { return new ArrayList<>(log); }
        @Override public void append(RaftNode.Entry e) { log.add(e); }
        @Override public void truncateFrom(long index) {
            while (!log.isEmpty() && log.get(log.size() - 1).index() >= index) log.remove(log.size() - 1);
        }
        @Override public RaftNode.HardState loadVote() { return new RaftNode.HardState(term, votedFor); }
        @Override public void saveVote(long t, String v) { term = t; votedFor = v; }
    }

    /** Append-only base64(CBOR) записи: e=entry, x=truncate-from, v=vote. Replay при старте. */
    public static final class File implements RaftNode.Store {
        private final Path file;
        public File(Path file) {
            this.file = file;
            try { if (file.getParent() != null) Files.createDirectories(file.getParent()); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }
        @Override public List<RaftNode.Entry> load() {
            List<RaftNode.Entry> out = new ArrayList<>();
            if (!Files.exists(file)) return out;
            for (String line : readLines()) {
                Map<Object, Object> m = Cbor.asMap(Cbor.decode(Base64.getDecoder().decode(line)));
                String t = Cbor.str(m, "t");
                if (t.equals("e")) out.add(new RaftNode.Entry(Cbor.lng(m, "term"), Cbor.lng(m, "index"), Cbor.bytes(m, "cmd")));
                else if (t.equals("x")) {
                    long from = Cbor.lng(m, "from");
                    while (!out.isEmpty() && out.get(out.size() - 1).index() >= from) out.remove(out.size() - 1);
                }
            }
            return out;
        }
        @Override public void append(RaftNode.Entry e) {
            Map<Object, Object> m = new LinkedHashMap<>();
            m.put("t", "e"); m.put("term", e.term()); m.put("index", e.index()); m.put("cmd", e.cmd());
            line(m);
        }
        @Override public void truncateFrom(long index) {
            Map<Object, Object> m = new LinkedHashMap<>();
            m.put("t", "x"); m.put("from", index);
            line(m);
        }
        @Override public RaftNode.HardState loadVote() {
            long term = 0;
            String voted = null;
            if (Files.exists(file)) {
                for (String line : readLines()) {
                    Map<Object, Object> m = Cbor.asMap(Cbor.decode(Base64.getDecoder().decode(line)));
                    if (Cbor.str(m, "t").equals("v")) {
                        term = Cbor.lng(m, "term");
                        Object v = m.get("voted");
                        voted = v instanceof String s ? s : null;
                    }
                }
            }
            return new RaftNode.HardState(term, voted);
        }
        @Override public void saveVote(long term, String votedFor) {
            Map<Object, Object> m = new LinkedHashMap<>();
            m.put("t", "v"); m.put("term", term); m.put("voted", votedFor);
            line(m);
        }
        private List<String> readLines() {
            try {
                List<String> out = new ArrayList<>();
                for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) if (!l.isBlank()) out.add(l);
                return out;
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        private void line(Map<Object, Object> m) {
            try {
                Files.writeString(file, Base64.getEncoder().encodeToString(Cbor.encode(m)) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
    }
}
