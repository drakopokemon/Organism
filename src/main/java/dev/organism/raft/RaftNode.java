package dev.organism.raft;

import dev.organism.supplychain.Cbor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Raft (leader election + log replication + commit) на JDK, без внешних зависимостей.
 * Столп 2: ЕДИНЫЙ порядок аудита/мутаций, без форков журнала. Неотказуемость — отдельно (FIDO2+Merkle/TSA).
 * Детерминизм: всё время — внешние tick(nowMs); election timeout рандомизирован seeded RNG.
 * Транспорт инкапсулирован (in-memory для тестов, TCP для прода); RPC = канонический CBOR.
 * Single-node (peers пуст) — мгновенный лидер для edge-автономии.
 */
public final class RaftNode {
    public enum Role { FOLLOWER, CANDIDATE, LEADER }
    public record Entry(long term, long index, byte[] cmd) {}
    public record HardState(long term, String votedFor) {}
    public interface Transport { void send(String to, byte[] frame); }
    public interface StateMachine { void apply(long index, byte[] cmd); }
    public interface Store {
        List<Entry> load();
        void append(Entry e);
        void truncateFrom(long index);
        HardState loadVote();
        void saveVote(long term, String votedFor);
    }

    public final String id;
    private final List<String> peers;
    private final Transport tx;
    private final Store store;
    private final StateMachine sm;
    private final SplittableRandom rnd;

    private long term;
    private String votedFor;
    private final List<Entry> log = new ArrayList<>();
    private long commitIndex, lastApplied;
    private Role role = Role.FOLLOWER;
    private String leaderId;
    private long now, electionDeadline, lastHeartbeat;
    private final Set<String> votes = new HashSet<>();
    private final Map<String, Long> nextIndex = new HashMap<>();
    private final Map<String, Long> matchIndex = new HashMap<>();
    private final long heartbeatMs = 50;

    public RaftNode(String id, List<String> peers, Transport tx, Store store, StateMachine sm, long seed) {
        this.id = id;
        this.peers = List.copyOf(peers);
        this.tx = tx;
        this.store = store;
        this.sm = sm;
        this.rnd = new SplittableRandom(seed);
        log.addAll(store.load());
        HardState hs = store.loadVote();
        term = hs == null ? 0 : hs.term();
        votedFor = hs == null ? null : hs.votedFor();
        resetElection();
    }

    public synchronized void tick(long nowMs) {
        now = nowMs;
        if (role != Role.LEADER && now >= electionDeadline) startElection();
        if (role == Role.LEADER && now - lastHeartbeat >= heartbeatMs) broadcastAppend();
        applyCommitted();
    }

    /** Только лидер принимает команды. Возвращает index или -1 (не лидер). */
    public synchronized long propose(byte[] cmd) {
        if (role != Role.LEADER) return -1;
        Entry e = new Entry(term, lastIndex() + 1, cmd.clone());
        log.add(e);
        store.append(e);
        if (peers.isEmpty()) { commitIndex = e.index(); applyCommitted(); }
        else broadcastAppend();
        return e.index();
    }

    public synchronized void onFrame(String from, byte[] frame) {
        Map<Object, Object> m = Cbor.asMap(Cbor.decode(frame));
        String t = Cbor.str(m, "t");
        long msgTerm = Cbor.lng(m, "term");
        if (msgTerm > term) stepDown(msgTerm);
        switch (t) {
            case "rv" -> {
                String cand = Cbor.str(m, "cand");
                long lastIdx = Cbor.lng(m, "lastIdx"), lastTerm = Cbor.lng(m, "lastTerm");
                boolean grant = false;
                if (msgTerm == term && (votedFor == null || votedFor.equals(cand)) && upToDate(lastIdx, lastTerm)) {
                    grant = true;
                    votedFor = cand;
                    store.saveVote(term, votedFor);
                    resetElection();
                }
                Map<Object, Object> r = rpc("vr");
                r.put("term", term);
                r.put("grant", grant);
                tx.send(cand, Cbor.encode(r));
            }
            case "vr" -> {
                if (role == Role.CANDIDATE && msgTerm == term && Cbor.bool(m, "grant")) {
                    votes.add(from);
                    if (votes.size() > (peers.size() + 1) / 2) becomeLeader();
                }
            }
            case "ae" -> {
                String leader = Cbor.str(m, "leader");
                long prevIdx = Cbor.lng(m, "prevIdx"), prevTerm = Cbor.lng(m, "prevTerm");
                long lcommit = Cbor.lng(m, "commit");
                boolean ok = false;
                long match = 0;
                if (msgTerm == term) {
                    if (role == Role.CANDIDATE) role = Role.FOLLOWER;
                    leaderId = leader;
                    resetElection();
                    Entry prev = entryAt(prevIdx);
                    if (prevIdx == 0 || (prev != null && prev.term() == prevTerm)) {
                        ok = true;
                        for (Object o : Cbor.list(m, "entries")) {
                            Map<Object, Object> em = Cbor.asMap(o);
                            long idx = Cbor.lng(em, "index"), et = Cbor.lng(em, "term");
                            byte[] cmd = Cbor.bytes(em, "cmd");
                            Entry ex = entryAt(idx);
                            if (ex != null) {
                                if (ex.term() == et) continue;
                                store.truncateFrom(idx);
                                while (log.size() >= idx) log.remove(log.size() - 1);
                            }
                            if (idx != lastIndex() + 1) throw new IllegalArgumentException("raft: non-contiguous append");
                            Entry e = new Entry(et, idx, cmd);
                            log.add(e);
                            store.append(e);
                        }
                        match = lastIndex();
                        if (lcommit > commitIndex) commitIndex = Math.min(lcommit, lastIndex());
                    }
                }
                Map<Object, Object> r = rpc("ar");
                r.put("term", term);
                r.put("ok", ok);
                r.put("match", match);
                tx.send(leader, Cbor.encode(r));
            }
            case "ar" -> {
                if (role == Role.LEADER && msgTerm == term) {
                    if (Cbor.bool(m, "ok")) {
                        long match = Cbor.lng(m, "match");
                        matchIndex.put(from, Math.max(matchIndex.getOrDefault(from, 0L), match));
                        nextIndex.put(from, matchIndex.get(from) + 1);
                        maybeCommit();
                    } else {
                        nextIndex.put(from, Math.max(1, nextIndex.getOrDefault(from, 2L) - 1));
                        sendAppend(from);
                    }
                }
            }
            default -> throw new IllegalArgumentException("raft: unknown rpc " + t);
        }
        applyCommitted();
    }

    private void startElection() {
        role = Role.CANDIDATE;
        term++;
        votedFor = id;
        store.saveVote(term, votedFor);
        votes.clear();
        votes.add(id);
        leaderId = null;
        resetElection();
        if (peers.isEmpty()) { becomeLeader(); return; }
        Map<Object, Object> m = rpc("rv");
        m.put("term", term);
        m.put("cand", id);
        m.put("lastIdx", lastIndex());
        m.put("lastTerm", lastTerm());
        byte[] f = Cbor.encode(m);
        for (String p : peers) tx.send(p, f);
    }

    private void becomeLeader() {
        role = Role.LEADER;
        leaderId = id;
        nextIndex.clear();
        matchIndex.clear();
        for (String p : peers) { nextIndex.put(p, lastIndex() + 1); matchIndex.put(p, 0L); }
        lastHeartbeat = now;
        if (peers.isEmpty()) commitIndex = lastIndex();
        else broadcastAppend();
        applyCommitted();
    }

    private void broadcastAppend() {
        lastHeartbeat = now;
        for (String p : peers) sendAppend(p);
    }

    private void sendAppend(String p) {
        long ni = nextIndex.getOrDefault(p, lastIndex() + 1);
        long prevIdx = ni - 1;
        long prevTerm = prevIdx == 0 ? 0 : entryAt(prevIdx).term();
        List<Object> es = new ArrayList<>();
        for (long i = ni; i <= lastIndex(); i++) {
            Entry e = log.get((int) i - 1);
            Map<Object, Object> em = new LinkedHashMap<>();
            em.put("term", e.term());
            em.put("index", e.index());
            em.put("cmd", e.cmd());
            es.add(em);
        }
        Map<Object, Object> m = rpc("ae");
        m.put("term", term);
        m.put("leader", id);
        m.put("prevIdx", prevIdx);
        m.put("prevTerm", prevTerm);
        m.put("entries", es);
        m.put("commit", commitIndex);
        tx.send(p, Cbor.encode(m));
    }

    private void maybeCommit() {
        long total = peers.size() + 1;
        for (long n = lastIndex(); n > commitIndex; n--) {
            Entry e = entryAt(n);
            if (e == null || e.term() != term) continue;
            long count = 1;
            for (String p : peers) if (matchIndex.getOrDefault(p, 0L) >= n) count++;
            if (count > total / 2) { commitIndex = n; break; }
        }
    }

    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            Entry e = entryAt(lastApplied);
            if (e != null) sm.apply(e.index(), e.cmd());
        }
    }

    private void stepDown(long newTerm) {
        term = newTerm;
        votedFor = null;
        store.saveVote(term, null);
        role = Role.FOLLOWER;
        leaderId = null;
        resetElection();
    }

    private boolean upToDate(long lastIdx, long lastTerm) {
        long myTerm = lastTerm(), myIdx = lastIndex();
        return lastTerm > myTerm || (lastTerm == myTerm && lastIdx >= myIdx);
    }

    private void resetElection() { electionDeadline = now + 150 + rnd.nextInt(150); }

    private Map<Object, Object> rpc(String t) {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put("t", t);
        m.put("src", id);
        return m;
    }

    private Entry entryAt(long idx) { return (idx <= 0 || idx > log.size()) ? null : log.get((int) idx - 1); }
    private long lastTerm() { return log.isEmpty() ? 0 : log.get(log.size() - 1).term(); }

    public synchronized Role role() { return role; }
    public synchronized long term() { return term; }
    public synchronized long commitIndex() { return commitIndex; }
    public synchronized long applied() { return lastApplied; }
    public synchronized long lastIndex() { return log.size(); }
    public synchronized String leaderId() { return leaderId; }
    public synchronized Entry entry(long idx) { return entryAt(idx); }
}
