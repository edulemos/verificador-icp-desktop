package br.com.verificador.desktop;

import br.com.verificador.desktop.config.AppPaths;
import br.com.verificador.desktop.engine.IcpBrasilEngine;
import br.com.verificador.desktop.model.*;
import br.com.verificador.desktop.service.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VerificationIntegrationTest {
    @TempDir Path directory;
    private static SignedFixtures fixture;
    private AppPaths paths;
    @BeforeAll static void createCertificates() throws Exception { fixture = new SignedFixtures(); }
    @BeforeEach void setup() throws Exception {
        paths = new AppPaths(directory.resolve("data")); paths.initialize();
        Files.write(paths.anchors().resolve("test-root.crt"), fixture.root.getEncoded());
    }
    private VerificationResult verify(String name, byte[] signature, Path detached) throws Exception {
        return new IcpBrasilEngine(paths).verify(new VerificationInput(Files.write(directory.resolve(name), signature), detached));
    }
    @Test void verifiesAttachedCmsThroughRealReferenceEngine() throws Exception {
        var result = verify("assinatura.p7m", fixture.cms(fixture.content, true), null);
        assertEquals(1, result.signatures().size(), result.message());
        assertTrue(result.signatures().get(0).integrity());
        assertTrue(result.signatures().get(0).asymmetricCipher());
        assertNotEquals(VerificationStatus.REJECTED, result.status());
        assertNotNull(result.reportXml());
    }
    @Test void verifiesSignedPdfThroughRealReferenceEngine() throws Exception {
        var result = verify("assinado.pdf", fixture.pdf(), null);
        assertFalse(result.signatures().isEmpty(), result.message());
        assertTrue(result.signatures().get(0).integrity());
    }
    @Test void verifiesSignedXmlThroughRealReferenceEngine() throws Exception {
        var result = verify("assinado.xml", fixture.xml(), null);
        assertFalse(result.signatures().isEmpty(), result.message());
        assertTrue(result.signatures().get(0).integrity());
    }
    @Test void bindsEachDetachedSignatureToItsOwnDocumentAndDetectsTampering() throws Exception {
        byte[] signature = fixture.cms(fixture.content, false);
        Path original = Files.write(directory.resolve("original.txt"), fixture.content);
        var correct = verify("destacada.p7s", signature, original);
        assertEquals(1, correct.signatures().size(), correct.message());
        assertTrue(correct.signatures().get(0).integrity());
        Path modified = Files.writeString(directory.resolve("alterado.txt"), "Documento adulterado.");
        var incorrect = verify("outra.p7s", signature, modified);
        assertNotEquals(VerificationStatus.APPROVED, incorrect.status());
        assertTrue(incorrect.signatures().isEmpty() || !incorrect.signatures().get(0).integrity());
    }
    @Test void doesNotApproveUnsignedGarbage() throws Exception {
        var result = verify("arquivo.txt", "Sem assinatura digital".getBytes(), null);
        assertEquals(VerificationStatus.NOT_VERIFIED, result.status());
        assertTrue(result.signatures().isEmpty());
    }
    @Test void refusesVerificationWithoutTrustAnchors() throws Exception {
        Files.delete(paths.anchors().resolve("test-root.crt"));
        assertThrows(IllegalStateException.class, () -> verify("assinatura.p7m", fixture.cms(fixture.content, true), null));
    }
    @Test void refusesUntrustedSigner() throws Exception {
        var unrelated = new SignedFixtures();
        Files.write(paths.anchors().resolve("test-root.crt"), unrelated.root.getEncoded());
        var result = verify("nao-confiavel.p7m", fixture.cms(fixture.content, true), null);
        assertNotEquals(VerificationStatus.APPROVED, result.status());
    }
    @Test void untrustedPdfProducesIndeterminateReportAndRemainsExportable() throws Exception {
        var unrelated = new SignedFixtures();
        Files.write(paths.anchors().resolve("test-root.crt"), unrelated.root.getEncoded());
        var result = verify("sem-cadeia-confiavel.pdf", fixture.pdf(), null);
        assertEquals(VerificationStatus.INDETERMINATE, result.status());
        assertEquals(1, result.signatures().size());
        assertEquals("Unknown", result.signatures().get(0).certificatePath());
        assertTrue(result.message().contains("raízes de confiança"));
        assertFalse(result.signatures().get(0).messages().isEmpty());
        for (var format : ReportExportService.Format.values()) {
            Path output = directory.resolve("untrusted." + format.name());
            new ReportExportService().export(List.of(result), output, format);
            assertTrue(Files.size(output) > 0);
        }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "verificador.regressionFile", matches = ".+")
    void verifiesExternalRegressionDocumentWithoutCrashing() throws Exception {
        Files.delete(paths.anchors().resolve("test-root.crt"));
        Path roots = Path.of(System.getProperty("verificador.regressionRoots"));
        try (var files = Files.list(roots)) {
            for (Path root : files.filter(Files::isRegularFile).toList()) Files.copy(root, paths.anchors().resolve(root.getFileName()));
        }
        String intermediates = System.getProperty("verificador.regressionIntermediates");
        if (intermediates != null) {
            try (var files = Files.list(Path.of(intermediates))) {
                for (Path file : files.filter(Files::isRegularFile).toList()) Files.copy(file, paths.intermediates().resolve(file.getFileName()));
            }
        }
        Path input = Path.of(System.getProperty("verificador.regressionFile"));
        var result = new IcpBrasilEngine(paths).verify(new VerificationInput(input, null));
        System.out.println("EXTERNAL_REGRESSION_RESULT status=" + result.status() + " message=" + result.message());
        for (var signature : result.signatures()) System.out.println("EXTERNAL_SIGNATURE status=" + signature.status() + " path=" + signature.certificatePath() + " messages=" + signature.messages());
        assertNotEquals(VerificationStatus.ERROR, result.status());
        String expectedStatus = System.getProperty("verificador.regressionExpectedStatus");
        if (expectedStatus != null) assertEquals(VerificationStatus.valueOf(expectedStatus), result.status());
        assertFalse(result.signatures().isEmpty());
        for (var format : ReportExportService.Format.values()) {
            Path output = directory.resolve("external-regression." + format.name());
            new ReportExportService().export(List.of(result), output, format);
            assertTrue(Files.size(output) > 0);
        }
        System.out.println("EXTERNAL_REGRESSION_OK status=" + result.status() + " signatures=" + result.signatures().size());
    }
    @Test void exportsRealEngineReportInAllFormats() throws Exception {
        var result = verify("assinatura.p7m", fixture.cms(fixture.content, true), null);
        assertFalse(result.signatures().isEmpty());
        var exporter = new ReportExportService();
        for (var format : ReportExportService.Format.values()) {
            Path file = directory.resolve("report." + format.name().toLowerCase());
            exporter.export(List.of(result), file, format);
            assertTrue(Files.size(file) > 100);
            if (format == ReportExportService.Format.PDF) {
                try (var pdf = org.apache.pdfbox.pdmodel.PDDocument.load(file.toFile())) { assertTrue(pdf.getNumberOfPages() >= 2); }
            }
        }
    }
}
