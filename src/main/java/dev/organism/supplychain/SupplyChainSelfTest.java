package dev.organism.supplychain;

import dev.organism.genome.Genome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Детерминированный selftest столпа 1: CBOR + Ed25519 genome-артефакт + верификация бинарников. */
public final class SupplyChainSelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-62s %s%n", ok ? "OK " : "FAIL", name, detail);
    }

    public static void main(String[] args) throws Exception {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put("z", 1L); m.put("a", -5L); m.put("s", "привет"); m.put("b", new byte[]{1, 2, 3});
        m.put("d", 3.14); m.put("l", List.of(1L, 2L)); m.put("n", null); m.put("t", true);
        byte[] enc = Cbor.encode(m);
        Map<?, ?> dec = Cbor.asMap(Cbor.decode(enc));
        check("cbor roundtrip string", "привет".equals(Cbor.str(dec, "s")), "");
        check("cbor roundtrip negint", Cbor.lng(dec, "a") == -5, "");
        check("cbor roundtrip double", Cbor.dbl(dec, "d") == 3.14, "");
        check("cbor roundtrip null/bool", Cbor.get(dec, "n") == null && Cbor.bool(dec, "t"), "");
        check("cbor deterministic re-encode", Arrays.equals(enc, Cbor.encode(dec)), "");
        Map<Object, Object> m2 = new LinkedHashMap<>();
        List<Map.Entry<Object, Object>> es = new ArrayList<>(m.entrySet());
        for (int i = es.size() - 1; i >= 0; i--) m2.put(es.get(i).getKey(), es.get(i).getValue());
        check("cbor canonical key order", Arrays.equals(enc, Cbor.encode(m2)), "");
        byte[] bad = enc.clone(); bad[0] ^= (byte) 0xff;
        boolean threw = false;
        try { Cbor.decode(bad); } catch (Exception e) { threw = true; }
        check("cbor fail-closed on corruption", threw, "");

        Genome g = new Genome(
                List.of(new Genome.LatencyObjective(42.0), new Genome.ThroughputObjective(9000.0)),
                List.of(new Genome.RegionConstraint("eu-west"), new Genome.ResourceConstraint(512), new Genome.ForbiddenApisConstraint("sun.misc.Unsafe")),
                List.of(new Genome.ProcessingCapability(2, 9), new Genome.ImmuneCapability(true), new Genome.StorageCapability(24, 3), new Genome.BalancingCapability("p2c")),
                new Genome.MutationPolicy(2, 8, 24, 50, 50, 2000, 64, 64, 1024, 50, 100, 2000));
        KeyPair kp = GenomeArtifact.generateKeyPair();
        byte[] art = GenomeArtifact.artifact(g, kp.getPrivate());
        Genome back = GenomeArtifact.open(art, kp.getPublic());
        check("genome artifact sign/open roundtrip", Arrays.equals(GenomeArtifact.encode(g), GenomeArtifact.encode(back)), "");
        Genome def = Genome.defaultGenome();
        Genome defBack = GenomeArtifact.open(GenomeArtifact.artifact(def, kp.getPrivate()), kp.getPublic());
        check("default genome artifact roundtrip", Arrays.equals(GenomeArtifact.encode(def), GenomeArtifact.encode(defBack)), "");
        KeyPair other = GenomeArtifact.generateKeyPair();
        boolean rejected = false;
        try { GenomeArtifact.open(art, other.getPublic()); } catch (Exception e) { rejected = true; }
        check("artifact wrong key rejected", rejected, "");
        byte[] tam = art.clone(); tam[tam.length / 2] ^= 1;
        rejected = false;
        try { GenomeArtifact.open(tam, kp.getPublic()); } catch (Exception e) { rejected = true; }
        check("artifact tamper rejected", rejected, "");
        PublicKey pub = GenomeArtifact.publicFromB64(GenomeArtifact.b64(kp.getPublic()));
        PrivateKey priv = GenomeArtifact.privateFromB64(GenomeArtifact.b64(kp.getPrivate()));
        check("SPKI/PKCS8 key roundtrip", Arrays.equals(pub.getEncoded(), kp.getPublic().getEncoded())
                && Arrays.equals(priv.getEncoded(), kp.getPrivate().getEncoded()), "");

        Path dir = Files.createTempDirectory("org-supply");
        Path blob = dir.resolve("app.bin");
        Files.write(blob, new byte[]{1, 2, 3, 4, 5});
        Path sig = dir.resolve("app.sig");
        Files.write(sig, GenomeArtifact.sign(Files.readAllBytes(blob), kp.getPrivate()));
        check("binary ed25519 verify ok", BinaryVerifier.verify(BinaryVerifier.Mode.ED25519, blob, sig, kp.getPublic(), null), "");
        Files.write(blob, new byte[]{1, 2, 3, 4, 6});
        check("binary tamper rejected", !BinaryVerifier.verify(BinaryVerifier.Mode.ED25519, blob, sig, kp.getPublic(), null), "");
        check("binary OFF preserves legacy", BinaryVerifier.verify(BinaryVerifier.Mode.OFF, blob, sig, null, null), "");
        check("cosign fail-closed", BinaryVerifier.toolAvailable("cosign")
                || !BinaryVerifier.verify(BinaryVerifier.Mode.COSIGN, blob, sig, null, dir.resolve("no.pem")), "");

        System.out.println(failed == 0 ? "SUPPLYCHAIN SELFTEST PASSED" : "SUPPLYCHAIN SELFTEST FAILED: " + failed);
        if (failed > 0) System.exit(1);
    }
}
