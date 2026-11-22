package dev.organism.net;

import dev.organism.cell.CellHost;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * TissueKernel v2 — кернел узла ORGANISM с клетками.
 *
 * Запуск (3 терминала):
 *   java -cp out dev.organism.net.TissueKernel --port 7101 --cells-port 8101 --seeds 127.0.0.1:7102,127.0.0.1:7103
 *   java -cp out dev.organism.net.TissueKernel --port 7102 --cells-port 8102 --seeds 127.0.0.1:7101
 *   java -cp out dev.organism.net.TissueKernel --port 7103 --cells-port 8103 --seeds 127.0.0.1:7101
 *
 * Админ-HTTP: GET /cells (TSV: id\tport\tweight), GET /health.
 * Gossip-порт: --port. Cells-порт: --cells-port (default 8100).
 */
public final class TissueKernel {
    public static void main(String[] args) throws Exception {
        var a = parse(args);
        int port = Integer.parseInt(a.getOrDefault("port", "7100"));
        int cellsPort = Integer.parseInt(a.getOrDefault("cells-port", "8100"));
        String advertise = a.getOrDefault("advertise", "127.0.0.1:" + port);
        String bind = a.getOrDefault("bind", "0.0.0.0");
        long periodMs = Long.parseLong(a.getOrDefault("period-ms", "1000"));
        long maxTicks = Long.parseLong(a.getOrDefault("ticks", "0"));
        long seed = Long.parseLong(a.getOrDefault("seed", "42"));
        String zone = a.getOrDefault("zone", "local");
        double targetP99 = Double.parseDouble(a.getOrDefault("target-p99", "120"));
        double usd = Double.parseDouble(a.getOrDefault("usd", "0.10"));

        var sock = new DatagramSocket(new InetSocketAddress(bind, port));
        var journal = Files.newBufferedWriter(Path.of("kernel-journal-" + port + ".jsonl"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        // Holder для host (нужен в listener).
        var hostHolder = new Object() { CellHost h; };

        Membership.Transport tx = (to, msg) -> {
            try {
                byte[] b = msg.encode();
                int c = to.lastIndexOf(':');
                var addr = new InetSocketAddress(to.substring(0, c), Integer.parseInt(to.substring(c + 1)));
                sock.send(new DatagramPacket(b, b.length, addr));
            } catch (IOException e) { /* UDP: потеря допустима протоколом */ }
        };
        Membership.Listener listener = new Membership.Listener() {
            @Override public void onChange(long tick, String node, Wire.Status from, Wire.Status to, int inc) {
                String line = "{\"t\":" + tick + ",\"node\":\"" + node + "\",\"from\":\"" + from
                        + "\",\"to\":\"" + to + "\",\"inc\":" + inc + "}";
                System.out.println("[t" + tick + "] " + node + " " + from + " -> " + to + " (inc " + inc + ")");
                try { journal.write(line); journal.newLine(); journal.flush(); }
                catch (IOException e) { throw new UncheckedIOException(e); }
            }
            @Override public void onBudReq(long tick, String from, double load) {
                if (hostHolder.h != null && hostHolder.h.freeMemMb() > 300) {
                    System.out.println("[t" + tick + "] BUD_REMOTE_REQ from=" + from + " load=" + load);
                    // Триггерим тик host для bud-решения
                }
            }
        };

        var membership = new Membership(advertise, seed, tx, listener);
        for (var s : a.getOrDefault("seeds", "").split(",")) if (!s.isBlank()) membership.addSeed(s.trim());

        hostHolder.h = new CellHost(membership, cellsPort, seed, targetP99,
                (t, msg) -> {
                    try {
                        journal.write("{\"t\":" + t + ",\"ev\":\"" + msg.replace("\"", "\\\"") + "\"}\n");
                        journal.flush();
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                    System.out.println("[t" + t + "] " + msg);
                });

        Thread.ofVirtual().name("wire-rx").start(() -> {
            byte[] buf = new byte[2048];
            var pkt = new DatagramPacket(buf, buf.length);
            while (!sock.isClosed()) {
                try {
                    sock.receive(pkt);
                    membership.onMessage(Wire.decode(buf, pkt.getLength()));
                } catch (IOException e) { break; }
                catch (RuntimeException e) { /* битая датаграмма: дроп */ }
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sock.close(); hostHolder.h.close();
            try { journal.close(); } catch (IOException ignored) {}
        }));

        System.out.println("tissue-kernel " + advertise + " cells-port=" + cellsPort + " zone=" + zone + " period=" + periodMs + "ms");
        var rt = Runtime.getRuntime();
        long tick = 0;
        while (maxTicks == 0 || tick < maxTicks) {
            tick++;
            int memFree = (int) ((rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) >> 20);
            int diskFree = (int) (new File(".").getUsableSpace() >> 20);
            membership.setFacts(new Wire.NodeFacts(advertise, zone, membership.incarnation(), tick,
                    rt.availableProcessors(), memFree, diskFree, usd, hostHolder.h.cellCount(), hostHolder.h.myP99()));
            hostHolder.h.tick(tick);
            membership.tick();
            if (tick % 5 == 0) System.out.println("[t" + tick + "] view=" + membership.view() + " cells=" + hostHolder.h.cellCount());
            Thread.sleep(periodMs);
        }
        sock.close(); hostHolder.h.close(); journal.close();
    }

    private static Map<String, String> parse(String[] args) {
        var m = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++)
            if (args[i].startsWith("--") && i + 1 < args.length) m.put(args[i].substring(2), args[++i]);
        return m;
    }
}
