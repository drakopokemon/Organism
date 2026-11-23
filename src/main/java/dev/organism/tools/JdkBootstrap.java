package dev.organism.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Кроссплатформенный bootstrap JDK 25 LTS (toolchain, идентичный CI). Исходник совместим с JDK 17:
 * без virtual threads / ScopedValue / preview — можно собрать и запустить старым JDK.
 * Если текущий JDK >= 25 — печатает его home. Иначе качает Temurin 25 (Adoptium API, 2 попытки),
 * распаковывает (tar везде; zip-fallback на Windows) в targetDir и печатает home нового JDK.
 * stdout = ТОЛЬКО путь home (логи в stderr). Вызов: java -cp out_boot dev.organism.tools.JdkBootstrap <targetDir>
 */
public final class JdkBootstrap {
    /** Минимальный feature-release: 25 LTS = то, на чём зелёный CI. Никаких --enable-preview. */
    public static final int MIN_FEATURE = 25;

    public static void main(String[] args) throws Exception {
        Path target = Path.of(args.length > 0 ? args[0] : ".organism/jdk").toAbsolutePath();
        int feature = Runtime.version().feature();
        if (feature >= MIN_FEATURE) {
            System.out.println(Path.of(System.getProperty("java.home")).toAbsolutePath());
            return;
        }
        log("Текущий JDK " + feature + " < " + MIN_FEATURE + " (ORGANISM toolchain = Temurin " + MIN_FEATURE + " LTS, как в CI).");
        Path existing = findHome(target);
        if (existing != null) { System.out.println(existing); return; }
        Files.createDirectories(target);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osTag = os.contains("win") ? "windows" : os.contains("mac") ? "mac" : "linux";
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String archTag = (arch.contains("aarch64") || arch.contains("arm64")) ? "aarch64" : "x64";
        String url = "https://api.adoptium.net/v3/binary/latest/" + MIN_FEATURE + "/ga/" + osTag + "/" + archTag + "/jdk/hotspot/normal/eclipse";
        Path archive = Files.createTempFile("organism-jdk" + MIN_FEATURE + "-", osTag.equals("windows") ? ".zip" : ".tar.gz");
        log("Качаю Temurin " + MIN_FEATURE + " (" + osTag + "/" + archTag + "), ~200 MB...");
        try {
            download(url, archive);
            log("Скачано " + Files.size(archive) + " байт, распаковываю...");
            extract(archive, target, osTag);
        } catch (Exception e) {
            log("Авто-загрузка JDK не удалась: " + e);
            log("Поставь JDK " + MIN_FEATURE + " вручную: winget install EclipseAdoptium.Temurin." + MIN_FEATURE + ".JDK  (или https://adoptium.net)");
            System.exit(2);
            return;
        } finally {
            Files.deleteIfExists(archive);
        }
        Path home = findHome(target);
        if (home == null) { log("JDK распакован, но home c bin/javac не найден в " + target); System.exit(3); return; }
        log("JDK " + MIN_FEATURE + " готов: " + home);
        System.out.println(home);
    }

    /** Две попытки: сети на edge/корпоративных прокси рвут длинные загрузки. */
    private static void download(String url, Path archive) throws Exception {
        HttpClient hc = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30)).build();
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
                HttpResponse<Path> resp = hc.send(req, HttpResponse.BodyHandlers.ofFile(archive));
                if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
                if (Files.size(archive) < 1_000_000L) throw new IllegalStateException("truncated download: " + Files.size(archive));
                return;
            } catch (Exception e) {
                last = e;
                log("Попытка " + attempt + "/2 не удалась: " + e.getMessage());
                Files.deleteIfExists(archive);
            }
        }
        throw last == null ? new IllegalStateException("download failed") : last;
    }

    private static void extract(Path archive, Path target, String osTag) throws Exception {
        Process p = new ProcessBuilder("tar", "-xf", archive.toString(), "-C", target.toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        boolean ok = p.waitFor(10, java.util.concurrent.TimeUnit.MINUTES) && p.exitValue() == 0;
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

    /** Ищет JDK home (bin/javac, не JRE) внутри target: сам target или один уровень вложенности. */
    private static Path findHome(Path target) throws Exception {
        if (!Files.isDirectory(target)) return null;
        if (hasCompiler(target)) return target;
        try (var s = Files.list(target)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isDirectory(p) && hasCompiler(p)) return p;
            }
        }
        return null;
    }

    private static boolean hasCompiler(Path home) {
        return Files.exists(home.resolve("bin").resolve("javac")) || Files.exists(home.resolve("bin").resolve("javac.exe"));
    }

    private static void log(String s) { System.err.println("[jdk-bootstrap] " + s); }
}
