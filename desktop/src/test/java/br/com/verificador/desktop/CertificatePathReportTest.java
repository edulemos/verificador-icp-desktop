package br.com.verificador.desktop;

import br.ufsc.labsec.signature.conformanceVerifier.report.NotICPBrasilSignatureReport;
import br.ufsc.labsec.signature.conformanceVerifier.report.SignatureReport;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CertificatePathReportTest {
    @Test void partialUntrustedSignatureHasUnknownCertificateState() {
        var report = new NotICPBrasilSignatureReport();
        assertEquals("Unknown", report.getCertPathState());
        assertEquals(SignatureReport.SignatureValidity.Indeterminate, report.validityStatus());
        assertFalse(report.isValid());
        assertNotNull(report.getCertPathMessage());
    }
    @Test void missingCertificateValidationDoesNotCrashXmlGenerationOrApproveSignature() throws Exception {
        var report = new SignatureReport();
        report.setHash(true);
        report.setAsymmetricCipher(true);
        report.setSchema(SignatureReport.SchemaState.VALID);
        assertEquals(SignatureReport.SignatureValidity.Indeterminate, report.validityStatus());
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        var signature = report.generateSignatureElement(document);
        assertEquals("Unknown", signature.getElementsByTagName("certPathValid").item(0).getTextContent());
    }
    @Test void explicitNullStateIsNormalizedAndValidStateDoesNotKeepUnknownWarning() {
        var report = new SignatureReport();
        report.setCertificationPathValid(null);
        assertEquals("Unknown", report.getCertPathState());
        report.setCertificationPathValid(SignatureReport.CertValidity.Valid);
        assertEquals("Valid", report.getCertPathState());
        assertNull(report.getCertPathMessage());
    }
}
