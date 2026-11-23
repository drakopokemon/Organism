package dev.organism.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Tamper-evident audit: HASH-CHAIN + HMAC-SHA256.
 * Запись: {"seq":N,"ts":...,"event":"...","detail":"...","prev":"<64hex>","sig":"<64hex>"}
 * sig = HMAC(secret, payloadPart) где payloadPart — ВСЕ байты строки до ,"sig": (включая prev).
 * verify() ловит: подмену байта, reorder (seq/prev), вырез из середины (chain),
 * truncate хвоста — через anchor <file>.head (seq:sig последней записи, atomic write).
 * Честное ограничение: без anchor хвостовой truncate не отличим от конца лога.
 * CLI: java dev.organism.security.AuditLog <file> <secret>
 */
public final class AuditLog implements AutoCloseable {
    private static final String GENESIS = "0".repeat(64);
    private final Path file;
    private final BufferedWriter w;
    private final Mac mac;
    private long seq;
    private String prevSig = GENESIS;

    public AuditLog(Path file, String secret) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            this.file = file;
            this.mac = Mac.getInstance("HmacSHA256");
            this.mac.init(new SecretKeySpec(sha256(secret), "HmacSHA256"));
            if (Files.exists(file) && Files.size(file) > 0) {
                if (!verify(file, secret)) throw new IOException("existing audit log FAILED verification (tampered?)");
                String last = lastLine(file);
                int idx = last.lastIndexOf(",\"sig\":\"");
                this.prevSig = last.substring(idx + 8, last.length() - 2);
                this.seq = Long.parseLong(last.substring(7, last.indexOf(','))) + 1;
            }
            this.w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (Exception e) {
            throw new IllegalStateException("audit init failed", e);
        }
    }

    public synchronized void log(String event, String detail) {
        try {
            long ts = System.currentTimeMillis();
            String part = "{\"seq\":" + seq + ",\"ts\":" + ts + ",\"event\":\"" + esc(event)
                    + "\",\"detail\":\"" + esc(detail) + "\",\"prev\":\"" + prevSig + "\"";
            String sig = HexFormat.of().formatHex(mac.doFinal(part.getBytes(StandardCharsets.UTF_8)));
            w.write(part + ",\"sig\":\"" + sig + "\"}");
            w.newLine();
            w.flush();
            writeAnchor(seq, sig);
            prevSig = sig;
            seq++;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeAnchor(long s, String sig) throws IOException {
        Path anchor = anchorOf(file);
        Path tmp = Files.createTempFile(anchor.getParent(), "audit-", ".head.tmp");
        Files.writeString(tmp, s + ":" + sig, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, anchor, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, anchor, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static Path anchorOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".head");
    }

    public static boolean verify(Path file, String secret) {
        try {
            if (!Files.exists(file)) return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sha256(secret), "HmacSHA256"));
            long expect = 0;
            String prev = GENESIS;
            long lastSeq = -1;
            String lastSig = null;
            try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) continue;
                    int idx = line.lastIndexOf(",\"sig\":\"");
                    if (idx < 0 || !line.endsWith("\"}")) return false;
                    String part = line.substring(0, idx);
                    String sig = line.substring(idx + 8, line.length() - 2);
                    if (!sig.matches("[0-9a-f]{64}")) return false;
                    byte[] want = mac.doFinal(part.getBytes(StandardCharsets.UTF_8));
                    if (!MessageDigest.isEqual(want, HexFormat.of().parseHex(sig))) return false;
                    if (!part.startsWith("{\"seq\":" + expect + ",")) return false;
                    if (!part.endsWith("\"prev\":\"" + prev + "\"")) return false;
                    prev = sig;
                    lastSeq = expect;
                    lastSig = sig;
                    expect++;
                }
            }
            Path anchor = anchorOf(file);
            if (Files.exists(anchor)) {
                String[] a = Files.readString(anchor, StandardCharsets.UTF_8).trim().split(":");
                if (a.length != 2 || Long.parseLong(a[0]) != lastSeq || !a[1].equals(lastSig)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String lastLine(Path file) throws IOException {
        String last = null;
        try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String l;
            while ((l = r.readLine()) != null) if (!l.isEmpty()) last = l;
        }
        if (last == null) throw new IOException("empty audit log");
        return last;
    }

    private static byte[] sha256(String secret) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
    }

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    @Override public synchronized void close() {
        try { w.close(); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("usage: AuditLog <file> <secret>");
            System.exit(2);
        }
        boolean ok = verify(Path.of(args[0]), args[1]);
        System.out.println(ok ? "AUDIT VERIFY OK" : "AUDIT VERIFY FAILED");
        System.exit(ok ? 0 : 1);
    }
}
