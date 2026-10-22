package br.com.verificador.desktop.model;

import java.util.List;

public record SignatureDetails(String signer, String type, VerificationStatus status,
                               boolean integrity, boolean asymmetricCipher, String certificatePath,
                               String policy, List<String> messages, SignerIdentity identity) {
    public SignatureDetails { messages = List.copyOf(messages); identity = identity == null ? SignerIdentity.EMPTY : identity; }
    public SignatureDetails(String signer, String type, VerificationStatus status, boolean integrity,
                            boolean asymmetricCipher, String certificatePath, String policy, List<String> messages) {
        this(signer, type, status, integrity, asymmetricCipher, certificatePath, policy, messages, SignerIdentity.EMPTY);
    }
}
