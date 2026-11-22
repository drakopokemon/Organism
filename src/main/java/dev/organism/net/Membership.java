package dev.organism.net;

import java.util.*;

/**
 * SWIM-lite: ping -> ping-req(k=3) -> suspect -> dead + push-pull anti-entropy.
 * Транспорт инъектируется => детерминированные тесты без сокетов.
 * Outbox флашится ПОСЛЕ обработки — никаких реентерабельных мутаций таблицы.
 * Слияние: больший incarnation побеждает; при равном DEAD > SUSPECT > ALIVE;
 * прямой Ack лечит SUSPECT, но не воскрешает DEAD — для этого refute с bump incarnation.
 * Anti-entropy синкается и с DEAD — иначе остров после partition не воскреснет.
 */
public final class Membership {
    public interface Transport { void send(String to, Wire msg); }
    public interface Listener { void onChange(long tick, String node, Wire.Status from, Wire.Status to, int incarnation); }

    static final class E {
        int inc; Wire.Status st; int suspLeft; Wire.NodeFacts facts;
        E(int inc, Wire.Status st) { this.inc = inc; this.st = st; }
    }
    record Fwd(String requester, String target, long origSeq, long at) {}

    public final String self;
    private final Transport tx;
    private final Listener listener;
    private final SplittableRandom rnd;
    private final TreeMap<String, E> table = new TreeMap<>();
    private final HashMap<Long, String> pendingPing = new HashMap<>();
    private final HashMap<Long, Integer> probeAge = new HashMap<>();
    private final HashMap<Long, Fwd> forwarded = new HashMap<>();
    private final ArrayDeque<Object[]> outbox = new ArrayDeque<>();
    private long seqGen, tick;
    private int incarnation;
    private Wire.NodeFacts myFacts;

    public Membership(String self, long seed, Transport tx, Listener listener) {
        this.self = self; this.tx = tx; this.listener = listener;
        this.rnd = new SplittableRandom(seed);
        table.put(self, new E(0, Wire.Status.ALIVE));
    }

    public synchronized void addSeed(String node) {
        if (!node.equals(self)) table.putIfAbsent(node, new E(0, Wire.Status.ALIVE));
    }
    public synchronized void setFacts(Wire.NodeFacts f) { myFacts = f; }
    public synchronized Wire.Status status(String node) { var e = table.get(node); return e == null ? null : e.st; }
    public synchronized Wire.NodeFacts facts(String node) { var e = table.get(node); return e == null ? null : e.facts; }
    public synchronized int incarnation() { return incarnation; }
    public synchronized long tickCount() { return tick; }
    public synchronized Map<String, Wire.Status> view() {
        var m = new TreeMap<String, Wire.Status>();
        table.forEach((k, v) -> m.put(k, v.st));
        return m;
    }

    /** Один протокольный период. Кернел вызывает раз в period-ms. */
    public synchronized void tick() {
        tick++;
        forwarded.values().removeIf(f -> tick - f.at() > 2);
        ageProbes();
        probeRandom();
        antiEntropy();
        ageSuspicion();
        flush();
    }

    public synchronized void onMessage(Wire msg) {
        switch (msg) {
            case Wire.Ping p -> {
                aliveEvidence(p.from(), p.incarnation(), true);
                send(p.from(), new Wire.Ack(self, incarnation, p.seq()));
            }
            case Wire.Ack a -> {
                aliveEvidence(a.from(), a.incarnation(), true);
                pendingPing.remove(a.seq()); probeAge.remove(a.seq());
                var f = forwarded.remove(a.seq());
                if (f != null) {
                    var te = table.get(f.target());
                    send(f.requester(), new Wire.Ack(f.target(), te == null ? 0 : te.inc, f.origSeq()));
                }
            }
            case Wire.PingReq r -> {
                aliveEvidence(r.from(), r.incarnation(), true);
                var e = table.get(r.target());
                if (e != null && e.st != Wire.Status.DEAD) {
                    long s = ++seqGen;
                    forwarded.put(s, new Fwd(r.from(), r.target(), r.seq(), tick));
                    send(r.target(), new Wire.Ping(self, incarnation, s));
                }
            }
            case Wire.Sync s -> {
                for (var m : s.members()) merge(m.node(), m.incarnation(), m.status());
                if (s.pull()) sendSync(s.from(), false);
            }
            case Wire.NodeFacts f -> {
                aliveEvidence(f.node(), f.incarnation(), false);
                var e = table.get(f.node());
                if (e != null && (e.facts == null
                        || f.incarnation() > e.facts.incarnation()
                        || (f.incarnation() == e.facts.incarnation() && f.seq() > e.facts.seq())))
                    e.facts = f;
            }
        }
        flush();
    }

    private void send(String to, Wire m) { outbox.add(new Object[]{to, m}); }
    private void flush() {
        Object[] x;
        while ((x = outbox.poll()) != null) tx.send((String) x[0], (Wire) x[1]);
    }

    private List<String> peers(Wire.Status max) {
        var l = new ArrayList<String>();
        for (var e : table.entrySet())
            if (!e.getKey().equals(self) && e.getValue().st.ordinal() <= max.ordinal()) l.add(e.getKey());
        return l;
    }
    private String pick(List<String> l) { return l.isEmpty() ? null : l.get(rnd.nextInt(l.size())); }
    private List<String> pickK(List<String> l, int k) {
        var copy = new ArrayList<>(l); var out = new ArrayList<String>();
        while (!copy.isEmpty() && out.size() < k) out.add(copy.remove(rnd.nextInt(copy.size())));
        return out;
    }

    private void probeRandom() {
        String t = pick(peers(Wire.Status.SUSPECT));
        if (t == null) return;
        long s = ++seqGen;
        pendingPing.put(s, t); probeAge.put(s, 0);
        send(t, new Wire.Ping(self, incarnation, s));
    }

    private void ageProbes() {
        for (var it = probeAge.entrySet().iterator(); it.hasNext();) {
            var en = it.next();
            long s = en.getKey(); int age = en.getValue() + 1;
            String target = pendingPing.get(s);
            if (target == null) { it.remove(); continue; }
            if (age == 1) {
                var others = peers(Wire.Status.SUSPECT); others.remove(target);
                for (var mid : pickK(others, 3))
                    send(mid, new Wire.PingReq(self, target, incarnation, s));
                en.setValue(age);
            } else {
                var e = table.get(target);
                if (e != null) suspect(target, e.inc);
                pendingPing.remove(s); it.remove();
            }
        }
    }

    private void antiEntropy() {
        String p = pick(peers(Wire.Status.DEAD));   // и с DEAD тоже — шанс на refute вернувшемуся
        if (p == null) return;
        sendSync(p, true);
        if (myFacts != null) send(p, myFacts);
    }

    private void sendSync(String to, boolean pull) {
        var members = new ArrayList<Wire.Member>();
        table.forEach((k, v) -> members.add(new Wire.Member(k, v.inc, v.st)));
        for (int i = 0; i < members.size(); i += 40)
            send(to, new Wire.Sync(self, pull, List.copyOf(members.subList(i, Math.min(i + 40, members.size())))));
    }

    private int suspicionTicks() { return Math.max(2, (int) Math.ceil(Math.log10(table.size() + 1))); }

    private void ageSuspicion() {
        for (var e : table.entrySet()) {
            var v = e.getValue();
            if (v.st == Wire.Status.SUSPECT && --v.suspLeft <= 0)
                transition(e.getKey(), v, v.inc, Wire.Status.DEAD);
        }
    }

    private void suspect(String node, int inc) {
        if (node.equals(self)) { refute(inc); return; }
        var e = table.get(node);
        if (e == null) return;
        if (inc > e.inc || (inc == e.inc && e.st == Wire.Status.ALIVE))
            transition(node, e, inc, Wire.Status.SUSPECT);
    }

    private void transition(String node, E e, int inc, Wire.Status to) {
        var from = e.st;
        e.inc = inc; e.st = to;
        e.suspLeft = to == Wire.Status.SUSPECT ? suspicionTicks() : 0;
        listener.onChange(tick, node, from, to, inc);
    }

    private void refute(int badInc) {
        incarnation = Math.max(incarnation, badInc) + 1;
        var e = table.get(self);
        var old = e.st;
        e.inc = incarnation; e.st = Wire.Status.ALIVE; e.suspLeft = 0;
        listener.onChange(tick, self, old, Wire.Status.ALIVE, incarnation);
        for (var p : pickK(peers(Wire.Status.DEAD), 3)) sendSync(p, false);
    }

    private void aliveEvidence(String node, int inc, boolean direct) {
        if (node.equals(self)) return;
        var e = table.get(node);
        if (e == null) {
            e = new E(inc, Wire.Status.ALIVE);
            table.put(node, e);
            listener.onChange(tick, node, null, Wire.Status.ALIVE, inc);
            return;
        }
        if (inc > e.inc) { transition(node, e, inc, Wire.Status.ALIVE); return; }
        if (inc == e.inc && direct && e.st == Wire.Status.SUSPECT)
            transition(node, e, inc, Wire.Status.ALIVE);
    }

    private void merge(String node, int inc, Wire.Status st) {
        if (node.equals(self)) {
            if (st != Wire.Status.ALIVE && inc >= incarnation) refute(inc);
            return;
        }
        var e = table.get(node);
        if (e == null) {
            e = new E(inc, st);
            e.suspLeft = st == Wire.Status.SUSPECT ? suspicionTicks() : 0;
            table.put(node, e);
            listener.onChange(tick, node, null, st, inc);
            return;
        }
        if (inc > e.inc || (inc == e.inc && st.ordinal() > e.st.ordinal()))
            transition(node, e, inc, st);
    }
}
