package br.com.verificador.desktop;

import br.com.verificador.desktop.config.AppPaths;
import br.com.verificador.desktop.engine.IcpBrasilEngine;
import br.com.verificador.desktop.model.*;
import br.com.verificador.desktop.service.*;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VerificationSummaryTest {
    @TempDir Path directory;
    private static SignedFixtures fixture;
    @BeforeAll static void prepare() throws Exception { fixture = new SignedFixtures(); }
    @Test void extractsIcpIdentityAndDeclaredTimeFromCmsAndPdf() throws Exception {
        var reader = new SignatureMetadataReader();
        for (byte[] bytes : List.of(fixture.cms(fixture.content, true), fixture.pdf())) {
            var identities = reader.read(bytes);
            assertEquals(1, identities.size());
            var identity = identities.get(0);
            assertEquals("EMPRESA DE EXEMPLO - TESTE", identity.name());
            assertEquals("12.345.678/0001-99", identity.cnpj());
            assertEquals("***.456.789-**", identity.maskedCpf());
            assertTrue(identity.representative());
            assertEquals("0x2", identity.serialNumber());
            assertNotNull(identity.signedAt());
        }
    }
    @Test void associatesMetadataByCertificateSubjectAndNeverByRowPosition() throws Exception {
        var identities = new ArrayList<>(new SignatureMetadataReader().read(fixture.cms(fixture.content, true)));
        assertEquals(SignerIdentity.EMPTY, SignatureMetadataReader.takeMatching(identities, "CN=Outro Signatario"));
        assertEquals(1, identities.size());
        assertNotEquals(SignerIdentity.EMPTY, SignatureMetadataReader.takeMatching(identities, fixture.leaf.getSubjectX500Principal().getName()));
        assertTrue(identities.isEmpty());
    }
    @Test void plainXmlWithoutSigningTimeDoesNotInventDate() throws Exception {
        var identity = new SignatureMetadataReader().read(fixture.xml()).get(0);
        assertEquals("0x2", identity.serialNumber());
        assertNull(identity.signedAt());
    }
    @Test void integrationHashesTheVerifiedBytesAndEnrichesMatchingSigner() throws Exception {
        var paths = new AppPaths(directory.resolve("data")); paths.initialize();
        Files.write(paths.anchors().resolve("root.crt"), fixture.root.getEncoded());
        byte[] bytes = fixture.pdf(); Path pdf = Files.write(directory.resolve("assinado.pdf"), bytes);
        var result = new IcpBrasilEngine(paths).verify(new VerificationInput(pdf, null));
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(expected, result.fileSha256());
        assertEquals("12.345.678/0001-99", result.signatures().get(0).identity().cnpj());
        assertNotNull(result.signatures().get(0).identity().signedAt());
        String xml = new ReportExportService().xml(List.of(result));
        assertTrue(xml.contains("algorithm=\"SHA-256\""));
        assertTrue(xml.contains("***.456.789-**"));
        assertFalse(xml.contains("12345678901"));
    }
    @Test void summaryEscapesNamesMasksCpfAndKeepsIndeterminateVerdict() throws Exception {
        var identity = new SignatureMetadataReader().read(fixture.cms(fixture.content, true)).get(0);
        var signature = new SignatureDetails("CN=Test", "CMS", VerificationStatus.INDETERMINATE, true, true, "Unknown", null, List.of("<script>unsafe</script>"), identity);
        var result = new VerificationResult(new VerificationInput(directory.resolve("test.pdf"), null), VerificationStatus.INDETERMINATE,
                "Concluído", List.of(signature), null, Instant.parse("2026-10-21T21:40:15Z"), 3, "a".repeat(64));
        String html = new VerificationSummaryHtml(ZoneId.of("America/Sao_Paulo")).document(List.of(result));
        assertTrue(html.contains("Informações gerais do arquivo"));
        assertTrue(html.contains("CPF do representante"));
        assertTrue(html.contains("21/10/2026 18:40:15"));
        assertTrue(html.contains("Assinatura com validade indeterminada"));
        assertFalse(html.contains("Assinatura aprovada"));
        assertTrue(html.contains("&lt;script&gt;")); assertFalse(html.contains("<script>"));
    }
}
