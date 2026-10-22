package br.com.verificador.desktop.ui;

import br.com.verificador.desktop.model.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;

public final class ResultsTableModel extends AbstractTableModel {
    public static final class Row {
        public VerificationInput input;
        public VerificationResult result;
        public String state = "Aguardando";
        Row(VerificationInput input) { this.input = input; }
    }
    private final List<Row> rows = new ArrayList<>();
    private static final String[] COLUMNS = {"Arquivo assinado", "Documento original", "Resultado", "Assinaturas", "Tempo"};
    public Row row(int index) { return rows.get(index); }
    public List<Row> rows() { return List.copyOf(rows); }
    public boolean add(VerificationInput input) {
        if (rows.stream().anyMatch(row -> row.input.equals(input))) return false;
        rows.add(new Row(input)); fireTableRowsInserted(rows.size() - 1, rows.size() - 1); return true;
    }
    public void clear() { rows.clear(); fireTableDataChanged(); }
    public void remove(int index) { rows.remove(index); fireTableRowsDeleted(index, index); }
    public void changed() { fireTableDataChanged(); }
    public List<VerificationResult> results() { return rows.stream().filter(row -> row.result != null).map(row -> row.result).toList(); }
    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public String getColumnName(int column) { return COLUMNS[column]; }
    @Override public Object getValueAt(int index, int column) {
        Row row = rows.get(index);
        return switch (column) {
            case 0 -> row.input.signature().getFileName().toString();
            case 1 -> row.input.detached() == null ? "—" : row.input.detached().getFileName().toString();
            case 2 -> row.result == null ? row.state : row.result.status().toString();
            case 3 -> row.result == null ? "—" : row.result.signatures().size();
            default -> row.result == null ? "—" : row.result.elapsedMillis() + " ms";
        };
    }
}
