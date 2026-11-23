package dev.organism.security.ca;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Столп 5: встроенный CA на JDK + keytool -gencert (эквивалент Bouncy Castle CA, ноль внешних зависимостей).
 * Серты узлов — 24 часа. Отзыв — CRL-файл (serialHex:ts), проверяется и в verify(), и в mTLS-handshake
 * (кастомный X509TrustManager поверх PKIX): скомпрометированный узел отваливается на следующем коннекте.
 * keytool поставляется вместе с JDK — это по-прежнему "только JDK".
 * CLI: init <dir> <pass> | issue <dir> <pass> <node> [san...] | revoke <dir> <pass> <serialHex> | verify <dir> <pass> <certPem>
 */
public final class JdkCa {
    private final Path dir;
    private final String pass;

    public JdkCa(Path dir, String pass) {
        this.dir = dir;
        this.pass = pass;
    }

    public Path dir() { return dir; }

    public void init() throws IOException {
        Files.createDirectories(dir);
        if (Files.exists(dir.resolve("ca.p12"))) return;
        kt("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1",
                "-validity", "3650", "-keystore", dir.resolve("ca.p12").toString(), "-storetype", "PKCS12",
                "-storepass", pass, "-dname", "CN=ORGANISM-CA", "-ext", "BasicConstraints:critical=ca:true");
        kt("-exportcert", "-rfc", "-alias", "ca", "-keystore", dir.resolve("ca.p12").toString(),
                "-storepass", pass, "-file", dir.resolve("ca.pem").toString());
    }

    public X509Certificate issue(String node, List<String> sanDns) throws IOException {
        String ks = dir.resolve("node-" + node + ".p12").toString();
        kt("-genkeypair", "-alias", "node", "-keyalg", "EC", "-groupname", "secp256r1",
                "-validity", "1", "-keystore", ks, "-storetype", "PKCS12", "-storepass", pass,
                "-dname", "CN=" + node);
        Path csr = dir.resolve(node + ".csr");
        kt("-certreq", "-alias", "node", "-keystore", ks, "-storepass", pass, "-file", csr.toString());
        StringBuilder san = new StringBuilder();
        for (String d : sanDns) {
            if (san.length() > 0) san.append(',');
            san.append("dns:").append(d);
        }
        Path crt = dir.resolve(node + ".crt");
        kt("-gencert", "-alias", "ca", "-keystore", dir.resolve("ca.p12").toString(), "-storepass", pass,
                "-infile", csr.toString(), "-outfile", crt.toString(), "-validity", "1", "-rfc",
                "-ext", "SAN=" + san);
        kt("-importcert", "-noprompt", "-alias", "ca", "-file", dir.resolve("ca.pem").toString(),
                "-keystore", ks, "-storepass", pass);
        kt("-importcert", "-noprompt", "-alias", "node", "-file", crt.toString(), "-keystore", ks, "-storepass", pass);
        return readCert(crt);
    }

    public X509Certificate caCert() throws IOException { return readCert(dir.resolve("ca.pem")); }

    public void revoke(BigInteger serial) throws IOException {
        Files.writeString(dir.resolve("crl.txt"), serial.toString(16) + ":" + System.currentTimeMillis() + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public boolean isRevoked(BigInteger serial) {
        Path crl = dir.resolve("crl.txt");
        if (!Files.exists(crl)) return false;
        try {
            for (String l : Files.readAllLines(crl, StandardCharsets.UTF_8)) {
                int c = l.indexOf(':');
                if (c > 0 && l.substring(0, c).equalsIgnoreCase(serial.toString(16))) return true;
            }
            return false;
        } catch (IOException e) {
            return true; // fail-closed: CRL нечитаем -> считаем отозванным
        }
    }

    public List<String> crlLines() throws IOException {
        Path crl = dir.resolve("crl.txt");
        return Files.exists(crl) ? Files.readAllLines(crl, StandardCharsets.UTF_8) : List.of();
    }

    /** Подпись CA + окно валидности + срок <= 25h + не отозван. */
    public boolean verify(X509Certificate cert) {
        try {
            cert.verify(caCert().getPublicKey());
            cert.checkValidity();
            long hours = (cert.getNotAfter().getTime() - cert.getNotBefore().getTime()) / 3600000L;
            if (hours > 25) return false;
            return !isRevoked(cert.getSerialNumber());
        } catch (Exception e) {
            return false;
        }
    }

    /** SSLContext из node-PKCS12; trust = CA + CRL-проверка каждого сертификата цепочки. */
    public SSLContext sslContext(String node) throws IOException {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(dir.resolve("node-" + node + ".p12"))) {
                ks.load(in, pass.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pass.toCharArray());
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            trust.setCertificateEntry("ca", caCert());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            X509TrustManager base = (X509TrustManager) tmf.getTrustManagers()[0];
            X509TrustManager crlAware = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    base.checkClientTrusted(chain, authType);
                    checkCrl(chain);
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    base.checkServerTrusted(chain, authType);
                    checkCrl(chain);
                }
                @Override public X509Certificate[] getAcceptedIssuers() { return base.getAcceptedIssuers(); }
                private void checkCrl(X509Certificate[] chain) throws CertificateException {
                    for (X509Certificate c : chain)
                        if (isRevoked(c.getSerialNumber()))
                            throw new CertificateException("revoked cert: " + c.getSerialNumber().toString(16));
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), new TrustManager[]{crlAware}, null);
            return ctx;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("ssl context failed", e);
        }
    }

    public static X509Certificate readCert(Path pem) throws IOException {
        try (InputStream in = Files.newInputStream(pem)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (Exception e) {
            throw new IOException("bad cert " + pem, e);
        }
    }

    private void kt(String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("keytool timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        if (p.exitValue() != 0) throw new IOException("keytool failed: " + String.join(" ", args) + "\n" + out);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: JdkCa init|issue|revoke|verify <dir> <pass> [node|serialHex|certPem] [san...]");
            System.exit(2);
            return;
        }
        JdkCa ca = new JdkCa(Path.of(args[1]), args[2]);
        switch (args[0]) {
            case "init" -> { ca.init(); System.out.println("CA INIT OK " + args[1]); }
            case "issue" -> {
                if (args.length < 4) { System.err.println("issue needs <node>"); System.exit(2); return; }
                ca.init();
                List<String> san = args.length > 4 ? Arrays.asList(Arrays.copyOfRange(args, 4, args.length)) : List.of("localhost");
                X509Certificate c = ca.issue(args[3], san);
                System.out.println("ISSUED " + args[3] + " serial=" + c.getSerialNumber().toString(16) + " notAfter=" + c.getNotAfter());
            }
            case "revoke" -> {
                if (args.length < 4) { System.err.println("revoke needs <serialHex>"); System.exit(2); return; }
                ca.revoke(new BigInteger(args[3], 16));
                System.out.println("REVOKED " + args[3]);
            }
            case "verify" -> {
                if (args.length < 4) { System.err.println("verify needs <certPem>"); System.exit(2); return; }
                boolean ok = ca.verify(readCert(Path.of(args[3])));
                System.out.println(ok ? "CERT VERIFY OK" : "CERT VERIFY FAILED");
                System.exit(ok ? 0 : 1);
            }
            default -> { System.err.println("unknown command " + args[0]); System.exit(2); }
        }
    }
}
