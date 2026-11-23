package dev.organism.audit;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Столп 6: Merkle Tree (RFC 6962-style): leaf=SHA256(0x00||data), node=SHA256(0x01||l||r).
 * Нечётный узел ПРОДВИГАЕТСЯ наверх (не дублируется — нет second-preimage трюка с последним листом).
 * Inclusion proof проверяется против корня, заякоренного в TSA: подчистка лога видна без полного перечитывания.
 */
public final class MerkleTree {
    private final List<List<byte[]>> levels = new ArrayList<>();
    private final int size;

    public MerkleTree(List<byte[]> data) {
        size = data.size();
        List<byte[]> cur = new ArrayList<>();
        for (byte[] d : data) cur.add(leafHash(d));
        levels.add(cur);
        while (cur.size() > 1) {
            List<byte[]> next = new ArrayList<>();
            for (int i = 0; i < cur.size(); i += 2) {
                if (i + 1 < cur.size()) next.add(nodeHash(cur.get(i), cur.get(i + 1)));
                else next.add(cur.get(i));
            }
            levels.add(next);
            cur = next;
        }
    }

    public int size() { return size; }

    public byte[] root() {
        if (size == 0) return sha256(new byte[0]);
        return levels.get(levels.size() - 1).get(0).clone();
    }

    public List<byte[]> proof(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("merkle index " + index);
        List<byte[]> out = new ArrayList<>();
        int i = index, n = size;
        for (int lvl = 0; n > 1; lvl++) {
            List<byte[]> cur = levels.get(lvl);
            if (i % 2 == 1) out.add(cur.get(i - 1).clone());
            else if (i + 1 < n) out.add(cur.get(i + 1).clone());
            i /= 2;
            n = (n + 1) / 2;
        }
        return out;
    }

    public static boolean verify(byte[] data, int index, int size, List<byte[]> proof, byte[] root) {
        if (index < 0 || index >= size) return false;
        byte[] h = leafHash(data);
        int i = index, n = size, p = 0;
        while (n > 1) {
            if (i % 2 == 1) {
                if (p >= proof.size()) return false;
                h = nodeHash(proof.get(p++), h);
            } else if (i + 1 < n) {
                if (p >= proof.size()) return false;
                h = nodeHash(h, proof.get(p++));
            }
            i /= 2;
            n = (n + 1) / 2;
        }
        return p == proof.size() && MessageDigest.isEqual(h, root);
    }

    public static byte[] leafHash(byte[] data) {
        MessageDigest md = md();
        md.update((byte) 0x00);
        return md.digest(data);
    }

    public static byte[] nodeHash(byte[] l, byte[] r) {
        MessageDigest md = md();
        md.update((byte) 0x01);
        md.update(l);
        return md.digest(r);
    }

    public static byte[] sha256(byte[] b) { return md().digest(b); }

    private static MessageDigest md() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
