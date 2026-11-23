package dev.organism.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PSK AES-256-GCM для UDP gossip. mTLS на UDP невозможен — это честный PSK-конверт.
 * Layout: ver(1) | ts(8, epoch ms BE) | nonce(8) | iv(12) | ct+tag(16).
 * AAD = весь заголовок. Replay-window +/-5s + bounded LRU nonce-cache.
 * Ключ: 64-hex напрямую, иначе PBKDF2WithHmacSHA256 (210k итераций, фикс. соль кластера).
 * Replay-проверка ПОСЛЕ успешной аутентификации — иначе спуфер выселяет кэш мусором.
 */
public final class TransportSecurity {
    public static final byte VERSION = 1;
    private static final int IV_LEN = 12, TAG_BITS = 128, HEADER_LEN = 1 + 8 + 8 + IV_LEN;
    private final SecretKeySpec key;
    private final SecureRandom rnd = new SecureRandom();
    private final long windowMs;
    private final Map<Long, Boolean> seen;

    public TransportSecurity(String psk) { this(psk, 5000, 16384); }

    public TransportSecurity(String psk, long windowMs, int nonceCacheSize) {
        this.key = deriveKey(psk);
        this.windowMs = windowMs;
        this.seen = new LinkedHashMap<>(256, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, Boolean> e) { return size() > nonceCacheSize; }
        };
    }

    public static SecretKeySpec deriveKey(String psk) {
        if (psk == null || psk.isBlank()) throw new IllegalArgumentException("empty PSK");
        try {
            if (psk.matches("[0-9a-fA-F]{64}")) {
                byte[] k = new byte[32];
                for (int i = 0; i < 32; i++) k[i] = (byte) Integer.parseInt(psk.substring(i * 2, i * 2 + 2), 16);
                return new SecretKeySpec(k, "AES");
            }
            var f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            var spec = new PBEKeySpec(psk.toCharArray(),
                    "organism-gossip-v1".getBytes(StandardCharsets.UTF_8), 210_000, 256);
            byte[] k = f.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return new SecretKeySpec(k, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("key derivation failed", e);
        }
    }

    public byte[] wrap(byte[] plain) {
        try {
            long ts = System.currentTimeMillis();
            long nonce = rnd.nextLong();
            byte[] iv = new byte[IV_LEN];
            rnd.nextBytes(iv);
            byte[] header = ByteBuffer.allocate(HEADER_LEN).put(VERSION).putLong(ts).putLong(nonce).put(iv).array();
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(header);
            byte[] ct = c.doFinal(plain);
            return ByteBuffer.allocate(HEADER_LEN + ct.length).put(header).put(ct).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("wrap failed", e);
        }
    }

    /** null => дроп (replay / stale / tamper / мусор). */
    public byte[] unwrap(byte[] buf, int len) {
        try {
            if (len < HEADER_LEN + TAG_BITS / 8) return null;
            ByteBuffer b = ByteBuffer.wrap(buf, 0, len);
            if (b.get() != VERSION) return null;
            long ts = b.getLong();
            long nonce = b.getLong();
            byte[] iv = new byte[IV_LEN];
            b.get(iv);
            long now = System.currentTimeMillis();
            if (ts < now - windowMs || ts > now + windowMs) return null;
            byte[] header = new byte[HEADER_LEN];
            System.arraycopy(buf, 0, header, 0, HEADER_LEN);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(header);
            byte[] plain = c.doFinal(buf, HEADER_LEN, len - HEADER_LEN);
            synchronized (seen) {
                if (seen.putIfAbsent(nonce, Boolean.TRUE) != null) return null;
            }
            return plain;
        } catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }
}
