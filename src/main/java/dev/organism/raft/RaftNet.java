package dev.organism.raft;

import dev.organism.supplychain.Cbor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** Транспорты Raft: детерминированная in-memory сеть (партиции/kill) и TCP (прод, virtual threads). */
public final class RaftNet {
    private RaftNet() {}

    public static final class InMem implements RaftNode.Transport {
        public final Map<String, RaftNode> nodes = new HashMap<>();
        public final Set<String> down = new HashSet<>();
        public final Set<String> blocked = new HashSet<>();
        private final ArrayDeque<Object[]> queue = new ArrayDeque<>();
        public long delivered, dropped;

        public void register(RaftNode n) { nodes.put(n.id, n); }
        @Override public void send(String to, byte[] frame) { queue.add(new Object[]{to, frame}); }

        public void deliverAll() {
            int guard = 0;
            while (!queue.isEmpty() && guard++ < 100000) {
                Object[] x = queue.poll();
                String to = (String) x[0];
                byte[] f = (byte[]) x[1];
                Map<Object, Object> m = Cbor.asMap(Cbor.decode(f));
                String src = Cbor.str(m, "src");
                RaftNode dst = nodes.get(to);
                if (dst == null || down.contains(src) || down.contains(to)
                        || blocked.contains(src + ">" + to) || blocked.contains(to + ">" + src)) { dropped++; continue; }
                delivered++;
                dst.onFrame(src, f);
            }
        }

        public void partition(List<String> sideA) {
            blocked.clear();
            for (String a : sideA)
                for (String b : nodes.keySet())
                    if (!sideA.contains(b)) blocked.add(a + ">" + b);
        }

        public void heal() { blocked.clear(); }
    }

    public static final class Tcp implements RaftNode.Transport, AutoCloseable {
        private final Map<String, InetSocketAddress> addrs;
        private final Map<String, Socket> out = new ConcurrentHashMap<>();
        private final ServerSocket server;
        private final BiConsumer<String, byte[]> onFrame;
        private volatile boolean running = true;

        public Tcp(int port, Map<String, InetSocketAddress> addrs, BiConsumer<String, byte[]> onFrame) throws IOException {
            this.addrs = addrs;
            this.onFrame = onFrame;
            this.server = new ServerSocket(port);
            Thread.ofVirtual().name("raft-tcp-accept").start(this::acceptLoop);
        }

        public int port() { return server.getLocalPort(); }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket s = server.accept();
                    Thread.ofVirtual().start(() -> serve(s));
                } catch (IOException e) {
                    if (!running) return;
                }
            }
        }

        private void serve(Socket s) {
            try (s) {
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                while (running) {
                    int len = in.readInt();
                    if (len <= 0 || len > 1_048_576) return;
                    byte[] f = new byte[len];
                    in.readFully(f);
                    Map<Object, Object> m = Cbor.asMap(Cbor.decode(f));
                    onFrame.accept(Cbor.str(m, "src"), f);
                }
            } catch (Exception ignored) { }
        }

        @Override public void send(String to, byte[] frame) {
            InetSocketAddress a = addrs.get(to);
            if (a == null) return;
            try {
                Socket s = out.get(to);
                if (s == null || s.isClosed() || !s.isConnected()) {
                    s = new Socket();
                    s.connect(a, 2000);
                    s.setTcpNoDelay(true);
                    out.put(to, s);
                }
                DataOutputStream o = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                o.writeInt(frame.length);
                o.write(frame);
                o.flush();
            } catch (IOException e) {
                Socket old = out.remove(to);
                if (old != null) try { old.close(); } catch (IOException ignored) { }
            }
        }

        @Override public void close() {
            running = false;
            try { server.close(); } catch (IOException ignored) { }
            for (Socket s : out.values()) try { s.close(); } catch (IOException ignored) { }
        }
    }
}
