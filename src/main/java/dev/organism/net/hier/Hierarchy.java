package dev.organism.net.hier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * Столп 5: иерархический gossip. ЧЕСТНОЕ обоснование: SWIM сам по себе infection-style (O(N log N) суммарно),
 * а не O(N^2). Иерархия выигрывает на WAN: RTT/loss/цена канала и локализация churn-штормов внутри региона.
 * Внутри региона — прямой infection-style обмен. Между регионами — ТОЛЬКО мосты-суперноды, батчами.
 * Новых Wire-типов НЕТ (sealed заморожен): оверлей использует существующие Wire.Sync и публичный API Membership.
 */
public final class Hierarchy {
    private Hierarchy() {}

    public static String regionOf(String node, Map<String, String> explicit, List<String> regions) {
        String r = explicit.get(node);
        if (r != null) return r;
        return regions.get(Integer.remainderUnsigned(score(node), regions.size()));
    }

    /** Стабильные выборы супернод: top-k по hash(region:node); churn рядовых узлов не трогает мосты. */
    public static List<String> supernodes(Collection<String> nodes, Map<String, String> explicit, List<String> regions, int perRegion) {
        Map<String, List<String>> byRegion = new TreeMap<>();
        for (String n : nodes) byRegion.computeIfAbsent(regionOf(n, explicit, regions), k -> new ArrayList<>()).add(n);
        List<String> out = new ArrayList<>();
        for (var e : byRegion.entrySet()) {
            List<String> cand = new ArrayList<>(e.getValue());
            cand.sort((a, b) -> Integer.compareUnsigned(score(e.getKey() + ":" + b), score(e.getKey() + ":" + a)));
            for (int i = 0; i < Math.min(perRegion, cand.size()); i++) out.add(cand.get(i));
        }
        return out;
    }

    public static int score(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return ((h[0] & 0xff) << 24) | ((h[1] & 0xff) << 16) | ((h[2] & 0xff) << 8) | (h[3] & 0xff);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Цели тика: k intra-region случайных + (если супернода) мосты в суперноды других регионов. */
    public static List<String> targets(String self, Collection<String> alive, Map<String, String> explicit,
                                       List<String> regions, List<String> supers, SplittableRandom rnd, int kIntra, int kBridge) {
        String myRegion = regionOf(self, explicit, regions);
        List<String> intra = new ArrayList<>();
        for (String n : alive) if (!n.equals(self) && regionOf(n, explicit, regions).equals(myRegion)) intra.add(n);
        Collections.shuffle(intra, new Random(rnd.nextLong()));
        List<String> out = new ArrayList<>(intra.subList(0, Math.min(kIntra, intra.size())));
        if (supers.contains(self)) {
            Map<String, List<String>> byRegion = new TreeMap<>();
            for (String s : supers) {
                String r = regionOf(s, explicit, regions);
                if (!r.equals(myRegion)) byRegion.computeIfAbsent(r, k -> new ArrayList<>()).add(s);
            }
            List<String> bridges = new ArrayList<>();
            for (var e : byRegion.entrySet()) bridges.add(e.getValue().get(0));
            Collections.shuffle(bridges, new Random(rnd.nextLong()));
            out.addAll(bridges.subList(0, Math.min(kBridge, bridges.size())));
        }
        return out;
    }
}
