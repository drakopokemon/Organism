package dev.organism;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Кроссплатформенный one-command launcher (Windows/Linux/macOS, только JDK 21+).
 * Генерит PSK/admin-token/RBAC/PKCS12 (идемпотентно, в .organism/), поднимает secured cluster
 * TissueKernel + GUI. Никаких shell-утилит (od/head/sha256sum) — всё на JDK, поэтому работает везде.
 *   java -cp out dev.organism.Launcher [nodes]
 *   java -cp out dev.organism.Launcher --setup-only   (только генерация секретов, для CI smoke)
 */
public final class Launcher {
    public static void main(String[] args) throws Exception {
        boolean setupOnly = false;
        int nodes = 3;
        for (String a : args) {
            if (a.equals("--setup-only")) setupOnly = true;
            else if (a.matches("\\d+")) nodes = Math.max(1, Math.min(32, Integer.parseInt(a)));
        }
        Path home = Path.of(System.getenv().getOrDefault("ORGANISM_HOME", ".organism")).toAbsolutePath();
        Files.createDirectories(home);
        String psk = readOrCreate(home.resolve("psk"), () -> hex(32));
        String token = readOrCreate(home.resolve("admin.token"), () -> hex(24));
        Files.writeString(home.resolve("rbac.txt"), sha256Hex(token) + ":ADMIN\n", StandardCharsets.UTF_8);
        Path ks = home.resolve("tls.p12");
        if (!Files.exists(ks)) genKeystore(ks);
        System.out.println("[organism] home=" + home + " psk/token/rbac/tls ready");
        if (setupOnly) {
            if (!Files.exists(ks) || Files.size(ks) == 0) throw new IllegalStateException("keystore generation failed");
            System.out.println("[organism] setup-only OK");
            return;
        }

        String javaCmd = ProcessHandle.current().info().command().orElse("java");
        String cp = Path.of("out").toAbsolutePath().toString();
        List<Process> procs = new ArrayList<>();
        for (int i = 1; i <= nodes; i++) {
            int port = 7100 + i, cells = 8100 + i, api = 9100 + i;
            List<String> cmd = new ArrayList<>(List.of(javaCmd, "-cp", cp, "dev.organism.net.TissueKernel",
                    "--port", "" + port, "--cells-port", "" + cells, "--seed", "" + i,
                    "--secure", "--psk", psk,
                    "--audit-path", home.resolve("audit-" + port + ".jsonl").toString(),
                    "--rbac-file", home.resolve("rbac.txt").toString(),
                    "--api-port", "" + api,
                    "--tls-keystore", ks.toString(), "--tls-pass", "organism",
                    "--approval-risk", "0.10",
                    "--snapshot-dir", home.resolve("spores-" + port).toString(), "--snapshot-every", "10"));
            if (i > 1) { cmd.add("--seeds"); cmd.add("127.0.0.1:7101"); }
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(home.resolve("node-" + port + ".log").toFile()).start();
            procs.add(p);
            Thread.sleep(700);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process p : procs) { p.destroy(); }
            for (Process p : procs) {
                try { if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly(); }
                catch (InterruptedException e) { p.destroyForcibly(); Thread.currentThread().interrupt(); }
            }
        }));
        System.out.println("========================================================");
        System.out.println("  ORGANISM cluster: " + nodes + " nodes (secure gossip + TLS API + GUI)");
        System.out.println("  GUI:    https://127.0.0.1:9101/");
        System.out.println("  TOKEN:  " + token);
        System.out.println("  logs:   " + home + "/node-*.log");
        System.out.println("  Ctrl+C to stop");
        System.out.println("========================================================");
        for (Process p : procs) p.waitFor();
    }

    private static void genKeystore(Path ks) throws Exception {
        String javaCmd = ProcessHandle.current().info().command().orElse("java");
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String keytool = Path.of(javaCmd).resolveSibling(win ? "keytool.exe" : "keytool").toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "organism", "-keyalg", "RSA",
                "-keysize", "2048", "-storetype", "PKCS12", "-keystore", ks.toString(),
                "-storepass", "organism", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "3650")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0)
            throw new IllegalStateException("keytool failed (нужен JDK, не JRE): exit=" + p.exitValue());
    }

    private static String readOrCreate(Path p, java.util.function.Supplier<String> gen) throws Exception {
        if (Files.exists(p)) { String s = Files.readString(p, StandardCharsets.UTF_8).trim(); if (!s.isEmpty()) return s; }
        String s = gen.get();
        Files.writeString(p, s, StandardCharsets.UTF_8);
        return s;
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String sha256Hex(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
