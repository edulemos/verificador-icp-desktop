package br.com.verificador.desktop;

import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.cms.*;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.*;
import org.bouncycastle.util.CollectionStore;

final class SignedFixtures {
    final KeyPair rootKey;
    final KeyPair leafKey;
    final X509Certificate root;
    final X509Certificate leaf;
    final byte[] content = "Documento de teste de integridade.".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    SignedFixtures() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        rootKey = generator.generateKeyPair(); leafKey = generator.generateKeyPair();
        X500Name issuer = new X500Name("CN=Raiz de Teste Desktop,O=Testes,C=BR");
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(issuer, BigInteger.ONE, Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(20, ChronoUnit.DAYS)), issuer, rootKey.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        var utils = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(rootKey.getPublic()));
        root = new JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(rootKey.getPrivate())));
        builder = new JcaX509v3CertificateBuilder(issuer, BigInteger.TWO, Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(10, ChronoUnit.DAYS)), new X500Name("CN=Assinante de Teste,O=Testes,C=BR"), leafKey.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
        builder.addExtension(Extension.authorityKeyIdentifier, false, utils.createAuthorityKeyIdentifier(root));
        builder.addExtension(Extension.subjectAlternativeName, false, new org.bouncycastle.asn1.x509.GeneralNames(new org.bouncycastle.asn1.x509.GeneralName[]{
                otherName("2.16.76.1.3.3", "12345678000199"),
                otherName("2.16.76.1.3.4", "0101199012345678901"),
                otherName("2.16.76.1.3.8", "EMPRESA DE EXEMPLO - TESTE")
        }));
        leaf = new JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(rootKey.getPrivate())));
    }
    private static org.bouncycastle.asn1.x509.GeneralName otherName(String oid, String value) {
        return new org.bouncycastle.asn1.x509.GeneralName(org.bouncycastle.asn1.x509.GeneralName.otherName,
                new org.bouncycastle.asn1.DERSequence(new org.bouncycastle.asn1.ASN1Encodable[]{new org.bouncycastle.asn1.ASN1ObjectIdentifier(oid),
                        new org.bouncycastle.asn1.DERTaggedObject(true, 0, new org.bouncycastle.asn1.DERUTF8String(value))}));
    }
    byte[] cms(byte[] input, boolean encapsulate) throws Exception {
        var generator = new CMSSignedDataGenerator();
        generator.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(leafKey.getPrivate()), leaf));
        generator.addCertificates(new JcaCertStore(List.of(leaf, root)));
        var crl = new JcaX509v2CRLBuilder(root.getSubjectX500Principal(), Date.from(Instant.now().minusSeconds(60)));
        crl.setNextUpdate(Date.from(Instant.now().plus(1, ChronoUnit.DAYS)));
        generator.addCRLs(new CollectionStore<>(List.of(crl.build(new JcaContentSignerBuilder("SHA256withRSA").build(rootKey.getPrivate())))));
        return generator.generate(new CMSProcessableByteArray(input), encapsulate).getEncoded();
    }
    byte[] pdf() throws Exception {
        try (var document = new org.apache.pdfbox.pdmodel.PDDocument(); var output = new java.io.ByteArrayOutputStream()) {
            document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            var signature = new org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature();
            signature.setFilter(org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setSignDate(Calendar.getInstance());
            signature.setName("Assinante de Teste");
            // PDFBox signs incrementally against an existing PDF.
            document.save(output);
            try (var existing = org.apache.pdfbox.pdmodel.PDDocument.load(output.toByteArray()); var signed = new java.io.ByteArrayOutputStream()) {
                existing.addSignature(signature, input -> { try { return cms(input.readAllBytes(), false); } catch (Exception e) { throw new java.io.IOException(e); } });
                existing.saveIncremental(signed); return signed.toByteArray();
            }
        }
    }
    byte[] xml() throws Exception {
        var factory = javax.xml.crypto.dsig.XMLSignatureFactory.getInstance("DOM");
        var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        var rootElement = document.createElement("documento"); rootElement.setTextContent("Documento de teste"); document.appendChild(rootElement);
        var reference = factory.newReference("", factory.newDigestMethod(javax.xml.crypto.dsig.DigestMethod.SHA256, null),
                List.of(factory.newTransform(javax.xml.crypto.dsig.Transform.ENVELOPED, (javax.xml.crypto.dsig.spec.TransformParameterSpec) null)), null, null);
        var signedInfo = factory.newSignedInfo(factory.newCanonicalizationMethod(javax.xml.crypto.dsig.CanonicalizationMethod.INCLUSIVE,
                (javax.xml.crypto.dsig.spec.C14NMethodParameterSpec) null), factory.newSignatureMethod(javax.xml.crypto.dsig.SignatureMethod.RSA_SHA256, null), List.of(reference));
        var keyInfoFactory = factory.getKeyInfoFactory();
        var keyInfo = keyInfoFactory.newKeyInfo(List.of(keyInfoFactory.newX509Data(List.of(leaf, root))));
        factory.newXMLSignature(signedInfo, keyInfo).sign(new javax.xml.crypto.dsig.dom.DOMSignContext(leafKey.getPrivate(), rootElement));
        var transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty("indent", "no");
        var bytes = new java.io.ByteArrayOutputStream();
        transformer.transform(new javax.xml.transform.dom.DOMSource(document), new javax.xml.transform.stream.StreamResult(bytes));
        return bytes.toByteArray();
    }
}
