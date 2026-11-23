package dev.organism.raft;

import dev.organism.security.AuditLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Детерминированный Raft selftest на in-memory транспорте: выборы, репликация, kill лидера, партиция, replay. */
public final class RaftSelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-62s %s%n", ok ? "OK " : "FAIL", name, detail);
    }

    static final class Sm implements RaftNode.StateMachine {
        final List<Long> applied = new ArrayList<>();
        final List<String> cmds = new ArrayList<>();
        @Override public void apply(long i, byte[] c) { applied.add(i); cmds.add(new String(c, StandardCharsets.UTF_8)); }
    }

    static long clock;
    static void step(Map<String, RaftNode> nodes, RaftNet.InMem net, long ms) {
        for (long end = clock + ms; clock < end; clock += 10) {
            for (RaftNode n : nodes.values()) if (!net.down.contains(n.id)) n.tick(clock);
            net.deliverAll();
        }
    }

    public static void main(String[] args) throws Exception {
        RaftNet.InMem net = new RaftNet.InMem();
        List<String> ids = List.of("n1", "n2", "n3");
        Map<String, RaftNode> nodes = new LinkedHashMap<>();
        Map<String, Sm> sms = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            String id = ids.get(i);
            List<String> peers = new ArrayList<>(ids);
            peers.remove(id);
            Sm sm = new Sm();
            sms.put(id, sm);
            RaftNode n = new RaftNode(id, peers, net, new RaftStore.Mem(), sm, 100 + i);
            nodes.put(id, n);
            net.register(n);
        }
        String leader = null;
        for (int i = 0; i < 300 && leader == null; i++) { step(nodes, net, 10); leader = findLeader(nodes); }
        check("leader elected", leader != null, "leader=" + leader);
        check("exactly one leader", countLeaders(nodes) == 1, "");
        long term1 = nodes.get(leader).term();

        for (int i = 1; i <= 50; i++) RaftAuditBridge.propose(nodes.get(leader), "E", "v" + i);
        step(nodes, net, 500);
        boolean allCommitted = true, allApplied = true;
        for (RaftNode n : nodes.values()) allCommitted &= n.commitIndex() == 50;
        for (Sm sm : sms.values()) allApplied &= sm.applied.size() == 50;
        check("50 entries committed on all", allCommitted, "");
        check("state machines applied 50", allApplied, "");
        boolean match = true;
        for (long i = 1; i <= 50; i++) {
            byte[] c0 = nodes.get(leader).entry(i).cmd();
            for (RaftNode n : nodes.values()) match &= Arrays.equals(c0, n.entry(i).cmd());
        }
        check("log matching property", match, "");

        net.down.add(leader);
        String leader2 = null;
        for (int i = 0; i < 400 && leader2 == null; i++) { step(nodes, net, 10); leader2 = findLeaderAlive(nodes, net); }
        check("new leader after kill", leader2 != null && !leader2.equals(leader), "leader2=" + leader2);
        check("term increased", nodes.get(leader2).term() > term1, "");
        for (int i = 51; i <= 60; i++) RaftAuditBridge.propose(nodes.get(leader2), "E", "v" + i);
        step(nodes, net, 500);
        check("majority commits without dead leader", nodes.get(leader2).commitIndex() == 60, "");

        net.down.remove(leader);
        step(nodes, net, 2000);
        check("old leader caught up", nodes.get(leader).commitIndex() == 60, "");
        check("old leader stepped down", nodes.get(leader).role() == RaftNode.Role.FOLLOWER, "");

        String cur = findLeader(nodes);
        String minId = null;
        for (String id : ids) if (!id.equals(cur)) { minId = id; break; }
        net.partition(List.of(minId));
        for (int i = 61; i <= 65; i++) RaftAuditBridge.propose(nodes.get(cur), "E", "p" + i);
        step(nodes, net, 1000);
        check("majority commits under partition", nodes.get(cur).commitIndex() == 65, "");
        check("minority cannot commit", nodes.get(minId).commitIndex() == 60, "");
        net.heal();
        step(nodes, net, 3000);
        check("partition healed, minority caught up", nodes.get(minId).commitIndex() == 65, "");

        Path dir = Files.createTempDirectory("org-raft");
        RaftStore.File fs = new RaftStore.File(dir.resolve("raft.log"));
        AuditLog audit = new AuditLog(dir.resolve("audit.log"), "raft-secret");
        RaftAuditBridge bridge = new RaftAuditBridge(audit, dir.resolve("applied"));
        RaftNode solo = new RaftNode("solo", List.of(), new RaftNet.InMem(), fs, bridge, 7);
        for (int i = 0; i < 50; i++) solo.tick(i * 10L);
        check("single node becomes leader", solo.role() == RaftNode.Role.LEADER, "");
        for (int i = 1; i <= 5; i++) RaftAuditBridge.propose(solo, "AUDIT", "e" + i);
        check("single node commit immediate", solo.commitIndex() == 5, "");
        audit.close();
        AuditLog audit2 = new AuditLog(dir.resolve("audit.log"), "raft-secret");
        RaftAuditBridge bridge2 = new RaftAuditBridge(audit2, dir.resolve("applied"));
        RaftNode solo2 = new RaftNode("solo", List.of(), new RaftNet.InMem(), new RaftStore.File(dir.resolve("raft.log")), bridge2, 7);
        for (int i = 50; i < 120; i++) solo2.tick(i * 10L);
        RaftAuditBridge.propose(solo2, "AUDIT", "e6");
        long lines = Files.readAllLines(dir.resolve("audit.log")).size();
        check("replay idempotent, no duplicate audit", lines == 6, "lines=" + lines);
        check("audit hash-chain verifies", AuditLog.verify(dir.resolve("audit.log"), "raft-secret"), "");
        audit2.close();

        System.out.println(failed == 0 ? "RAFT SELFTEST PASSED" : "RAFT SELFTEST FAILED: " + failed);
        if (failed > 0) System.exit(1);
    }

    static String findLeader(Map<String, RaftNode> nodes) {
        for (var e : nodes.entrySet()) if (e.getValue().role() == RaftNode.Role.LEADER) return e.getKey();
        return null;
    }
    static String findLeaderAlive(Map<String, RaftNode> nodes, RaftNet.InMem net) {
        for (var e : nodes.entrySet()) if (!net.down.contains(e.getKey()) && e.getValue().role() == RaftNode.Role.LEADER) return e.getKey();
        return null;
    }
    static int countLeaders(Map<String, RaftNode> nodes) {
        int c = 0;
        for (var n : nodes.values()) if (n.role() == RaftNode.Role.LEADER) c++;
        return c;
    }
}
