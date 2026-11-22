package dev.organism;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.Executors;

/** Демо-«внешний сервис» (стоит вместо Python-модели): /health и /work, 5 % запросов — медленные (600 мс). */
public final class EchoService {
    public static void main(String[] a) throws Exception {
        var rnd = new Random();
        var s = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(a[0])), 0);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.createContext("/health", ex -> reply(ex, "ok"));
        s.createContext("/work", ex -> {
            try { Thread.sleep(rnd.nextInt(100) < 5 ? 600 : 8 + rnd.nextInt(10)); } catch (InterruptedException ignored) {}
            reply(ex, "done");
        });
        s.start();
        Thread.currentThread().join();
    }
    private static void reply(HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes();
        ex.sendResponseHeaders(200, b.length);
        try (var o = ex.getResponseBody()) { o.write(b); }
    }
}
