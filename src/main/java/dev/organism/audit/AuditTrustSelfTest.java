package dev.organism.audit;

import dev.organism.security.AuditLog;
import dev.organism.supplychain.GenomeArtifact;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Детерминированный selftest столпа 6: Merkle-пруфы, TSA-якоря, tamper/truncate против hash-chain AuditLog. */
public final class AuditTrustSelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-62s %s%n", ok ? "OK " : "FAIL", name, detail);
    }

    public static void main(String[] args) throws Exception {
        for (int size : new int[]{1, 2, 3, 5, 7, 8, 64, 100}) {
            List<byte[]> data = new ArrayList<>();
            for (int i = 0; i < size; i++) data.add(("event-" + i).getBytes(StandardCharsets.UTF_8));
            MerkleTree tree = new MerkleTree(data);
            boolean all = true;
            for (int i = 0; i < size; i++) all &= MerkleTree.verify(data.get(i), i, size, tree.proof(i), tree.root());
            check("merkle proofs size=" + size, all, "");
            if (size > 1) {
                check("merkle tamper detected size=" + size,
                        !MerkleTree.verify("event-X".getBytes(StandardCharsets.UTF_8), 0, size, tree.proof(0), tree.root()), "");
                check("merkle wrong index rejected size=" + size,
                        !MerkleTree.verify(data.get(0), 1, size, tree.proof(0), tree.root()), "");
            }
        }
        check("merkle empty root stable", Arrays.equals(new MerkleTree(List.of()).root(), MerkleTree.sha256(new byte[0])), "");

        Path dir = Files.createTempDirectory("org-audit");
        Path auditFile = dir.resolve("audit.log");
        AuditLog log = new AuditLog(auditFile, "trust-secret");
        for (int i = 0; i < 100; i++) log.log("EVT", "payload-" + i);
        KeyPair tsaKey = GenomeArtifact.generateKeyPair();
        AtomicLong clock = new AtomicLong(1000000);
        Tsa tsa = new Tsa(tsaKey.getPrivate(), "org-tsa", () -> clock.addAndGet(3600000));
        Tsa.AnchorStore store = new Tsa.AnchorStore(dir.resolve("anchors.b64"));
        AuditAnchoring anchoring = new AuditAnchoring(auditFile, tsa, store);
        byte[] root1 = anchoring.anchorNow();
        check("anchor verifies against current log", anchoring.verifyLatest(tsaKey.getPublic()), "");
        check("anchor chain ok", store.verifyChain(tsaKey.getPublic()), "");
        List<byte[]> leaves = anchoring.leaves();
        MerkleTree t1 = new MerkleTree(leaves);
        check("inclusion proof event#42 vs anchored root",
                MerkleTree.verify(leaves.get(42), 42, leaves.size(), t1.proof(42), root1), "");
        for (int i = 100; i < 150; i++) log.log("EVT", "payload-" + i);
        byte[] root2 = anchoring.anchorNow();
        check("roots differ after new events", !Arrays.equals(root1, root2), "");
        List<byte[]> tokens = store.load();
        check("serial monotonic", Tsa.serialOf(tokens.get(0)) == 1 && Tsa.serialOf(tokens.get(1)) == 2, "");
        check("ts monotonic", Tsa.tsOf(tokens.get(1)) > Tsa.tsOf(tokens.get(0)), "");
        check("chain still ok", store.verifyChain(tsaKey.getPublic()), "");
        check("latest verifies", anchoring.verifyLatest(tsaKey.getPublic()), "");
        check("old root no longer matches current log",
                !Tsa.verify(tokens.get(0), tsaKey.getPublic(), new MerkleTree(anchoring.leaves()).root()), "");

        List<String> lines = Files.readAllLines(auditFile, StandardCharsets.UTF_8);
        lines.set(10, lines.get(10).replace("payload-10", "payload-EVIL"));
        Files.write(auditFile, lines, StandardCharsets.UTF_8);
        check("hash-chain detects tamper", !AuditLog.verify(auditFile, "trust-secret"), "");
        check("merkle anchor detects tamper", !anchoring.verifyLatest(tsaKey.getPublic()), "");
        byte[] tok = tokens.get(1).clone();
        tok[tok.length / 2] ^= 1;
        check("TSA token tamper rejected", !Tsa.verify(tok, tsaKey.getPublic(), root2), "");
        KeyPair other = GenomeArtifact.generateKeyPair();
        check("TSA wrong key rejected", !Tsa.verify(tokens.get(1), other.getPublic(), root2), "");

        Path audit2 = dir.resolve("audit2.log");
        AuditLog log2 = new AuditLog(audit2, "s2");
        for (int i = 0; i < 10; i++) log2.log("X", "y" + i);
        Tsa.AnchorStore store2 = new Tsa.AnchorStore(dir.resolve("anchors2.b64"));
        AuditAnchoring anch2 = new AuditAnchoring(audit2, tsa, store2);
        anch2.anchorNow();
        List<String> l2 = Files.readAllLines(audit2, StandardCharsets.UTF_8);
        Files.write(audit2, l2.subList(0, l2.size() - 1), StandardCharsets.UTF_8);
        check("tail truncation breaks anchor", !anch2.verifyLatest(tsaKey.getPublic()), "");
        log.close();
        log2.close();

        System.out.println(failed == 0 ? "AUDIT TRUST SELFTEST PASSED" : "AUDIT TRUST SELFTEST FAILED: " + failed);
        if (failed > 0) System.exit(1);
    }
}
