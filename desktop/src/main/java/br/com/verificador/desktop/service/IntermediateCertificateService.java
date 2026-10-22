package br.com.verificador.desktop.service;

import br.com.verificador.desktop.config.AppPaths;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.cert.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Certificates from this package are chain candidates, never trust anchors. */
public final class IntermediateCertificateService {
    private static final String BASE = "https://acraiz.icpbrasil.gov.br/credenciadas/CertificadosAC-ICP-Brasil/";
    static final URI ARCHIVE = URI.create(BASE + "ACcompactado.zip");
    static final URI CHECKSUM = URI.create(BASE + "hashsha512.txt");
    private static final int MAX_ARCHIVE = 20 * 1024 * 1024;
    private static final int MAX_ENTRY = 256 * 1024;
    private static final Pattern HASH = Pattern.compile("(?i)(?<![0-9a-f])[0-9a-f]{128}(?![0-9a-f])");
    private final AppPaths paths;
    private final Downloader downloader;

    @FunctionalInterface interface Downloader { byte[] get(URI uri, int limit) throws IOException, InterruptedException; }

    public IntermediateCertificateService(AppPaths paths) { this(paths, new OfficialCertificateDownload()::get); }
    IntermediateCertificateService(AppPaths paths, Downloader downloader) { this.paths = paths; this.downloader = downloader; }

    public int update(Consumer<String> progress) throws Exception {
        progress.accept("Baixando certificados intermediários do ITI…");
        byte[] archive = downloader.get(ARCHIVE, MAX_ARCHIVE);
        String checksum = new String(downloader.get(CHECKSUM, 16 * 1024), StandardCharsets.UTF_8);
        verifyChecksum(archive, checksum);
        var certificates = readArchive(archive);
        if (certificates.isEmpty()) throw new IOException("O pacote do ITI não contém certificados intermediários utilizáveis.");
        Files.createDirectories(paths.intermediates());
        Path temporary = Files.createTempFile(paths.intermediates(), "iti-", ".tmp");
        try {
            // Publish the whole validated archive in one operation; a failed update preserves the old package.
            Files.write(temporary, archive);
            Files.move(temporary, archivePath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        progress.accept(certificates.size() + " certificado(s) intermediário(s) disponível(is).");
        return certificates.size();
    }

    private Path archivePath() { return paths.intermediates().resolve("iti-ac-vigente.zip"); }
    public boolean isInstalled() { return Files.isRegularFile(archivePath()); }
    public List<X509Certificate> load() throws Exception {
        if (!isInstalled()) return List.of();
        try (var stream = Files.newInputStream(archivePath())) {
            return readArchive(readBounded(stream, MAX_ARCHIVE));
        }
    }

    static void verifyChecksum(byte[] archive, String checksum) throws Exception {
        var matcher = HASH.matcher(checksum);
        if (!matcher.find()) throw new IOException("Hash SHA-512 ausente na resposta do ITI.");
        byte[] expected = HexFormat.of().parseHex(matcher.group());
        if (matcher.find()) throw new IOException("Resposta de hash do ITI ambígua.");
        if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-512").digest(archive)))
            throw new IOException("O SHA-512 do pacote de certificados difere do publicado pelo ITI. O pacote anterior foi preservado.");
    }

    static List<X509Certificate> readArchive(byte[] archive) throws Exception {
        if (archive.length > MAX_ARCHIVE) throw new IOException("Pacote de certificados excede o limite permitido.");
        var factory = CertificateFactory.getInstance("X.509", new BouncyCastleProvider());
        Map<String, X509Certificate> certificates = new LinkedHashMap<>();
        int entries = 0; long total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.ISO_8859_1)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (++entries > 10000) throw new IOException("Pacote com entradas demais.");
                if (entry.isDirectory()) continue;
                byte[] bytes = readBounded(zip, MAX_ENTRY);
                total += bytes.length;
                if (total > 64 * 1024 * 1024) throw new IOException("Pacote descompactado excede o limite permitido.");
                String name = entry.getName().toLowerCase(Locale.ROOT);
                // No ZIP entry is extracted to a filesystem path.
                if (!name.endsWith(".crt") && !name.endsWith(".cer") && !name.endsWith(".pem")) continue;
                for (var value : factory.generateCertificates(new ByteArrayInputStream(bytes))) {
                    var certificate = (X509Certificate) value;
                    if (certificate.getBasicConstraints() < 0)
                        throw new IOException("O pacote contém um certificado que não é de autoridade certificadora: " + entry.getName());
                    boolean[] usage = certificate.getKeyUsage();
                    if (usage != null && (usage.length <= 5 || !usage[5])) throw new IOException("Certificado de AC sem permissão para emitir certificados.");
                    if (certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) continue;
                    String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
                    certificates.putIfAbsent(id, certificate);
                }
            }
        }
        return List.copyOf(certificates.values());
    }

    private static byte[] readBounded(InputStream stream, int limit) throws IOException {
        byte[] bytes = stream.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IOException("Arquivo excede o tamanho permitido.");
        return bytes;
    }
}
