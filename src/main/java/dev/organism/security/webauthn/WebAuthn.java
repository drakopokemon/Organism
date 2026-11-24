package dev.organism.security.webauthn;

import dev.organism.supplychain.Cbor;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.AlgorithmParameters;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Столп 6: FIDO2/WebAuthn assertion verification на JDK (ES256 + EdDSA), без BouncyCastle.
 * Человек подписывает SHA-256 карточки мутации (challenge) — подписывает ровно то, что видит в UI.
 * Проверки FAIL-CLOSED: type/challenge/origin, rpIdHash, UP/UV-флаги, монотонный signCount (клон ключа),
 * подпись над authData || SHA256(clientDataJSON). COSE-ключи: EC2/P-256 и OKP/Ed25519 из CBOR.
 */
public final class WebAuthn {
    public record Credential(String id, String user, PublicKey pub, long alg, long signCount) {}

    private final Map<String, Credential> creds = new ConcurrentHashMap<>();

    public void register(String credentialId, String user, byte[] coseKey) {
        Map<Object, Object> m = Cbor.asMap(Cbor.decode(coseKey));
        long kty = Cbor.lng(m, 1L);
        long alg = Cbor.lng(m, 3L);
        try {
            if (kty == 2 && alg == -7) {
                if (Cbor.lng(m, -1L) != 1) throw new IllegalArgumentException("webauthn: only P-256 supported");
                byte[] x = Cbor.bytes(m, -2L), y = Cbor.bytes(m, -3L);
                if (x.length != 32 || y.length != 32) throw new IllegalArgumentException("webauthn: bad EC point");
                AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
                ap.init(new ECGenParameterSpec("secp256r1"));
                ECParameterSpec spec = ap.getParameterSpec(ECParameterSpec.class);
                PublicKey pub = KeyFactory.getInstance("EC").generatePublic(
                        new ECPublicKeySpec(new ECPoint(new BigInteger(1, x), new BigInteger(1, y)), spec));
                creds.put(credentialId, new Credential(credentialId, user, pub, alg, 0));
            } else if (kty == 1 && alg == -8) {
                if (Cbor.lng(m, -1L) != 6) throw new IllegalArgumentException("webauthn: only Ed25519 supported");
                byte[] k = Cbor.bytes(m, -2L);
                if (k.length != 32) throw new IllegalArgumentException("webauthn: bad Ed25519 key");
                boolean xOdd = (k[31] & 0x80) != 0;
                byte[] le = k.clone();
                le[31] &= 0x7f;
                byte[] be = new byte[32];
                for (int i = 0; i < 32; i++) be[i] = le[31 - i];
                EdECPoint point = new EdECPoint(xOdd, new BigInteger(1, be));
                PublicKey pub = KeyFactory.getInstance("Ed25519").generatePublic(
                        new EdECPublicKeySpec(NamedParameterSpec.ED25519, point));
                creds.put(credentialId, new Credential(credentialId, user, pub, alg, 0));
            } else {
                throw new IllegalArgumentException("webauthn: unsupported COSE kty/alg " + kty + "/" + alg);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("webauthn: bad COSE key", e);
        }
    }

    public Credential credential(String id) { return creds.get(id); }
    public String userOf(String id) { Credential c = creds.get(id); return c == null ? null : c.user(); }
    public int size() { return creds.size(); }

    public static byte[] challengeFor(String mutationCardJson) {
        return sha256(mutationCardJson.getBytes(StandardCharsets.UTF_8));
    }

    /** FAIL-CLOSED: любое несовпадение -> SecurityException. Возвращает новый signCount. */
    public synchronized long verifyAssertion(String credentialId, byte[] clientDataJson, byte[] authData, byte[] sig,
                                             byte[] expectedChallenge, Set<String> allowedOrigins, String rpId, boolean requireUV) {
        Credential c = creds.get(credentialId);
        if (c == null) throw new SecurityException("webauthn: unknown credential");
        String cdj = new String(clientDataJson, StandardCharsets.UTF_8);
        if (!"webauthn.get".equals(jsonString(cdj, "type"))) throw new SecurityException("webauthn: bad type");
        String want = Base64.getUrlEncoder().withoutPadding().encodeToString(expectedChallenge);
        if (!want.equals(jsonString(cdj, "challenge"))) throw new SecurityException("webauthn: challenge mismatch (not this mutation card)");
        if (!allowedOrigins.contains(jsonString(cdj, "origin"))) throw new SecurityException("webauthn: origin not allowed");
        if (authData.length < 37) throw new SecurityException("webauthn: short authenticatorData");
        byte[] rpHash = Arrays.copyOf(authData, 32);
        if (!MessageDigest.isEqual(rpHash, sha256(rpId.getBytes(StandardCharsets.UTF_8))))
            throw new SecurityException("webauthn: rpIdHash mismatch");
        int flags = authData[32] & 0xff;
        if ((flags & 0x01) == 0) throw new SecurityException("webauthn: user-present flag missing");
        if (requireUV && (flags & 0x04) == 0) throw new SecurityException("webauthn: user-verification required");
        long count = ((authData[33] & 0xffL) << 24) | ((authData[34] & 0xffL) << 16) | ((authData[35] & 0xffL) << 8) | (authData[36] & 0xffL);
        if ((count != 0 || c.signCount() != 0) && count <= c.signCount())
            throw new SecurityException("webauthn: signCount rollback (cloned key?)");
        byte[] signed = new byte[authData.length + 32];
        System.arraycopy(authData, 0, signed, 0, authData.length);
        System.arraycopy(sha256(clientDataJson), 0, signed, authData.length, 32);
        try {
            Signature s = Signature.getInstance(c.alg() == -7 ? "SHA256withECDSA" : "Ed25519");
            s.initVerify(c.pub());
            s.update(signed);
            if (!s.verify(sig)) throw new SecurityException("webauthn: signature INVALID");
        } catch (GeneralSecurityException e) {
            throw new SecurityException("webauthn: signature check failed: " + e.getMessage());
        }
        creds.put(credentialId, new Credential(c.id(), c.user(), c.pub(), c.alg(), count));
        CountHook h = countHook;
        if (h != null) h.accept(credentialId, count);
        return count;
    }

    /** 6.6: hook после успешного verifyAssertion - персист signCount (clone-detect переживает рестарт). */
    public interface CountHook { void accept(String credentialId, long count); }
    private volatile CountHook countHook;
    public synchronized WebAuthn onSignCount(CountHook h) { this.countHook = h; return this; }

    /** 6.6: восстановление signCount после рестарта. Rollback самого счётчика запрещён (fail-closed). */
    public synchronized void restoreSignCount(String credentialId, long count) {
        Credential c = creds.get(credentialId);
        if (c == null) throw new IllegalArgumentException("webauthn: unknown credential " + credentialId);
        if (count < c.signCount()) throw new SecurityException("webauthn: signCount restore rollback");
        creds.put(credentialId, new Credential(c.id(), c.user(), c.pub(), c.alg(), count));
    }

    public static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** COSE EC2/P-256 из JDK ECPublicKey (для регистрации синтетических/экспортированных ключей). */
    public static byte[] coseFromEc(ECPublicKey pub) {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put(1L, 2L); m.put(3L, -7L); m.put(-1L, 1L);
        m.put(-2L, to32(pub.getW().getAffineX()));
        m.put(-3L, to32(pub.getW().getAffineY()));
        return Cbor.encode(m);
    }

    /** COSE OKP/Ed25519: x = encoded point (y little-endian + sign bit of x). */
    public static byte[] coseFromEd(PublicKey pub) {
        java.security.interfaces.EdECPublicKey ed = (java.security.interfaces.EdECPublicKey) pub;
        EdECPoint pt = ed.getPoint();
        byte[] raw = pt.getY().toByteArray();
        int off = (raw.length > 32 && raw[0] == 0) ? 1 : 0;
        int len = Math.min(32, raw.length - off);
        byte[] le = new byte[32];
        for (int i = 0; i < len; i++) le[i] = raw[off + len - 1 - i];
        if (pt.isXOdd()) le[31] |= (byte) 0x80;
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put(1L, 1L); m.put(3L, -8L); m.put(-1L, 6L); m.put(-2L, le);
        return Cbor.encode(m);
    }

    static byte[] to32(BigInteger v) {
        byte[] b = v.toByteArray();
        int off = (b.length > 32 && b[0] == 0) ? 1 : 0;
        int len = Math.min(32, b.length - off);
        byte[] out = new byte[32];
        System.arraycopy(b, off, out, 32 - len, len);
        return out;
    }

    /** Минимальный scanner плоского JSON (clientDataJSON): только строковые поля, fail-closed. */
    static String jsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) throw new SecurityException("webauthn: missing field " + key);
        int p = i + needle.length();
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length() || json.charAt(p) != ':') throw new SecurityException("webauthn: bad json");
        p++;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length() || json.charAt(p) != '"') throw new SecurityException("webauthn: field not a string");
        p++;
        StringBuilder sb = new StringBuilder();
        while (p < json.length()) {
            char ch = json.charAt(p++);
            if (ch == '"') return sb.toString();
            if (ch == '\\') {
                if (p >= json.length()) break;
                char e = json.charAt(p++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (p + 4 > json.length()) throw new SecurityException("webauthn: bad unicode escape");
                        sb.append((char) Integer.parseInt(json.substring(p, p + 4), 16));
                        p += 4;
                    }
                    default -> throw new SecurityException("webauthn: bad escape");
                }
            } else sb.append(ch);
        }
        throw new SecurityException("webauthn: unterminated string");
    }
}
