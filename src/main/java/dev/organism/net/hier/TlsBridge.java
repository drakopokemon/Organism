package dev.organism.net.hier;

import dev.organism.net.Wire;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

/**
 * mTLS-мост между супернодами: JDK SSLContext из PKCS12, выданных JdkCa (серты 24h, отзыв через CRL).
 * Кадр: 4-byte length + Wire.encode(). WAN нестабилен — потерянная сводка уедет в следующем периоде.
 */
public final class TlsBridge {
    private TlsBridge() {}

    public record Handle(int port, SSLServerSocket socket) implements AutoCloseable {
        @Override public void close() throws IOException { socket.close(); }
    }

    public static RegionBridge.WanLink client(SSLContext ctx, InetSocketAddress remote, String remoteRegion) {
        return (region, summary) -> {
            try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket()) {
                s.connect(remote, 5000);
                s.setSoTimeout(5000);
                s.startHandshake();
                byte[] b = summary.encode();
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                out.writeInt(b.length);
                out.write(b);
                out.flush();
            } catch (IOException e) {
                // локальный gossip живёт автономно; сводка повторится в следующем периоде
            }
        };
    }

    public static Handle server(SSLContext ctx, int port, Consumer<Wire.Sync> onSync) throws IOException {
        SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(port);
        ss.setNeedClientAuth(true);
        Thread.ofVirtual().name("tls-bridge-accept").start(() -> {
            while (!ss.isClosed()) {
                try {
                    SSLSocket s = (SSLSocket) ss.accept();
                    Thread.ofVirtual().start(() -> {
                        try (s) {
                            s.setSoTimeout(5000);
                            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                            int len = in.readInt();
                            if (len <= 0 || len > 1_048_576) return;
                            byte[] b = new byte[len];
                            in.readFully(b);
                            Wire w = Wire.decode(b, b.length);
                            if (w instanceof Wire.Sync sync) onSync.accept(sync);
                        } catch (Exception ignored) { }
                    });
                } catch (IOException e) {
                    if (ss.isClosed()) return;
                }
            }
        });
        return new Handle(ss.getLocalPort(), ss);
    }
}
