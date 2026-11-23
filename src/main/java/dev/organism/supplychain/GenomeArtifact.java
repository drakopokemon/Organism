package dev.organism.supplychain;

import dev.organism.genome.Genome;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Supply Chain генома: Genome -> канонический CBOR -> Ed25519-подпись (JDK 15+, без BouncyCastle/Sigstore).
 * Артефакт = CBOR {"alg":"Ed25519","payload":bstr,"sig":bstr}. open() FAIL-CLOSED: чужой ключ/тампер -> throw.
 * Параметры мутаций НЕ вшиваются в бинарник агента — они едут в env/CLI при старте (см. NativeSupervisor).
 * CLI: genkey <prefix> | sign <genome.yaml|-> <keyB64> <out.artifact> | verify <artifact> <pubB64>
 */
public final class GenomeArtifact {
    public static final String ALG = "Ed25519";
    private GenomeArtifact() {}

    public static Map<Object, Object> toCbor(Genome g) {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put("v", 1L);
        List<Object> objs = new ArrayList<>();
        for (Genome.Objective o : g.objectives) {
            Map<Object, Object> om = new LinkedHashMap<>();
            switch (o) {
                case Genome.LatencyObjective l -> { om.put("type", "latency"); om.put("targetP99Ms", l.targetP99Ms()); }
                case Genome.CostObjective c -> { om.put("type", "cost"); om.put("maxUsdPerMonth", c.maxUsdPerMonth()); }
                case Genome.ThroughputObjective t -> { om.put("type", "throughput"); om.put("targetRps", t.targetRps()); }
            }
            objs.add(om);
        }
        List<Object> cons = new ArrayList<>();
        for (Genome.Constraint c : g.constraints) {
            Map<Object, Object> cm = new LinkedHashMap<>();
            switch (c) {
                case Genome.RegionConstraint r -> { cm.put("type", "region"); cm.put("allowedRegion", r.allowedRegion()); }
                case Genome.ResourceConstraint r -> { cm.put("type", "resource"); cm.put("maxRamMb", (long) r.maxRamMb()); }
                case Genome.ForbiddenApisConstraint f -> { cm.put("type", "forbidden_apis"); cm.put("forbiddenApis", f.forbiddenApis()); }
            }
            cons.add(cm);
        }
        List<Object> caps = new ArrayList<>();
        for (Genome.Capability c : g.capabilities) {
            Map<Object, Object> cm = new LinkedHashMap<>();
            switch (c) {
                case Genome.ProcessingCapability p -> { cm.put("type", "processing"); cm.put("minInstances", (long) p.minInstances()); cm.put("maxInstances", (long) p.maxInstances()); }
                case Genome.ImmuneCapability i -> { cm.put("type", "immune"); cm.put("enabled", i.enabled()); }
                case Genome.StorageCapability s -> { cm.put("type", "storage"); cm.put("shards", (long) s.shards()); cm.put("replication", (long) s.replication()); }
                case Genome.BalancingCapability b -> { cm.put("type", "balancing"); cm.put("algorithm", b.algorithm()); }
            }
            caps.add(cm);
        }
        Genome.MutationPolicy p = g.mutationPolicy;
        Map<Object, Object> mp = new LinkedHashMap<>();
        mp.put("poolSizeStep", p.poolSizeStep()); mp.put("poolSizeMin", p.poolSizeMin()); mp.put("poolSizeMax", p.poolSizeMax());
        mp.put("queueLimitStep", p.queueLimitStep()); mp.put("queueLimitMin", p.queueLimitMin()); mp.put("queueLimitMax", p.queueLimitMax());
        mp.put("cacheMbStep", p.cacheMbStep()); mp.put("cacheMbMin", p.cacheMbMin()); mp.put("cacheMbMax", p.cacheMbMax());
        mp.put("timeoutMsStep", p.timeoutMsStep()); mp.put("timeoutMsMin", p.timeoutMsMin()); mp.put("timeoutMsMax", p.timeoutMsMax());
        m.put("objectives", objs);
        m.put("constraints", cons);
        m.put("capabilities", caps);
        m.put("mutationPolicy", mp);
        return m;
    }

    public static Genome fromCbor(Object decoded) {
        Map<Object, Object> m = Cbor.asMap(decoded);
        if (Cbor.lng(m, "v") != 1) throw new IllegalArgumentException("genome artifact: unsupported version");
        List<Genome.Objective> objs = new ArrayList<>();
        for (Object o : Cbor.list(m, "objectives")) {
            Map<Object, Object> om = Cbor.asMap(o);
            String t = Cbor.str(om, "type");
            switch (t) {
                case "latency" -> objs.add(new Genome.LatencyObjective(Cbor.dbl(om, "targetP99Ms")));
                case "cost" -> objs.add(new Genome.CostObjective(Cbor.dbl(om, "maxUsdPerMonth")));
                case "throughput" -> objs.add(new Genome.ThroughputObjective(Cbor.dbl(om, "targetRps")));
                default -> throw new IllegalArgumentException("genome artifact: unknown objective " + t);
            }
        }
        List<Genome.Constraint> cons = new ArrayList<>();
        for (Object o : Cbor.list(m, "constraints")) {
            Map<Object, Object> cm = Cbor.asMap(o);
            String t = Cbor.str(cm, "type");
            switch (t) {
                case "region" -> cons.add(new Genome.RegionConstraint(Cbor.str(cm, "allowedRegion")));
                case "resource" -> cons.add(new Genome.ResourceConstraint((int) Cbor.lng(cm, "maxRamMb")));
                case "forbidden_apis" -> cons.add(new Genome.ForbiddenApisConstraint(Cbor.str(cm, "forbiddenApis")));
                default -> throw new IllegalArgumentException("genome artifact: unknown constraint " + t);
            }
        }
        List<Genome.Capability> caps = new ArrayList<>();
        for (Object o : Cbor.list(m, "capabilities")) {
            Map<Object, Object> cm = Cbor.asMap(o);
            String t = Cbor.str(cm, "type");
            switch (t) {
                case "processing" -> caps.add(new Genome.ProcessingCapability((int) Cbor.lng(cm, "minInstances"), (int) Cbor.lng(cm, "maxInstances")));
                case "immune" -> caps.add(new Genome.ImmuneCapability(Cbor.bool(cm, "enabled")));
                case "storage" -> caps.add(new Genome.StorageCapability((int) Cbor.lng(cm, "shards"), (int) Cbor.lng(cm, "replication")));
                case "balancing" -> caps.add(new Genome.BalancingCapability(Cbor.str(cm, "algorithm")));
                default -> throw new IllegalArgumentException("genome artifact: unknown capability " + t);
            }
        }
        Map<Object, Object> mp = Cbor.asMap(Cbor.get(m, "mutationPolicy"));
        Genome.MutationPolicy policy = new Genome.MutationPolicy(
                Cbor.dbl(mp, "poolSizeStep"), Cbor.dbl(mp, "poolSizeMin"), Cbor.dbl(mp, "poolSizeMax"),
                Cbor.dbl(mp, "queueLimitStep"), Cbor.dbl(mp, "queueLimitMin"), Cbor.dbl(mp, "queueLimitMax"),
                Cbor.dbl(mp, "cacheMbStep"), Cbor.dbl(mp, "cacheMbMin"), Cbor.dbl(mp, "cacheMbMax"),
                Cbor.dbl(mp, "timeoutMsStep"), Cbor.dbl(mp, "timeoutMsMin"), Cbor.dbl(mp, "timeoutMsMax"));
        return new Genome(objs, cons, caps, policy);
    }

    public static byte[] encode(Genome g) { return Cbor.encode(toCbor(g)); }

    public static byte[] sign(byte[] payload, PrivateKey key) {
        try {
            Signature s = Signature.getInstance(ALG);
            s.initSign(key);
            s.update(payload);
            return s.sign();
        } catch (GeneralSecurityException e) { throw new IllegalStateException("ed25519 sign failed", e); }
    }

    public static boolean verify(byte[] payload, byte[] sig, PublicKey key) {
        try {
            Signature s = Signature.getInstance(ALG);
            s.initVerify(key);
            s.update(payload);
            return s.verify(sig);
        } catch (GeneralSecurityException e) { return false; }
    }

    public static byte[] artifact(Genome g, PrivateKey key) {
        byte[] payload = encode(g);
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put("alg", ALG);
        m.put("payload", payload);
        m.put("sig", sign(payload, key));
        return Cbor.encode(m);
    }

    /** FAIL-CLOSED: неверная подпись/алгоритм/структура -> исключение, геном НЕ возвращается. */
    public static Genome open(byte[] artifact, PublicKey trustedKey) {
        Map<Object, Object> m = Cbor.asMap(Cbor.decode(artifact));
        if (!ALG.equals(Cbor.str(m, "alg"))) throw new SecurityException("genome artifact: unsupported alg");
        byte[] payload = Cbor.bytes(m, "payload");
        byte[] sig = Cbor.bytes(m, "sig");
        if (!verify(payload, sig, trustedKey)) throw new SecurityException("genome artifact: signature INVALID");
        return fromCbor(Cbor.decode(payload));
    }

    public static KeyPair generateKeyPair() {
        try { return KeyPairGenerator.getInstance(ALG).generateKeyPair(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    public static String b64(java.security.Key k) { return Base64.getEncoder().encodeToString(k.getEncoded()); }

    public static PublicKey publicFromB64(String s) {
        try { return KeyFactory.getInstance(ALG).generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(s.trim()))); }
        catch (GeneralSecurityException e) { throw new IllegalArgumentException("bad ed25519 public key", e); }
    }

    public static PrivateKey privateFromB64(String s) {
        try { return KeyFactory.getInstance(ALG).generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(s.trim()))); }
        catch (GeneralSecurityException e) { throw new IllegalArgumentException("bad ed25519 private key", e); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); System.exit(2); return; }
        switch (args[0]) {
            case "genkey" -> {
                if (args.length != 2) { usage(); System.exit(2); return; }
                KeyPair kp = generateKeyPair();
                Files.writeString(Path.of(args[1] + ".pub"), b64(kp.getPublic()));
                Files.writeString(Path.of(args[1] + ".key"), b64(kp.getPrivate()));
                System.out.println("ED25519 keypair written: " + args[1] + ".pub / " + args[1] + ".key");
            }
            case "sign" -> {
                if (args.length != 4) { usage(); System.exit(2); return; }
                Genome g = args[1].equals("-") ? Genome.defaultGenome() : Genome.fromFile(Path.of(args[1]));
                PrivateKey priv = privateFromB64(Files.readString(Path.of(args[2])));
                Files.write(Path.of(args[3]), artifact(g, priv));
                System.out.println("GENOME SIGNED: " + args[3] + " objectives=" + g.objectives.size());
            }
            case "verify" -> {
                if (args.length != 3) { usage(); System.exit(2); return; }
                PublicKey pub = publicFromB64(Files.readString(Path.of(args[2])));
                Genome g = open(Files.readAllBytes(Path.of(args[1])), pub);
                System.out.println("GENOME VERIFY OK objectives=" + g.objectives.size() + " constraints=" + g.constraints.size());
            }
            default -> { usage(); System.exit(2); }
        }
    }

    private static void usage() {
        System.err.println("usage: GenomeArtifact genkey <prefix> | sign <genome.yaml|-> <keyB64> <out> | verify <artifact> <pubB64>");
    }
}
