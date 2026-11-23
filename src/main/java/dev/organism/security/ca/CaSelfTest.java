package dev.organism.security.ca;

import dev.organism.net.Membership;
import dev.organism.net.Wire;
import dev.organism.net.hier.RegionBridge;
import dev.organism.net.hier.TlsBridge;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Selftest столпа 5: keytool-CA, серты 24h, CRL-отзыв (включая mTLS-handshake), живой mTLS-мост. */
public final class CaSelfTest {
    private static int failed;
    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        System.out.printf("  %s %-62s %s%n", ok ? "OK " : "FAIL", name, detail);
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("org-ca");
        JdkCa ca = new JdkCa(dir, "changeit");
        ca.init();
        X509Certificate c1 = ca.issue("node1", List.of("localhost"));
        check("node cert signed by CA", ca.verify(c1), "");
        long hours = (c1.getNotAfter().getTime() - c1.getNotBefore().getTime()) / 3600000L;
        check("cert lifetime ~24h", hours >= 23 && hours <= 25, "hours=" + hours);
        X509Certificate c2 = ca.issue("node2", List.of("localhost"));
        check("second node cert ok", ca.verify(c2), "");
        ca.revoke(c1.getSerialNumber());
        check("revoked cert rejected (CRL)", !ca.verify(c1), "");
        check("other cert unaffected", ca.verify(c2), "");
        Path dir2 = Files.createTempDirectory("org-ca2");
        JdkCa foreign = new JdkCa(dir2, "changeit");
        foreign.init();
        check("foreign CA rejects cert", !foreign.verify(c2), "");

        SSLContext serverCtx = ca.sslContext("node1");
        SSLContext clientCtx = ca.sslContext("node2");
        SSLServerSocket ss = (SSLServerSocket) serverCtx.getServerSocketFactory().createServerSocket(0);
        ss.setNeedClientAuth(true);
        int port = ss.getLocalPort();
        CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try (SSLSocket s = (SSLSocket) ss.accept()) {
                s.setSoTimeout(5000);
                s.getInputStream().read();
            } catch (Exception ignored) { }
            done.countDown();
        });
        boolean rejected = false;
        try (SSLSocket cs = (SSLSocket) clientCtx.getSocketFactory().createSocket("localhost", port)) {
            cs.setSoTimeout(5000);
            cs.startHandshake();
            cs.getInputStream().read();
        } catch (Exception e) { rejected = true; }
        check("mTLS rejects revoked server cert", rejected, "");
        done.await(5, TimeUnit.SECONDS);
        ss.close();

        X509Certificate c3 = ca.issue("node3", List.of("localhost"));
        check("third node cert ok", ca.verify(c3), "");
        SSLContext okServer = ca.sslContext("node3");
        SSLContext okClient = ca.sslContext("node2");
        SSLServerSocket ss2 = (SSLServerSocket) okServer.getServerSocketFactory().createServerSocket(0);
        ss2.setNeedClientAuth(true);
        int p2 = ss2.getLocalPort();
        Thread.ofVirtual().start(() -> {
            try (SSLSocket s = (SSLSocket) ss2.accept()) {
                s.setSoTimeout(5000);
                int b = s.getInputStream().read();
                s.getOutputStream().write(b + 1);
                s.getOutputStream().flush();
            } catch (Exception ignored) { }
        });
        int echoed = -1;
        try (SSLSocket cs = (SSLSocket) okClient.getSocketFactory().createSocket("localhost", p2)) {
            cs.setSoTimeout(5000);
            cs.startHandshake();
            cs.getOutputStream().write(41);
            cs.getOutputStream().flush();
            echoed = cs.getInputStream().read();
        }
        check("mTLS healthy handshake + echo", echoed == 42, "echoed=" + echoed);
        ss2.close();

        Map<String, Membership> net = new HashMap<>();
        Membership ma = new Membership("a1", 1, (to, msg) -> { Membership d = net.get(to); if (d != null) d.onMessage(msg); }, (t, n, f, to, inc) -> { });
        Membership mb = new Membership("b1", 2, (to, msg) -> { Membership d = net.get(to); if (d != null) d.onMessage(msg); }, (t, n, f, to, inc) -> { });
        net.put("a1", ma);
        net.put("b1", mb);
        ma.setFacts(new Wire.NodeFacts("a1", "regionA", 0, 1, 4, 1024, 10000, 0.1, 3, 42.0));
        mb.setFacts(new Wire.NodeFacts("b1", "regionB", 0, 1, 8, 2048, 20000, 0.2, 5, 55.0));
        List<Wire.Sync> received = Collections.synchronizedList(new ArrayList<>());
        RegionBridge bridgeB = new RegionBridge(mb, "regionB", 1, (r, s) -> { });
        TlsBridge.Handle h = TlsBridge.server(okServer, 0, s -> { received.add(s); bridgeB.onRemote(s); });
        RegionBridge.WanLink wan = TlsBridge.client(okClient, new InetSocketAddress("localhost", h.port()), "regionB");
        RegionBridge bridgeA = new RegionBridge(ma, "regionA", 1, wan);
        for (int t = 0; t < 10; t++) {
            ma.tick();
            mb.tick();
            bridgeA.tick();
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (received.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        check("mTLS bridge delivers Sync", !received.isEmpty(), "");
        check("remote membership learned via mTLS", mb.view().containsKey("a1"), "");
        h.close();

        System.out.println(failed == 0 ? "CA SELFTEST PASSED" : "CA SELFTEST FAILED: " + failed);
        if (failed > 0) System.exit(1);
    }
}
