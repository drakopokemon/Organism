package dev.organism.supplychain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.concurrent.TimeUnit;

/**
 * Верификация бинарников агентов перед запуском (аналог cosign verify-blob).
 * OFF      — legacy-поведение (дефолт, ничего не проверяем).
 * ED25519  — нативная JDK-подпись blob'а (Sigstore-эквивалент без внешних зависимостей).
 * COSIGN   — opt-in subprocess `cosign verify-blob`; если cosign нет в PATH — FAIL-CLOSED (отказ запуска).
 */
public final class BinaryVerifier {
    public enum Mode { OFF, ED25519, COSIGN }
    private BinaryVerifier() {}

    public static boolean verifyEd25519(Path blob, Path sig, PublicKey pub) {
        try {
            byte[] data = Files.readAllBytes(blob);
            byte[] s = Files.readAllBytes(sig);
            return GenomeArtifact.verify(data, s, pub);
        } catch (Exception e) { return false; }
    }

    public static boolean verifyCosign(Path blob, Path sig, Path pubPem) {
        try {
            Process p = new ProcessBuilder("cosign", "verify-blob",
                    "--key", pubPem.toString(), "--signature", sig.toString(), blob.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false; // fail-closed: нет cosign / сломан ключ -> не запускаем
        }
    }

    public static boolean verify(Mode mode, Path blob, Path sig, PublicKey pub, Path cosignPubPem) {
        return switch (mode) {
            case OFF -> true;
            case ED25519 -> pub != null && sig != null && verifyEd25519(blob, sig, pub);
            case COSIGN -> cosignPubPem != null && sig != null && verifyCosign(blob, sig, cosignPubPem);
        };
    }

    public static boolean toolAvailable(String tool) {
        try {
            Process p = new ProcessBuilder(tool, "version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }
}
