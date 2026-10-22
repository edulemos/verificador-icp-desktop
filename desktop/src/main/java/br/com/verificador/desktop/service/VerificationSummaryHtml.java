package br.com.verificador.desktop.service;

import br.com.verificador.desktop.model.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.naming.ldap.LdapName;

/** Shared by the Swing summary and exported HTML. The status comes exclusively from the engine. */
public final class VerificationSummaryHtml {
    private final DateTimeFormatter dates;
    public VerificationSummaryHtml() { this(ZoneId.systemDefault()); }
    public VerificationSummaryHtml(ZoneId zone) { dates = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss z").withZone(zone); }

    public String document(List<VerificationResult> results) {
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"UTF-8\"><title>Resumo da verificação</title></head><body style=\"font-family:Segoe UI,Arial,sans-serif;font-size:12px;color:#1c2c3d;background-color:#ffffff;margin:18px\">");
        for (var result : results) append(html, result);
        return html.append("</body></html>").toString();
    }
    private void append(StringBuilder html, VerificationResult result) {
        html.append("<h3>Informações gerais do arquivo:</h3><table width=\"100%\" cellpadding=\"3\" cellspacing=\"0\" border=\"0\">");
        row(html, "Nome do arquivo", result.input().signature().getFileName().toString());
        row(html, "Hash (SHA-256)", result.fileSha256());
        row(html, "Data da validação", dates.format(result.checkedAt()));
        row(html, "Resultado do arquivo", result.status().toString());
        if (result.input().detached() != null) row(html, "Documento original", result.input().detached().getFileName().toString());
        html.append("</table><hr>");
        if (result.signatures().isEmpty()) html.append("<p><b>").append(escape(result.status().toString())).append("</b> — ").append(escape(result.message())).append("</p>");
        int index = 1;
        for (var signature : result.signatures()) {
            var identity = signature.identity();
            String color = switch (signature.status()) { case APPROVED -> "#28a745"; case REJECTED, ERROR -> "#c53333"; default -> "#a36912"; };
            String symbol = switch (signature.status()) { case APPROVED -> "&#10003;"; case REJECTED, ERROR -> "&#10007;"; default -> "!"; };
            html.append("<table width=\"100%\" cellpadding=\"5\" cellspacing=\"0\" border=\"0\"><tr><td width=\"30\" align=\"center\" bgcolor=\"").append(color)
                    .append("\"><font color=\"#ffffff\" size=\"5\"><b>").append(symbol).append("</b></font></td><td><h3>Informações da assinatura")
                    .append(result.signatures().size() > 1 ? " " + index : "").append(":</h3></td></tr></table>");
            html.append("<table width=\"100%\" cellpadding=\"3\" cellspacing=\"0\" border=\"0\">");
            row(html, "Assinado por", identity.name() == null ? displayName(signature.signer()) : identity.name());
            if (identity.cnpj() != null) row(html, "CNPJ", identity.cnpj());
            if (identity.maskedCpf() != null) row(html, identity.representative() ? "CPF do representante" : "CPF", identity.maskedCpf());
            row(html, "Nº de série do certificado", identity.serialNumber());
            row(html, "Emitido por", identity.issuer());
            row(html, "Data da assinatura (declarada)", identity.signedAt() == null ? null : dates.format(identity.signedAt()));
            html.append("</table><p><b><font color=\"").append(color).append("\">").append(verdict(signature.status())).append("</font></b></p>");
            for (String message : signature.messages()) if (message != null && !message.isBlank()) html.append("<p>").append(escape(message)).append("</p>");
            html.append("<hr>"); index++;
        }
    }
    private static String verdict(VerificationStatus status) {
        return switch (status) {
            case APPROVED -> "Assinatura aprovada.";
            case REJECTED -> "Assinatura reprovada.";
            case INDETERMINATE -> "Assinatura com validade indeterminada.";
            case NOT_VERIFIED -> "Assinatura não verificada.";
            case ERROR -> "Não foi possível concluir a verificação.";
        };
    }
    public static String displayName(String subject) {
        if (subject == null) return null;
        try { for (var rdn : new LdapName(subject).getRdns()) if ("CN".equalsIgnoreCase(rdn.getType())) return String.valueOf(rdn.getValue()); }
        catch (Exception ignored) { }
        return subject;
    }
    private static void row(StringBuilder html, String label, String value) {
        html.append("<tr><td width=\"235\" valign=\"top\" nowrap><b>").append(escape(label)).append(":</b></td><td valign=\"top\">").append(escape(value)).append("</td></tr>");
    }
    public static String escape(String value) {
        return value == null || value.isBlank() ? "Não informado" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
