package dev.organism.raft;

import dev.organism.security.AuditLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Raft = ПОРЯДОК лога. committed entry -> hash-chain AuditLog (неотказуемость обеспечивают FIDO2+Merkle/TSA, не Raft).
 * Идемпотентный replay после рестарта: appliedFile хранит последний применённый index.
 */
public final class RaftAuditBridge implements RaftNode.StateMachine {
    private final AuditLog audit;
    private final Path appliedFile;
    private long applied;

    public RaftAuditBridge(AuditLog audit, Path appliedFile) {
        this.audit = audit;
        this.appliedFile = appliedFile;
        if (appliedFile != null && Files.exists(appliedFile)) {
            try { applied = Long.parseLong(Files.readString(appliedFile).trim()); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }
    }

    @Override public void apply(long index, byte[] cmd) {
        if (index <= applied) return;
        String s = new String(cmd, StandardCharsets.UTF_8);
        int sep = s.indexOf('|');
        String event = sep < 0 ? "RAFT" : s.substring(0, sep);
        String detail = sep < 0 ? s : s.substring(sep + 1);
        audit.log(event, detail);
        applied = index;
        if (appliedFile != null) {
            try { Files.writeString(appliedFile, Long.toString(applied)); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }
    }

    public long applied() { return applied; }

    public static long propose(RaftNode n, String event, String detail) {
        return n.propose((event + "|" + detail).getBytes(StandardCharsets.UTF_8));
    }
}
