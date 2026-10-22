package br.ufsc.labsec.signature;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import org.bouncycastle.asn1.ASN1InputStream;

/** Removes only the zero padding reserved by PDF /Contents, preserving the original CMS bytes. */
public final class PdfSignatureContents {
    private PdfSignatureContents() { }
    public static byte[] unpad(byte[] contents) throws IOException {
        var bytes = new ByteArrayInputStream(contents);
        try (var asn1 = new ASN1InputStream(bytes)) {
            if (asn1.readObject() == null) throw new IOException("Conteúdo CMS do PDF vazio.");
            int length = contents.length - bytes.available();
            for (int index = length; index < contents.length; index++)
                if (contents[index] != 0) throw new IOException("Dados adicionais após o CMS no PDF.");
            return Arrays.copyOf(contents, length);
        }
    }
}
