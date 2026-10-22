package br.com.verificador.desktop.model;

import java.time.Instant;
import java.util.List;

public record VerificationResult(VerificationInput input, VerificationStatus status, String message,
                                 List<SignatureDetails> signatures, String reportXml, Instant checkedAt,
                                 long elapsedMillis, String fileSha256) {
    public VerificationResult { signatures = List.copyOf(signatures); }
    public VerificationResult(VerificationInput input, VerificationStatus status, String message,
                              List<SignatureDetails> signatures, String reportXml, Instant checkedAt, long elapsedMillis) {
        this(input, status, message, signatures, reportXml, checkedAt, elapsedMillis, null);
    }
    public static VerificationResult failure(VerificationInput input, Exception exception, long elapsed) {
        String message = exception.getMessage();
        return new VerificationResult(input, VerificationStatus.ERROR,
                message == null || message.isBlank() ? exception.getClass().getSimpleName() : message,
                List.of(), null, Instant.now(), elapsed);
    }
}
