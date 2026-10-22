package br.com.verificador.desktop.service;

import br.com.verificador.desktop.config.AppPaths;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Updates selected trust roots and, separately, the official intermediate CA package. */
public final class TrustAnchorService {
    // v7 uses Ed521, which is not implemented by the reference engine's crypto provider.
    private static final int[] VERSIONS = {5, 6, 12, 13};
    private final AppPaths paths;
    private final OfficialCertificateDownload downloader = new OfficialCertificateDownload();

    public TrustAnchorService(AppPaths paths) { this.paths = paths; }

    public List<String> update(Consumer<String> progress) throws InterruptedException, IOException {
        Files.createDirectories(paths.anchors());
        List<String> failures = new ArrayList<>();
        for (int version : VERSIONS) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String name = "ICP-Brasilv" + version + ".crt";
            progress.accept("Atualizando raiz ICP-Brasil v" + version + "…");
            try {
                URI uri = URI.create("https://acraiz.icpbrasil.gov.br/credenciadas/RAIZ/" + name);
                byte[] encoded = downloader.get(uri, 128 * 1024);
                validateRoot(encoded);
                Path temporary = Files.createTempFile(paths.anchors(), "root-", ".tmp");
                try {
                    Files.write(temporary, encoded);
                    Files.move(temporary, paths.anchors().resolve(name), StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temporary); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
            catch (Exception e) { failures.add(name + ": " + e.getMessage()); }
        }
        try { new IntermediateCertificateService(paths).update(progress); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw exception; }
        catch (Exception exception) { failures.add("Certificados intermediários do ITI: " + exception.getMessage()); }
        return failures;
    }

    public int count() throws IOException {
        if (!Files.isDirectory(paths.anchors())) return 0;
        int valid = 0;
        try (var entries = Files.list(paths.anchors())) {
            for (Path entry : entries.filter(Files::isRegularFile).toList()) {
                if (!entry.getFileName().toString().toLowerCase().matches(".*\\.(crt|cer|pem)")) continue;
                try { validateRoot(Files.readAllBytes(entry)); valid++; } catch (Exception ignored) { }
            }
        }
        return valid;
    }

    public static X509Certificate validateRoot(byte[] encoded) throws Exception {
        var certificate = (X509Certificate) CertificateFactory.getInstance("X.509", new org.bouncycastle.jce.provider.BouncyCastleProvider())
                .generateCertificate(new ByteArrayInputStream(encoded));
        if (certificate.getBasicConstraints() < 0 || !certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal()))
            throw new IOException("O arquivo não é um certificado de autoridade raiz.");
        certificate.verify(certificate.getPublicKey(), new org.bouncycastle.jce.provider.BouncyCastleProvider());
        return certificate;
    }
}
