package br.com.verificador.desktop.service;

import br.com.verificador.desktop.config.AppPaths;
import br.com.verificador.desktop.engine.IcpBrasilEngine;
import br.com.verificador.desktop.model.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.cms.*;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.*;
import org.bouncycastle.util.CollectionStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IntermediateCertificateServiceTest {
    @TempDir Path temporary;
    private static KeyPair rootKey, caKey, leafKey;
    private static X509Certificate root, ca, leaf;

    @BeforeAll static void certificates() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        rootKey = generator.generateKeyPair(); caKey = generator.generateKeyPair(); leafKey = generator.generateKeyPair();
        root = certificate("CN=Root", rootKey, "CN=Root", rootKey, true, 1);
        ca = certificate("CN=Intermediate", caKey, "CN=Root", rootKey, true, 2);
        leaf = certificate("CN=Signer", leafKey, "CN=Intermediate", caKey, false, 3);
    }
    private static X509Certificate certificate(String subject, KeyPair key, String issuer, KeyPair issuerKey, boolean isCa, int serial) throws Exception {
        var builder = new JcaX509v3CertificateBuilder(new X500Name(issuer), BigInteger.valueOf(serial),
                Date.from(Instant.now().minusSeconds(3600)), Date.from(Instant.now().plusSeconds(86400)), new X500Name(subject), key.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCa));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(isCa ? KeyUsage.keyCertSign | KeyUsage.cRLSign : KeyUsage.digitalSignature));
        var utils = new JcaX509ExtensionUtils();
        builder.addExtension(Extension.subjectKeyIdentifier, false, utils.createSubjectKeyIdentifier(key.getPublic()));
        builder.addExtension(Extension.authorityKeyIdentifier, false, utils.createAuthorityKeyIdentifier(issuerKey.getPublic()));
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey.getPrivate())));
    }
    private AppPaths paths() throws Exception { var paths = new AppPaths(temporary.resolve("data")); paths.initialize(); return paths; }
    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    private static String checksum(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes)) + "  ACcompactado.zip"; }
    private static IntermediateCertificateService service(AppPaths paths, byte[] archive, String checksum) {
        return new IntermediateCertificateService(paths, (uri, limit) -> uri.equals(IntermediateCertificateService.ARCHIVE)
                ? archive : checksum.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void importsOnlyIntermediatesWithoutTrustingPackagedRootsOrExtractingPaths() throws Exception {
        var paths = paths();
        byte[] archive = zip(Map.of("../../outside.crt", ca.getEncoded(), "root.crt", root.getEncoded(), "duplicate.cer", ca.getEncoded()));
        var service = service(paths, archive, checksum(archive));
        assertEquals(1, service.update(message -> {}));
        assertEquals(ca, service.load().get(0));
        assertEquals(0, new TrustAnchorService(paths).count());
        assertFalse(Files.exists(temporary.resolve("outside.crt")));
        try (var files = Files.list(paths.intermediates())) { assertEquals(1, files.count()); }
    }

    @Test void failedChecksumDownloadOrMalformedPackagePreservesPreviousArchive() throws Exception {
        var paths = paths();
        byte[] valid = zip(Map.of("ca.crt", ca.getEncoded()));
        var service = service(paths, valid, checksum(valid)); service.update(message -> {});
        assertThrows(IOException.class, () -> service(paths, valid, "0".repeat(128)).update(message -> {}));
        assertThrows(IOException.class, () -> service(paths, valid, "pagina HTML").update(message -> {}));
        byte[] invalid = zip(Map.of("leaf.crt", leaf.getEncoded()));
        assertThrows(IOException.class, () -> service(paths, invalid, checksum(invalid)).update(message -> {}));
        assertThrows(IOException.class, () -> new IntermediateCertificateService(paths, (uri, limit) -> { throw new IOException("offline"); }).update(message -> {}));
        assertEquals(List.of(ca), service.load());
    }

    @Test void rejectsOversizedZipEntryAndInvalidCertificate() throws Exception {
        byte[] oversized = zip(Map.of("large.crt", new byte[256 * 1024 + 1]));
        assertThrows(IOException.class, () -> IntermediateCertificateService.readArchive(oversized));
        byte[] invalid = zip(Map.of("invalid.crt", new byte[]{1, 2, 3}));
        assertThrows(Exception.class, () -> IntermediateCertificateService.readArchive(invalid));
    }

    @Test void completesMissingIntermediateButStillRequiresAnInstalledTrustedRoot() throws Exception {
        var paths = paths();
        Files.write(paths.anchors().resolve("test-root.crt"), root.getEncoded());
        byte[] signed = signedCms(false);
        var input = new VerificationInput(Files.write(temporary.resolve("signed.p7m"), signed), null);
        var before = new IcpBrasilEngine(paths).verify(input);
        assertNotEquals(VerificationStatus.APPROVED, before.status());
        byte[] archive = zip(Map.of("ca.crt", ca.getEncoded(), "root.crt", root.getEncoded()));
        service(paths, archive, checksum(archive)).update(message -> {});
        var after = new IcpBrasilEngine(paths).verify(input);
        assertFalse(after.signatures().isEmpty(), after.message());
        assertTrue(after.signatures().get(0).integrity());
        assertEquals("Valid", after.signatures().get(0).certificatePath());
        var revokedInput = new VerificationInput(Files.write(temporary.resolve("revoked.p7m"), signedCms(true)), null);
        var revoked = new IcpBrasilEngine(paths).verify(revokedInput);
        assertNotEquals(VerificationStatus.APPROVED, revoked.status());
        assertEquals("Revoked", revoked.signatures().get(0).certificatePath());
        // Reusing the same package in another trust context must not inherit trust or cached issuers.
        var unrelated = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var otherRoot = certificate("CN=Other Root", unrelated, "CN=Other Root", unrelated, true, 4);
        Files.write(paths.anchors().resolve("test-root.crt"), otherRoot.getEncoded());
        var untrusted = new IcpBrasilEngine(paths).verify(input);
        assertNotEquals(VerificationStatus.APPROVED, untrusted.status());
    }

    private static byte[] signedCms(boolean revoked) throws Exception {
        var cms = new CMSSignedDataGenerator();
        cms.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(leafKey.getPrivate()), leaf));
        cms.addCertificates(new JcaCertStore(List.of(leaf)));
        var crls = new ArrayList<org.bouncycastle.cert.X509CRLHolder>();
        for (var pair : List.of(Map.entry(root, rootKey), Map.entry(ca, caKey))) {
            var crl = new JcaX509v2CRLBuilder(pair.getKey().getSubjectX500Principal(), Date.from(Instant.now().minusSeconds(60)));
            crl.setNextUpdate(Date.from(Instant.now().plusSeconds(3600)));
            if (revoked && pair.getKey().equals(ca)) crl.addCRLEntry(leaf.getSerialNumber(), Date.from(Instant.now().minusSeconds(120)), 1);
            crls.add(crl.build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getValue().getPrivate())));
        }
        cms.addCRLs(new CollectionStore<>(crls));
        return cms.generate(new CMSProcessableByteArray(new byte[]{1, 2, 3}), true).getEncoded();
    }

    @Test void acceptsOnlyZeroPaddingAfterPdfCms() throws Exception {
        byte[] original = new org.bouncycastle.asn1.DERSequence().getEncoded();
        byte[] padded = Arrays.copyOf(original, original.length + 100);
        assertArrayEquals(original, br.ufsc.labsec.signature.PdfSignatureContents.unpad(padded));
        padded[padded.length - 1] = 1;
        assertThrows(IOException.class, () -> br.ufsc.labsec.signature.PdfSignatureContents.unpad(padded));
    }

    @Test void verifiesEcdsaAndStandardEdDsaCmsSignaturesWithUpdatedProvider() throws Exception {
        var paths = paths();
        Files.write(paths.anchors().resolve("test-root.crt"), root.getEncoded());
        for (String algorithm : List.of("SHA256withECDSA", "SHA384withECDSA", "SHA512withECDSA", "Ed25519", "Ed448")) {
            boolean ec = algorithm.endsWith("ECDSA");
            var generator = KeyPairGenerator.getInstance(ec ? "EC" : algorithm, "BC");
            if (ec) generator.initialize(new java.security.spec.ECGenParameterSpec(switch (algorithm) {
                case "SHA256withECDSA" -> "secp256r1";
                case "SHA384withECDSA" -> "secp384r1";
                default -> "secp521r1";
            }));
            var key = generator.generateKeyPair();
            var certificate = certificate("CN=" + algorithm, key, "CN=Root", rootKey, false, 20);
            var cms = new CMSSignedDataGenerator();
            cms.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().setProvider("BC").build())
                    .build(new JcaContentSignerBuilder(algorithm).setProvider("BC").build(key.getPrivate()), certificate));
            cms.addCertificates(new JcaCertStore(List.of(certificate, root)));
            byte[] signed = cms.generate(new CMSProcessableByteArray(new byte[]{4, 5, 6}), true).getEncoded();
            var input = new VerificationInput(Files.write(temporary.resolve(algorithm + ".p7m"), signed), null);
            var result = new IcpBrasilEngine(paths).verify(input);
            assertFalse(result.signatures().isEmpty(), algorithm + ": " + result.message());
            assertTrue(result.signatures().get(0).integrity(), algorithm);
            assertTrue(result.signatures().get(0).asymmetricCipher(), algorithm);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "verificador.testOfficialCertificates", matches = "true")
    void downloadsAndValidatesTheActualOfficialPackage() throws Exception {
        var paths = paths();
        var failures = new TrustAnchorService(paths).update(System.out::println);
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(4, new TrustAnchorService(paths).count());
        var certificates = new IntermediateCertificateService(paths).load();
        assertFalse(certificates.isEmpty());
        System.out.println("ITI_PACKAGE_OK roots=4 intermediates=" + certificates.size());
        var provider = new BouncyCastleProvider();
        assertNotNull(Signature.getInstance("Ed25519", provider));
        assertNotNull(Signature.getInstance("Ed448", provider));
        // The private ICP-Brasil Ed521 OID is still unsupported; upgrading BC does not imply v7 coverage.
        assertThrows(NoSuchAlgorithmException.class, () -> Signature.getInstance("1.3.6.1.4.1.44588.2.1", provider));
    }
}
