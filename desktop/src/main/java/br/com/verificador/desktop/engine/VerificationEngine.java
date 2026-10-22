package br.com.verificador.desktop.engine;

import br.com.verificador.desktop.model.VerificationInput;
import br.com.verificador.desktop.model.VerificationResult;

public interface VerificationEngine {
    VerificationResult verify(VerificationInput input) throws Exception;
}
