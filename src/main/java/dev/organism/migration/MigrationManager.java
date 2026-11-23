package dev.organism.migration;

import dev.organism.security.TransportSecurity;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Споры (снапшоты состояния агентов) для миграции/восстановления.
 * Формат: ver(1) | iv(12) | AES-256-GCM(ct+tag) | HMAC-SHA256(32) над ver|iv|ct.
 * Два subkey из PSK: sha256(root||"enc"), sha256(root||"mac") — domain separation.
 * Atomic write: tmp + force + ATOMIC_MOVE. Имена: sanitize(agentId) + "__" + ts + ".snap".
 * loadLatest: Files.list в try-with-resources, выбор по ЧИСЛОВОМУ ts.
 * HMAC проверяется ДО расшифровки (encrypt-then-MAC, constant-time compare).
 */
public final class MigrationManager {
    private static final byte VER = 1;
    private final Path dir;
    private final SecretKeySpec encKey, macKey;
    private final SecureRandom rnd = new SecureRandom();

    public MigrationManager(Path dir, String secret) {
        try {
            this.dir = dir;
            Files.createDirectories(dir);
            byte[] root = TransportSecurity.deriveKey(secret).getEncoded();
            this.encKey = new SecretKeySpec(sha256(root, "enc"), "AES");
            this.macKey = new SecretKeySpec(sha256(root, "mac"), "HmacSHA256");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] root, String label) throws GeneralSecurityException {
        var md = MessageDigest.getInstance("SHA-256");
        md.update(root);
        md.update(label.getBytes(StandardCharsets.UTF_8));
        return md.digest();
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKey);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    public Path snapshot(String agentId, byte[] state) throws IOException {
        long ts = System.currentTimeMillis();
        String name = sanitize(agentId) + "__" + ts + ".snap";
        try {
            byte[] iv = new byte[12];
            rnd.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, encKey, new GCMParameterSpec(128, iv));
            c.updateAAD(new byte[]{VER});
            byte[] ct = c.doFinal(state);
            byte[] body = ByteBuffer.allocate(1 + 12 + ct.length).put(VER).put(iv).put(ct).array();
            byte[] full = ByteBuffer.allocate(body.length + 32).put(body).put(hmac(body)).array();
            Path tmp = Files.createTempFile(dir, "snap-", ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                out.write(full);
            }
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Path fin = dir.resolve(name);
            try {
                Files.move(tmp, fin, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, fin, StandardCopyOption.REPLACE_EXISTING);
            }
            return fin;
        } catch (GeneralSecurityException e) {
            throw new IOException("snapshot crypto failed", e);
        }
    }

    public byte[] loadLatest(String agentId) throws IOException {
        String prefix = sanitize(agentId) + "__";
        Path best = null;
        long bestTs = -1;
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (!n.startsWith(prefix) || !n.endsWith(".snap")) continue;
                String mid = n.substring(prefix.length(), n.length() - 5);
                if (mid.isEmpty() || mid.length() > 18 || !mid.chars().allMatch(Character::isDigit)) continue;
                long ts = Long.parseLong(mid);
                if (ts > bestTs) { bestTs = ts; best = p; }
            }
        }
        return best == null ? null : read(best);
    }

    public byte[] read(Path file) throws IOException {
        byte[] full = Files.readAllBytes(file);
        if (full.length < 1 + 12 + 16 + 32) throw new IOException("snapshot too short: " + file);
        byte[] body = Arrays.copyOf(full, full.length - 32);
        byte[] mac = Arrays.copyOfRange(full, full.length - 32, full.length);
        if (!MessageDigest.isEqual(hmac(body), mac))
            throw new SecurityException("snapshot HMAC mismatch (tampered?): " + file);
        ByteBuffer b = ByteBuffer.wrap(body);
        if (b.get() != VER) throw new IOException("bad snapshot version");
        byte[] iv = new byte[12];
        b.get(iv);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, encKey, new GCMParameterSpec(128, iv));
            c.updateAAD(new byte[]{VER});
            return c.doFinal(body, 13, body.length - 13);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("snapshot decrypt failed: " + file, e);
        }
    }

    public static String sanitize(String id) {
        return id == null ? "null" : id.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
