package br.com.verificador.desktop.model;

import java.time.Instant;

/** Descriptive certificate/signing attributes; these do not determine signature validity. */
public record SignerIdentity(String subject, String name, String cnpj, String maskedCpf,
                             boolean representative, String serialNumber, String issuer,
                             Instant signedAt, String timeSource) {
    public static final SignerIdentity EMPTY = new SignerIdentity(null, null, null, null, false, null, null, null, null);
}
