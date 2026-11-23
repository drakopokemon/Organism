package dev.organism.audit;

import dev.organism.supplychain.Cbor;
import dev.organism.supplychain.GenomeArtifact;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Столп 6: внутренний TSA (Time-Stamp Authority) — Ed25519-подписанный CBOR-токен {root,ts,serial,tsa}.
 * Раз в час Merkle-корень аудита якорится сюда: админ не может подчистить лог, не сломав цепочку якорей.
 * ЧЕСТНО: это JDK-эквивалент RFC3161 (не ASN.1/TSP), а подписанный timestamp с монотонным serial.
 * AnchorStore — append-only base64-строки; verifyChain: непрерывные serial + монотонный ts + валидные подписи.
 */
public final class Tsa {
    private final PrivateKey key;
    private final String id;
    private final LongSupplier clock;
    private final AtomicLong serial = new AtomicLong();

    public Tsa(PrivateKey key, String id, LongSupplier clock) {
        this.key = key;
        this.id = id;
        this.clock = clock;
    }

    public byte[] stamp(byte[] root) {
        Map<Object, Object> payload = new LinkedHashMap<>();
        payload.put("root", root.clone());
        payload.put("ts", clock.getAsLong());
        payload.put("serial", serial.incrementAndGet());
        payload.put("tsa", id);
        byte[] p = Cbor.encode(payload);
        Map<Object, Object> token = new LinkedHashMap<>();
        token.put("alg", GenomeArtifact.ALG);
        token.put("payload", p);
        token.put("sig", GenomeArtifact.sign(p, key));
        return Cbor.encode(token);
    }

    public static boolean verify(byte[] artifact, PublicKey pub, byte[] expectedRoot) {
        try {
            Map<Object, Object> m = Cbor.asMap(Cbor.decode(artifact));
            if (!GenomeArtifact.ALG.equals(Cbor.str(m, "alg"))) return false;
            byte[] p = Cbor.bytes(m, "payload");
            if (!GenomeArtifact.verify(p, Cbor.bytes(m, "sig"), pub)) return false;
            Map<Object, Object> pm = Cbor.asMap(Cbor.decode(p));
            return MessageDigest.isEqual(Cbor.bytes(pm, "root"), expectedRoot);
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<Object, Object> payloadOf(byte[] artifact) {
        Map<Object, Object> m = Cbor.asMap(Cbor.decode(artifact));
        return Cbor.asMap(Cbor.decode(Cbor.bytes(m, "payload")));
    }

    public static long serialOf(byte[] artifact) { return Cbor.lng(payloadOf(artifact), "serial"); }
    public static long tsOf(byte[] artifact) { return Cbor.lng(payloadOf(artifact), "ts"); }
    public static byte[] rootOf(byte[] artifact) { return Cbor.bytes(payloadOf(artifact), "root"); }

    public static final class AnchorStore {
        private final Path file;

        public AnchorStore(Path file) {
            this.file = file;
            try { if (file.getParent() != null) Files.createDirectories(file.getParent()); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }

        public void append(byte[] token) {
            try {
                Files.writeString(file, Base64.getEncoder().encodeToString(token) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }

        public List<byte[]> load() {
            if (!Files.exists(file)) return List.of();
            try {
                List<byte[]> out = new ArrayList<>();
                for (String l : Files.readAllLines(file, StandardCharsets.UTF_8))
                    if (!l.isBlank()) out.add(Base64.getDecoder().decode(l));
                return out;
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }

        public boolean verifyChain(PublicKey pub) {
            long expectSerial = 1, prevTs = Long.MIN_VALUE;
            for (byte[] t : load()) {
                try {
                    Map<Object, Object> m = Cbor.asMap(Cbor.decode(t));
                    byte[] p = Cbor.bytes(m, "payload");
                    if (!GenomeArtifact.verify(p, Cbor.bytes(m, "sig"), pub)) return false;
                    Map<Object, Object> pm = Cbor.asMap(Cbor.decode(p));
                    if (Cbor.lng(pm, "serial") != expectSerial) return false;
                    long ts = Cbor.lng(pm, "ts");
                    if (ts < prevTs) return false;
                    prevTs = ts;
                    expectSerial++;
                } catch (Exception e) {
                    return false;
                }
            }
            return true;
        }
    }
}
