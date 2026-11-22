package dev.organism.net;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * TissueKernel — первый настоящий процесс ORGANISM вне симулятора.
 * UDP-приёмник на виртуальном потоке + тикер Membership + JSONL-журнал.
 *
 * 3 терминала:
 *   java -cp out dev.organism.net.TissueKernel --port 7101 --seeds 127.0.0.1:7102,127.0.0.1:7103
 *   java -cp out dev.organism.net.TissueKernel --port 7102 --seeds 127.0.0.1:7101
 *   java -cp out dev.organism.net.TissueKernel --port 7103 --seeds 127.0.0.1:7101
 * Ctrl+C на одном => остальные видят SUSPECT->DEAD за ~4-6 с; перезапуск => refute => ALIVE.
 * M1: wire без аутентификации — только доверенная сеть/VPN.
 */
public final class TissueKernel {
    public static void main(String[] args) throws Exception {
        var a = parse(args);
        int port = Integer.parseInt(a.getOrDefault("port", "7100"));
        String advertise = a.getOrDefault("advertise", "127.0.0.1:" + port);
        String bind = a.getOrDefault("bind", "0.0.0.0");
        long periodMs = Long.parseLong(a.getOrDefault("period-ms", "1000"));
        long maxTicks = Long.parseLong(a.getOrDefault("ticks", "0"));
        long seed = Long.parseLong(a.getOrDefault("seed", "42"));
        String zone = a.getOrDefault("zone", "local");
        double usd = Double.parseDouble(a.getOrDefault("usd", "0.10"));

        var sock = new DatagramSocket(new InetSocketAddress(bind, port));
        var journal = Files.newBufferedWriter(Path.of("kernel-journal-" + port + ".jsonl"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        Membership.Transport tx = (to, msg) -> {
            try {
                byte[] b = msg.encode();
                int c = to.lastIndexOf(':');
                var addr = new InetSocketAddress(to.substring(0, c), Integer.parseInt(to.substring(c + 1)));
                sock.send(new DatagramPacket(b, b.length, addr));
            } catch (IOException e) { /* UDP: потеря допустима протоколом */ }
        };
        Membership.Listener log = (t, node, from, to, inc) -> {
            String line = "{\"t\":" + t + ",\"node\":\"" + node + "\",\"from\":\"" + from
                    + "\",\"to\":\"" + to + "\",\"inc\":" + inc + "}";
            System.out.println("[t" + t + "] " + node + " " + from + " -> " + to + " (inc " + inc + ")");
            try { journal.write(line); journal.newLine(); journal.flush(); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        };

        var m = new Membership(advertise, seed, tx, log);
        for (var s : a.getOrDefault("seeds", "").split(",")) if (!s.isBlank()) m.addSeed(s.trim());

        Thread.ofVirtual().name("wire-rx").start(() -> {
            byte[] buf = new byte[2048];
            var pkt = new DatagramPacket(buf, buf.length);
            while (!sock.isClosed()) {
                try {
                    sock.receive(pkt);
                    m.onMessage(Wire.decode(buf, pkt.getLength()));
                } catch (IOException e) { break; }
                catch (RuntimeException e) { /* битая датаграмма: дроп */ }
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sock.close();
            try { journal.close(); } catch (IOException ignored) {}
        }));

        System.out.println("tissue-kernel " + advertise + " zone=" + zone + " period=" + periodMs + "ms");
        var rt = Runtime.getRuntime();
        while (maxTicks == 0 || m.tickCount() < maxTicks) {
            long t = m.tickCount() + 1;
            int memFree = (int) ((rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) >> 20);
            int diskFree = (int) (new File(".").getUsableSpace() >> 20);
            m.setFacts(new Wire.NodeFacts(advertise, zone, m.incarnation(), t,
                    rt.availableProcessors(), memFree, diskFree, usd, 0, 0));
            m.tick();
            if (t % 5 == 0) System.out.println("[t" + t + "] view=" + m.view());
            Thread.sleep(periodMs);
        }
        sock.close(); journal.close();
    }

    private static Map<String, String> parse(String[] args) {
        var m = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++)
            if (args[i].startsWith("--") && i + 1 < args.length) m.put(args[i].substring(2), args[++i]);
        return m;
    }
}
