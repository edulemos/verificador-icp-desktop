package br.com.verificador.desktop.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record VerificationInput(Path signature, Path detached) {
    public static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    public VerificationInput {
        signature = signature.toAbsolutePath().normalize();
        if (detached != null) detached = detached.toAbsolutePath().normalize();
    }
    public void validate() throws IOException {
        check(signature);
        if (detached != null) {
            check(detached);
            if (Files.isSameFile(signature, detached)) throw new IOException("A assinatura e o documento original devem ser arquivos diferentes.");
        }
    }
    private static void check(Path file) throws IOException {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) throw new IOException("Arquivo inacessível: " + file);
        long size = Files.size(file);
        if (size == 0) throw new IOException("Arquivo vazio: " + file.getFileName());
        if (size > MAX_FILE_BYTES) throw new IOException("O limite por arquivo é de 100 MB: " + file.getFileName());
    }
}
