package br.com.verificador.desktop.engine;

import br.com.verificador.desktop.config.AppPaths;
import br.com.verificador.desktop.model.*;
import br.com.verificador.desktop.service.TrustAnchorService;
import br.com.verificador.desktop.service.IntermediateCertificateService;
import br.com.verificador.desktop.service.XmlSupport;
import br.com.verificador.desktop.service.SignatureMetadataReader;
import br.ufsc.labsec.component.Application;
import br.ufsc.labsec.signature.SignatureDataWrapper;
import br.ufsc.labsec.signature.conformanceVerifier.ConformanceVerifier;
import br.ufsc.labsec.signature.conformanceVerifier.gui.ReportGuiComponent;
import br.ufsc.labsec.signature.conformanceVerifier.report.SignatureReport;
import br.ufsc.labsec.signature.conformanceVerifier.report.NotICPBrasilSignatureReport;
import br.ufsc.labsec.signature.conformanceVerifier.validationService.TrustAnchorComponent;
import br.ufsc.labsec.signature.repository.PKCS12IdentityService.PKCS12Repository;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class IcpBrasilEngine implements VerificationEngine {
    // The reference engine uses a static component registry. Serialize access across instances.
    private static final Object LOCK = new Object();
    private final AppPaths paths;
    public IcpBrasilEngine(AppPaths paths) { this.paths = paths; }

    @Override public VerificationResult verify(VerificationInput input) throws Exception {
        input.validate();
        long start = System.nanoTime();
        synchronized (LOCK) {
            if (new TrustAnchorService(paths).count() == 0)
                throw new IllegalStateException("Nenhuma âncora de confiança instalada. Use Atualizar certificados antes de verificar.");
            var configuration = new ConformanceVerifier();
            configuration.component(PKCS12Repository.class)
                    .param("cachePath", paths.cache().toString())
                    .param("repositoryPath", paths.repository().toString());
            configuration.component(TrustAnchorComponent.class)
                    .param("trustAnchorsDirectory", paths.anchors() + File.separator)
                    .param("trustAnchorsURLs", "");
            byte[] signature = readBounded(input.signature());
            String sha256 = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(signature));
            byte[] detached = input.detached() == null ? new byte[0] : readBounded(input.detached());
            try (var sig = new ByteArrayInputStream(signature); var det = new ByteArrayInputStream(detached)) {
                Application app = new Application(configuration,
                        List.of(new SignatureDataWrapper(sig, det, input.signature().getFileName().toString())));
                if (!app.setup()) throw new IllegalStateException("Falha ao inicializar os componentes do motor ICP-Brasil.");
                var repository = (PKCS12Repository) app.getComponent(PKCS12Repository.class.getName());
                repository.getCertificateCollection().addCertificates(new IntermediateCertificateService(paths).load());
                var component = (ReportGuiComponent) app.getComponent(ReportGuiComponent.class.getName());
                var reports = component.startVerification();
                if (reports.size() != 1 || reports.get(0).getSignatures().isEmpty()) {
                    return new VerificationResult(input, VerificationStatus.NOT_VERIFIED,
                            "Nenhuma assinatura verificável. Confira o formato, o documento original de assinaturas destacadas, a cadeia de confiança e os logs.",
                            List.of(), null, Instant.now(), elapsed(start), sha256);
                }
                var report = reports.get(0);
                report.setNumber(1);
                List<SignatureDetails> details = new ArrayList<>();
                var identities = new ArrayList<>(new SignatureMetadataReader().read(signature));
                boolean incompleteCertificatePath = false;
                for (SignatureReport s : report.getSignatures()) {
                    var messages = new ArrayList<>(s.getErrorMessages());
                    if (s.getCertPathMessage() != null) messages.add(s.getCertPathMessage());
                    if (s instanceof NotICPBrasilSignatureReport) {
                        incompleteCertificatePath = true;
                        messages.add("O motor não conseguiu vincular esta assinatura às raízes de confiança configuradas. A integridade criptográfica não foi avaliada neste relatório parcial.");
                    }
                    details.add(new SignatureDetails(s.getSignerSubjectName(), s.getSignatureType(), map(s.validityStatus()),
                            s.isHash(), s.isNonRepudiable(), s.getCertPathState(), s.getSignaturePolicy(), messages,
                            SignatureMetadataReader.takeMatching(identities, s.getSignerSubjectName())));
                }
                var status = aggregate(details);
                String message = incompleteCertificatePath
                        ? "Uma ou mais assinaturas ficaram indeterminadas: não foi possível construir uma cadeia até as raízes de confiança configuradas. Consulte os detalhes."
                        : "Verificação concluída pelo motor de referência LABSEC 2.7.";
                return new VerificationResult(input, status, message,
                        details, XmlSupport.serialize(report.generate()), Instant.now(), elapsed(start), sha256);
            }
        }
    }

    private static byte[] readBounded(java.nio.file.Path path) throws java.io.IOException {
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes((int) VerificationInput.MAX_FILE_BYTES + 1);
            if (bytes.length > VerificationInput.MAX_FILE_BYTES) throw new java.io.IOException("Arquivo excede 100 MB: " + path);
            return bytes;
        }
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static VerificationStatus map(SignatureReport.SignatureValidity status) {
        return switch (status) {
            case Valid -> VerificationStatus.APPROVED;
            case Invalid -> VerificationStatus.REJECTED;
            case Indeterminate -> VerificationStatus.INDETERMINATE;
        };
    }
    public static VerificationStatus aggregate(List<SignatureDetails> signatures) {
        if (signatures.isEmpty()) return VerificationStatus.NOT_VERIFIED;
        if (signatures.stream().anyMatch(s -> s.status() == VerificationStatus.REJECTED)) return VerificationStatus.REJECTED;
        if (signatures.stream().allMatch(s -> s.status() == VerificationStatus.APPROVED)) return VerificationStatus.APPROVED;
        return VerificationStatus.INDETERMINATE;
    }
}
