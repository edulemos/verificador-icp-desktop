package br.com.verificador.desktop.service;

import br.com.verificador.desktop.model.SignerIdentity;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.security.auth.x500.X500Principal;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.cms.Time;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSSignedData;
import org.w3c.dom.Element;

public final class SignatureMetadataReader {
    public List<SignerIdentity> read(byte[] bytes) {
        // Extraction is descriptive. Unsupported attributes must not change the engine's verdict.
        try (var pdf = PDDocument.load(bytes)) {
            List<SignerIdentity> identities = new ArrayList<>();
            for (var signature : pdf.getSignatureDictionaries()) {
                String subFilter = signature.getSubFilter();
                if ("ETSI.RFC3161".equals(subFilter)) continue;
                Instant pdfDate = signature.getSignDate() == null ? null : signature.getSignDate().toInstant();
                try { identities.addAll(cms(br.ufsc.labsec.signature.PdfSignatureContents.unpad(signature.getContents(bytes)), pdfDate)); } catch (Exception ignored) { }
            }
            return identities;
        } catch (Exception ignored) { }
        try { return cms(bytes, null); } catch (Exception ignored) { }
        try { return xml(bytes); } catch (Exception ignored) { return List.of(); }
    }

    private List<SignerIdentity> cms(byte[] bytes, Instant fallbackDate) throws Exception {
        var cms = new CMSSignedData(bytes);
        List<SignerIdentity> identities = new ArrayList<>();
        for (var signer : cms.getSignerInfos().getSigners()) {
            var matches = cms.getCertificates().getMatches(signer.getSID());
            if (matches.size() != 1) continue;
            var certificate = (X509CertificateHolder) matches.iterator().next();
            Instant signingTime = null;
            try {
                var attribute = signer.getSignedAttributes() == null ? null : signer.getSignedAttributes().get(CMSAttributes.signingTime);
                if (attribute != null) signingTime = Time.getInstance(attribute.getAttrValues().getObjectAt(0)).getDate().toInstant();
            } catch (Exception ignored) { }
            identities.add(identity(certificate, signingTime != null ? signingTime : fallbackDate,
                    signingTime != null ? "Declarada nos atributos assinados" : fallbackDate != null ? "Declarada no PDF" : null));
        }
        return identities;
    }

    private List<SignerIdentity> xml(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        var document = br.ufsc.labsec.signature.SecureXml.newBuilder(factory).parse(new ByteArrayInputStream(bytes));
        var signatures = document.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Signature");
        List<SignerIdentity> identities = new ArrayList<>();
        for (int index = 0; index < signatures.getLength(); index++) {
            Element signature = (Element) signatures.item(index);
            Instant signedAt = null;
            var times = signature.getElementsByTagNameNS("*", "SigningTime");
            if (times.getLength() == 1) try { signedAt = OffsetDateTime.parse(times.item(0).getTextContent().trim()).toInstant(); } catch (Exception ignored) { }
            var certificates = signature.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "X509Certificate");
            for (int certIndex = 0; certIndex < certificates.getLength(); certIndex++) {
                try {
                    byte[] encoded = java.util.Base64.getMimeDecoder().decode(certificates.item(certIndex).getTextContent());
                    var certificate = new X509CertificateHolder(encoded);
                    var constraints = BasicConstraints.fromExtensions(certificate.getExtensions());
                    if (constraints != null && constraints.isCA()) continue;
                    identities.add(identity(certificate, signedAt, signedAt == null ? null : "Declarada no XML"));
                } catch (Exception ignored) { }
            }
        }
        return identities;
    }

    public SignerIdentity identity(X509CertificateHolder certificate, Instant signedAt, String timeSource) throws java.io.IOException {
        String cnpj = null, cpf = null, businessName = null;
        boolean representative = false;
        try {
            var extension = certificate.getExtension(Extension.subjectAlternativeName);
            if (extension != null) for (var name : GeneralNames.getInstance(extension.getParsedValue()).getNames()) {
                if (name.getTagNo() != GeneralName.otherName) continue;
                var sequence = ASN1Sequence.getInstance(name.getName());
                String oid = ASN1ObjectIdentifier.getInstance(sequence.getObjectAt(0)).getId();
                String value = text(sequence.getObjectAt(1), 0);
                if (value == null) continue;
                if ("2.16.76.1.3.3".equals(oid) && value.matches("[0-9]{14}")) cnpj = formatCnpj(value);
                if ("2.16.76.1.3.8".equals(oid) && !value.isBlank()) businessName = value;
                if (("2.16.76.1.3.1".equals(oid) || "2.16.76.1.3.4".equals(oid)) && value.length() >= 19) {
                    String digits = value.substring(8, 19);
                    if (digits.matches("[0-9]{11}")) { cpf = maskCpf(digits); representative = "2.16.76.1.3.4".equals(oid); }
                }
            }
        } catch (Exception ignored) { }
        var names = certificate.getSubject().getRDNs(BCStyle.CN);
        String commonName = names.length == 0 ? null : IETFUtils.valueToString(names[0].getFirst().getValue());
        String displayName = businessName == null ? commonName : businessName;
        if (cnpj != null && displayName != null) {
            String suffix = ":" + cnpj.replaceAll("[^0-9]", "");
            if (displayName.endsWith(suffix)) displayName = displayName.substring(0, displayName.length() - suffix.length()).trim();
        }
        return new SignerIdentity(new X500Principal(certificate.getSubject().getEncoded()).getName(),
                displayName, cnpj, cpf, representative,
                "0x" + certificate.getSerialNumber().toString(16), certificate.getIssuer().toString(), signedAt, timeSource);
    }

    private static String text(ASN1Encodable value, int depth) {
        if (depth > 5) return null;
        if (value instanceof ASN1TaggedObject tagged) return text(tagged.getBaseObject().toASN1Primitive(), depth + 1);
        if (value instanceof ASN1String string) return string.getString().trim();
        if (value instanceof ASN1OctetString octets) {
            try { return text(ASN1Primitive.fromByteArray(octets.getOctets()), depth + 1); }
            catch (Exception ignored) { return new String(octets.getOctets(), java.nio.charset.StandardCharsets.UTF_8).trim(); }
        }
        return null;
    }
    public static SignerIdentity takeMatching(List<SignerIdentity> identities, String subject) {
        if (subject == null) return SignerIdentity.EMPTY;
        try {
            var expected = new X500Principal(subject);
            for (var iterator = identities.iterator(); iterator.hasNext();) {
                var identity = iterator.next();
                if (identity.subject() != null && expected.equals(new X500Principal(identity.subject()))) { iterator.remove(); return identity; }
            }
        } catch (IllegalArgumentException ignored) { }
        return SignerIdentity.EMPTY;
    }
    public static String maskCpf(String digits) { return "***." + digits.substring(3, 6) + "." + digits.substring(6, 9) + "-**"; }
    private static String formatCnpj(String digits) { return digits.substring(0,2) + "." + digits.substring(2,5) + "." + digits.substring(5,8) + "/" + digits.substring(8,12) + "-" + digits.substring(12); }
}
