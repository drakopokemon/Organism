package dev.organism.audit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Клей столпа 6: файл AuditLog -> Merkle-корень -> TSA-токен -> append-only AnchorStore.
 * startHourly — якорь раз в час (спека 6.0). verifyLatest пересчитывает корень по ТЕКУЩИМ строкам лога.
 */
public final class AuditAnchoring {
    private final Path auditFile;
    private final Tsa tsa;
    private final Tsa.AnchorStore store;

    public AuditAnchoring(Path auditFile, Tsa tsa, Tsa.AnchorStore store) {
        this.auditFile = auditFile;
        this.tsa = tsa;
        this.store = store;
    }

    public List<byte[]> leaves() {
        try {
            List<byte[]> out = new ArrayList<>();
            if (Files.exists(auditFile))
                for (String l : Files.readAllLines(auditFile, StandardCharsets.UTF_8))
                    if (!l.isEmpty()) out.add(l.getBytes(StandardCharsets.UTF_8));
            return out;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public synchronized byte[] anchorNow() {
        byte[] root = new MerkleTree(leaves()).root();
        byte[] token = tsa.stamp(root);
        store.append(token);
        return root;
    }

    public boolean verifyLatest(PublicKey tsaPub) {
        List<byte[]> tokens = store.load();
        if (tokens.isEmpty()) return false;
        byte[] latest = tokens.get(tokens.size() - 1);
        byte[] currentRoot = new MerkleTree(leaves()).root();
        return Tsa.verify(latest, tsaPub, currentRoot);
    }

    public void startHourly(ScheduledExecutorService ex) {
        ex.scheduleAtFixedRate(this::anchorNow, 1, 1, TimeUnit.HOURS);
    }

    public Tsa.AnchorStore store() { return store; }
}
