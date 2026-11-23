import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Кроссплатформенный лаунчер "запусти и всё". Только JDK 21+.
 * Запуск: java Launch.java [nodes]   (Windows/Linux/macOS одинаково, без bash).
 * Компилирует проект, генерит PSK/токен/PKCS12, поднимает secured cluster + GUI, печатает URL и токен.
 */
public class Launch {
    public static void main(String[] args) throws Exception {
        int nodes = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        Path root = Path.of("").toAbsolutePath();
        Path out = root.resolve("out");
        Path work = root.resolve(".organism");
        Files.createDirectories(work);

        if (needsCompile(root, out)) {
            System.out.println("[organism] compiling (JDK only)...");
            Files.createDirectories(out);
            var srcs = new ArrayList<String>();
            try (var s = Files.walk(root.resolve("src/main/java"))) {
                s.filter(p -> p.toString().endsWith(".java")).forEach(p -> srcs.add(p.toString()));
            }
            var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            if (compiler == null) { System.err.println("Нужен JDK (не JRE): javac недоступен. Поставь Temurin 21+."); System.exit(2); }
            var argv = new ArrayList<String>();
            argv.add("-d"); argv.add(out.toString()); argv.addAll(srcs);
            int rc = compiler.run(null, null, null, argv.toArray(new String[0]));
            if (rc != 0) { System.err.println("compile failed: " + rc); System.exit(rc); }
            Path res = root.resolve("src/main/resources");
            if (Files.isDirectory(res)) {
                try (var s = Files.walk(res)) {
                    s.filter(Files::isRegularFile).forEach(p -> {
                        try { Files.copy(p, out.resolve(res.relativize(p).toString()), StandardCopyOption.REPLACE_EXISTING); }
                        catch (IOException e) { throw new UncheckedIOException(e); }
                    });
                }
            }
        }

        SecureRandom rnd = new SecureRandom();
        Path pskFile = work.resolve("psk");
        String psk = Files.exists(pskFile) ? Files.readString(pskFile).trim() : hex(rnd, 32, pskFile);
        Path tokFile = work.resolve("admin.token");
        String token = Files.exists(tokFile) ? Files.readString(tokFile).trim() : hex(rnd, 24, tokFile);
        Path rbac = work.resolve("rbac.txt");
        Files.writeString(rbac, sha256(token) + ":ADMIN\n");
        Path ks = work.resolve("tls.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", win() ? "keytool.exe" : "keytool").toString();
        if (!Files.exists(ks)) {
            var pb = new ProcessBuilder(keytool, "-genkeypair", "-alias", "organism", "-keyalg", "RSA", "-keysize", "2048",
                    "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", "organism",
                    "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "3650");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() != 0) { System.err.println("keytool failed"); System.exit(3); }
        }

        String javaBin = Path.of(System.getProperty("java.home"), "bin", win() ? "java.exe" : "java").toString();
        var procs = new ArrayList<Process>();
        int bp = 7100, bc = 8100, ba = 9100;
        for (int i = 1; i <= nodes; i++) {
            int port = bp + i, cells = bc + i, api = ba + i;
            var cmd = new ArrayList<String>(List.of(javaBin, "-cp", out.toString(), "dev.organism.net.TissueKernel",
                    "--port", "" + port, "--cells-port", "" + cells, "--seed", "" + i));
            if (i > 1) { cmd.add("--seeds"); cmd.add("127.0.0.1:" + (bp + 1)); }
            cmd.addAll(List.of("--secure", "--psk", psk,
                    "--audit-path", work.resolve("audit-" + port + ".jsonl").toString(),
                    "--rbac-file", rbac.toString(), "--api-port", "" + api,
                    "--tls-keystore", ks.toString(), "--tls-pass", "organism",
                    "--approval-risk", "0.10", "--snapshot-dir", work.resolve("spores-" + port).toString(),
                    "--snapshot-every", "10"));
            var pb = new ProcessBuilder(cmd);
            pb.redirectOutput(work.resolve("node-" + port + ".log").toFile());
            pb.redirectErrorStream(true);
            procs.add(pb.start());
            Thread.sleep(800);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> procs.forEach(Process::destroy)));

        System.out.println("========================================================");
        System.out.println("  ORGANISM cluster: " + nodes + " nodes");
        System.out.println("  secure gossip (AES-GCM) + TLS API + hash-chain audit");
        System.out.println("  GUI:    https://127.0.0.1:" + (ba + 1) + "/");
        System.out.println("  TOKEN:  " + token);
        System.out.println("  logs:   " + work + System.getProperty("file.separator") + "node-*.log");
        System.out.println("  Ctrl+C to stop");
        System.out.println("========================================================");
        for (Process p : procs) p.waitFor();
    }

    static boolean win() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    static String hex(SecureRandom r, int n, Path f) throws IOException {
        byte[] b = new byte[n]; r.nextBytes(b);
        String s = HexFormat.of().formatHex(b);
        Files.writeString(f, s);
        return s;
    }

    static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    static boolean needsCompile(Path root, Path out) throws IOException {
        if (!Files.isDirectory(out)) return true;
        long outT = Files.getLastModifiedTime(out).toMillis();
        try (var s = Files.walk(root.resolve("src/main/java"))) {
            return s.filter(p -> p.toString().endsWith(".java")).anyMatch(p -> {
                try { return Files.getLastModifiedTime(p).toMillis() > outT; }
                catch (IOException e) { return true; }
            });
        }
    }
}
