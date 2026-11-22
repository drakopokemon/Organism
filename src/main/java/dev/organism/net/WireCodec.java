package dev.organism.net;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;

/**
 * Gossip Auth (HMAC-SHA256).
 * Оборачивает бинарные сообщения Wire в конверт с MAC-подписью.
 * Формат: [int payloadLen][byte[] payload][byte[32] mac]
 */
public final class WireCodec {
    private static final String HMAC_ALGO = "HmacSHA256";
    private final SecretKeySpec key;

    public WireCodec(byte[] secret) {
        this.key = new SecretKeySpec(secret, HMAC_ALGO);
    }

    public byte[] wrap(Wire msg) {
        byte[] payload = msg.encode();
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(key);
            byte[] sig = mac.doFinal(payload);
            ByteBuffer buf = ByteBuffer.allocate(4 + payload.length + sig.length);
            buf.putInt(payload.length);
            buf.put(payload);
            buf.put(sig);
            return buf.array();
        } catch (Exception e) { 
            throw new RuntimeException("Wire wrap failed", e); 
        }
    }

    public Wire unwrap(byte[] raw, int len) {
        if (len < 4 + 32) throw new SecurityException("packet too short for MAC");
        ByteBuffer buf = ByteBuffer.wrap(raw, 0, len);
        int pLen = buf.getInt();
        if (pLen < 0 || pLen > len - 4 - 32) throw new SecurityException("bad payload length");
        
        byte[] payload = new byte[pLen];
        buf.get(payload);
        byte[] sig = new byte[32];
        buf.get(sig);
        
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(key);
            byte[] expected = mac.doFinal(payload);
            if (!MessageDigest.isEqual(expected, sig)) {
                throw new SecurityException("bad MAC, dropping packet");
            }
            return Wire.decode(payload, pLen);
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) { 
            throw new RuntimeException("Wire unwrap failed", e); 
        }
    }
}
