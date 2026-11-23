package dev.organism.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * RBAC: ADMIN / OPERATOR / VIEWER; actions VIEW / MUTATE / APPROVE / KILL.
 * Файл: <sha256hex(token)>:<ROLE>, '#' — комментарий. Сырые токены НЕ хранятся.
 * ADMIN: всё. OPERATOR: VIEW/MUTATE/KILL. VIEWER: VIEW. APPROVE — только ADMIN.
 * Сравнение хэшей constant-time (MessageDigest.isEqual), без early-exit.
 */
public final class RbacPolicy {
    public enum Role { ADMIN, OPERATOR, VIEWER }
    public enum Action { VIEW, MUTATE, APPROVE, KILL }

    private final Map<String, Role> hashes = new LinkedHashMap<>();

    public static RbacPolicy fromFile(Path p) throws IOException {
        RbacPolicy r = new RbacPolicy();
        for (String raw : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int c = line.lastIndexOf(':');
            if (c <= 0) throw new IOException("bad rbac line (expected <sha256hex>:<ROLE>)");
            String h = line.substring(0, c).toLowerCase(Locale.ROOT);
            if (!h.matches("[0-9a-f]{64}")) throw new IOException("token must be sha256 hex");
            r.hashes.put(h, Role.valueOf(line.substring(c + 1).trim().toUpperCase(Locale.ROOT)));
        }
        if (r.hashes.isEmpty()) throw new IOException("rbac file has no entries");
        return r;
    }

    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public Role roleOf(String token) {
        if (token == null || token.isEmpty()) return null;
        byte[] got;
        try {
            got = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
        Role found = null;
        for (var e : hashes.entrySet()) {
            byte[] want = HexFormat.of().parseHex(e.getKey());
            if (MessageDigest.isEqual(got, want)) found = e.getValue();
        }
        return found;
    }

    public boolean allows(Role r, Action a) {
        if (r == null) return false;
        return switch (r) {
            case ADMIN -> true;
            case OPERATOR -> a != Action.APPROVE;
            case VIEWER -> a == Action.VIEW;
        };
    }

    public boolean authorize(String token, Action a) { return allows(roleOf(token), a); }
    public int size() { return hashes.size(); }
}
