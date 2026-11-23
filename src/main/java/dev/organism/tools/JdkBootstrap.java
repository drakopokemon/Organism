package dev.organism.tools;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Кроссплатформенный bootstrap JDK 21 (совместим с JDK 17: без virtual threads / ScopedValue).
 * Если текущий JDK >= 21 — печатает его home и выходит. Иначе качает Temurin 21 (Adoptium API),
 * распаковывает (tar везде; zip-fallback на Windows) в targetDir и печатает home нового JDK.
 * stdout = ТОЛЬКО путь home (логи в stderr). Вызов: java -cp out_boot dev.organism.tools.JdkBootstrap <targetDir>
 */
public final class JdkBootstrap {
    public static void main(String[] args) throws Exception {
        Path target = Path.of(args.length > 0 ? args[0] : ".organism/jdk").toAbsolutePath();
        int feature = Runtime.version().feature();
        if (feature >= 21) {
            System.out.println(Path.of(System.getProperty("java.home")).toAbsolutePath());
            return;
        }
        log("Текущий JDK " + feature + " < 21 (ORGANISM требует virtual threads/Loom из Java 21+).");
        Path existing = findHome(target);
        if (existing != null) { System.out.println(existing); return; }
        Files.createDirectories(target);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osTag = os.contains("win") ? "windows" : os.contains("mac") ? "mac" : "linux";
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String archTag = (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x64";
        String url = "https://api.adoptium.net/v3/binary/latest/21/ga/" + osTag + "/" + archTag + "/jdk/hotspot/normal/eclipse";
        Path archive = Files.createTempFile("organism-jdk21-", osTag.equals("windows") ? ".zip" : ".tar.gz");
        log("Качаю Temurin 21 (" + osTag + "/" + archTag + ")...");
        try {
            HttpClient hc = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
            HttpResponse<Path> resp = hc.send(req, HttpResponse.BodyHandlers.ofFile(archive));
            if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
            log("Скачано " + Files.size(archive) + " байт, распаковываю...");
            extract(archive, target, osTag);
        } catch (Exception e) {
            log("Авто-загрузка JDK не удалась: " + e.getMessage());
            log("Поставь JDK 21 вручную: winget install EclipseAdoptium.Temurin.21.JDK  (или https://adoptium.net)");
            System.exit(2);
            return;
        } finally {
            Files.deleteIfExists(archive);
        }
        Path home = findHome(target);
        if (home == null) { log("JDK распакован, но home не найден в " + target); System.exit(3); return; }
        log("JDK 21 готов: " + home);
        System.out.println(home);
    }

    private static void extract(Path archive, Path target, String osTag) throws Exception {
        Process p = new ProcessBuilder("tar", "-xf", archive.toString(), "-C", target.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        boolean ok = p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES) && p.exitValue() == 0;
        if (!ok && osTag.equals("windows")) unzip(archive, target);
        else if (!ok) throw new IllegalStateException("tar extraction failed");
    }

    private static void unzip(Path archive, Path target) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = target.resolve(e.getName()).normalize();
                if (!out.startsWith(target)) throw new IllegalStateException("zip slip: " + e.getName());
                if (e.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent());
                Files.copy(zis, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Ищет каталог с bin/java внутри target (один уровень вложенности). */
    private static Path findHome(Path target) throws Exception {
        if (!Files.isDirectory(target)) return null;
        if (Files.exists(target.resolve("bin"))) return target;
        try (var s = Files.list(target)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isDirectory(p) && Files.exists(p.resolve("bin"))) return p;
            }
        }
        return null;
    }

    private static void log(String s) { System.err.println("[jdk-bootstrap] " + s); }
}
