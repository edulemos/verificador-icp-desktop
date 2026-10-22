package br.com.verificador.desktop.model;

public enum VerificationStatus {
    APPROVED("Aprovado"), REJECTED("Reprovado"), INDETERMINATE("Indeterminado"),
    NOT_VERIFIED("Não verificado"), ERROR("Erro");
    private final String label;
    VerificationStatus(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
