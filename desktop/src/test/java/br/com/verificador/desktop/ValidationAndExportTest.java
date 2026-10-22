package br.com.verificador.desktop;

import br.com.verificador.desktop.engine.IcpBrasilEngine;
import br.com.verificador.desktop.model.*;
import br.com.verificador.desktop.service.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ValidationAndExportTest {
    @TempDir Path directory;
    @Test void emptyMissingAndSameOriginalAreRejected() throws Exception {
        Path empty = Files.createFile(directory.resolve("empty"));
        Path file = Files.writeString(directory.resolve("document"), "data");
        assertThrows(Exception.class, () -> new VerificationInput(empty, null).validate());
        assertThrows(Exception.class, () -> new VerificationInput(directory.resolve("missing"), null).validate());
        assertThrows(Exception.class, () -> new VerificationInput(file, file).validate());
    }
    @Test void noSignaturesNeverMeansApproved() {
        assertEquals(VerificationStatus.NOT_VERIFIED, IcpBrasilEngine.aggregate(List.of()));
        assertEquals(VerificationStatus.INDETERMINATE, IcpBrasilEngine.aggregate(List.of(signature(VerificationStatus.APPROVED), signature(VerificationStatus.INDETERMINATE))));
        assertEquals(VerificationStatus.REJECTED, IcpBrasilEngine.aggregate(List.of(signature(VerificationStatus.INDETERMINATE), signature(VerificationStatus.REJECTED))));
    }
    private SignatureDetails signature(VerificationStatus status) { return new SignatureDetails("Test", "CMS", status, true, true, "Valid", null, List.of()); }
    @Test void externalEntitiesAreRejected() {
        assertThrows(Exception.class, () -> XmlSupport.parse("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///sensitive'>]><a>&x;</a>"));
    }
    @Test void referenceEngineRejectsDoctypeBeforeReadingExternalEntities() {
        byte[] xml = "<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///sensitive'>]><a>&x;</a>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(Exception.class, () -> new br.ufsc.labsec.signature.conformanceVerifier.xml.XmlSignatureContainer(
                new java.io.ByteArrayInputStream(xml), new java.io.ByteArrayInputStream(new byte[0]), null));
        assertThrows(Exception.class, () -> new br.ufsc.labsec.signature.conformanceVerifier.xades.XadesSignatureContainer(new java.io.ByteArrayInputStream(xml)));
    }
    @Test void failuresAreIncludedAndHtmlIsEscaped() throws Exception {
        var input = new VerificationInput(directory.resolve("test.pdf"), null);
        var failure = new VerificationResult(input, VerificationStatus.ERROR, "<script>alert('x')</script>", List.of(), null, Instant.now(), 1);
        var exporter = new ReportExportService();
        assertTrue(exporter.html(List.of(failure)).contains("&lt;script&gt;"));
        assertFalse(exporter.html(List.of(failure)).contains("<script>"));
        for (var format : ReportExportService.Format.values()) {
            Path destination = directory.resolve("failure." + format);
            exporter.export(List.of(failure), destination, format);
            assertTrue(Files.size(destination) > 0);
        }
    }
}
