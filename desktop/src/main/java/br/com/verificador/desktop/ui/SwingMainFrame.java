package br.com.verificador.desktop.ui;

import br.com.verificador.desktop.config.AppPaths;
import br.com.verificador.desktop.engine.VerificationEngine;
import br.com.verificador.desktop.model.*;
import br.com.verificador.desktop.service.ReportExportService;
import br.com.verificador.desktop.service.TrustAnchorService;
import br.com.verificador.desktop.service.IntermediateCertificateService;
import br.com.verificador.desktop.service.VerificationSummaryHtml;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.List;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

public final class SwingMainFrame extends JFrame {
    private static final Logger LOG = Logger.getLogger(SwingMainFrame.class.getName());
    private final VerificationEngine engine;
    private final TrustAnchorService anchors;
    private final ReportExportService exporter;
    private final AppPaths paths;
    private final JButton upload = new JButton("Selecionar arquivo…");
    private final JEditorPane summaryView = new JEditorPane("text/html", "");
    private final JLabel status = new JLabel("Selecione ou arraste um arquivo para iniciar.");
    private final JProgressBar progress = new JProgressBar();
    private final JMenuItem detached = new JMenuItem("Vincular documento original…");
    private final JMenuItem export = new JMenuItem("Exportar relatório…");
    private final JMenuItem update = new JMenuItem("Atualizar certificados");
    private VerificationInput input;
    private VerificationResult result;
    private boolean busy;

    public SwingMainFrame(VerificationEngine engine, TrustAnchorService anchors, ReportExportService exporter, AppPaths paths) {
        super("Verificador ICP-Brasil Desktop");
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("A janela deve ser criada na EDT.");
        this.engine = engine; this.anchors = anchors; this.exporter = exporter; this.paths = paths;
        build();
    }

    private void build() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                if (busy) {
                    JOptionPane.showMessageDialog(SwingMainFrame.this, "Aguarde a operação atual terminar antes de fechar.");
                    return;
                }
                dispose();
            }
        });
        JMenuBar menuBar = new JMenuBar();
        JMenu options = new JMenu("Opções");
        options.add(detached); options.add(export); options.addSeparator(); options.add(update);
        JMenuItem about = new JMenuItem("Sobre e configurações");
        options.add(about); menuBar.add(options); setJMenuBar(menuBar);
        detached.addActionListener(event -> linkOriginal());
        export.addActionListener(event -> exportReports());
        update.addActionListener(event -> updateCertificates());
        about.addActionListener(event -> showAbout());

        JPanel root = new JPanel(new BorderLayout(0, 22));
        root.setBorder(new EmptyBorder(24, 28, 20, 28));
        setContentPane(root);
        JPanel header = new JPanel(new BorderLayout(24, 0));
        header.setOpaque(false);
        JPanel titles = new JPanel(new GridLayout(0, 1, 0, 8)); titles.setOpaque(false);
        JLabel title = new JLabel("Verificador ICP-Brasil");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26));
        titles.add(title);
        titles.add(new JLabel("Selecione ou arraste um arquivo para validar automaticamente."));
        header.add(titles, BorderLayout.CENTER);
        JPanel selection = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)); selection.setOpaque(false);
        upload.setMargin(new Insets(12, 24, 12, 24));
        upload.addActionListener(event -> chooseFile());
        selection.add(upload); header.add(selection, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        JPanel content = new JPanel(new BorderLayout(0, 10)); content.setOpaque(false);
        JLabel heading = new JLabel("Resumo da validação");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 16));
        content.add(heading, BorderLayout.NORTH);
        summaryView.setEditable(false);
        summaryView.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        summaryView.setFont(UIManager.getFont("Label.font"));
        showMessage("Nenhum arquivo selecionado", "Arraste um arquivo para esta janela ou use Selecionar arquivo. Formatos: PDF, XML, P7S, P7M e CMS.");
        content.add(new JScrollPane(summaryView), BorderLayout.CENTER);
        root.add(content, BorderLayout.CENTER);

        JPanel feedback = new JPanel(new BorderLayout(12, 0)); feedback.setOpaque(false);
        feedback.add(status, BorderLayout.CENTER);
        progress.setPreferredSize(new Dimension(150, 16)); progress.setVisible(false);
        feedback.add(progress, BorderLayout.EAST); root.add(feedback, BorderLayout.SOUTH);
        installFileDrop(root, new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return !busy && support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    Object value = support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!(value instanceof List<?> files) || files.size() != 1 || !(files.get(0) instanceof File file)) {
                        status.setText("Arraste apenas um arquivo por vez.");
                        return false;
                    }
                    var selected = new VerificationInput(file.toPath(), null);
                    selected.validate();
                    validateFile(selected);
                    return true;
                } catch (Exception exception) { showError(exception); return false; }
            }
        });
        getRootPane().setDefaultButton(upload);
        setMinimumSize(new Dimension(680, 520));
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(960, screen.width), Math.min(780, screen.height)); setLocationRelativeTo(null);
        refresh();
    }

    private static void installFileDrop(JComponent component, TransferHandler handler) {
        TransferHandler previous = component.getTransferHandler();
        component.setTransferHandler(new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) { return handler.canImport(support); }
            @Override public boolean importData(TransferSupport support) { return handler.importData(support); }
            @Override public int getSourceActions(JComponent source) { return previous == null ? NONE : previous.getSourceActions(source); }
            @Override public void exportToClipboard(JComponent source, java.awt.datatransfer.Clipboard clipboard, int action) {
                if (previous != null) previous.exportToClipboard(source, clipboard, action);
            }
        });
        for (Component child : component.getComponents()) {
            if (child instanceof JComponent nested) installFileDrop(nested, handler);
        }
    }

    private void chooseFile() {
        if (busy) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Selecionar arquivo assinado");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("Assinaturas digitais (PDF, XML, P7S, P7M, CMS)", "pdf", "xml", "p7s", "p7m", "cms"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            validateFile(new VerificationInput(chooser.getSelectedFile().toPath(), null));
        }
    }

    private void linkOriginal() {
        if (busy || input == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Documento original correspondente à assinatura");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            validateFile(new VerificationInput(input.signature(), chooser.getSelectedFile().toPath()));
        }
    }

    private void validateFile(VerificationInput selected) {
        if (busy) return;
        try { selected.validate(); }
        catch (Exception exception) { showError(exception); return; }
        input = selected; result = null;
        setBusy(true);
        status.setText("Validando " + selected.signature().getFileName() + "…");
        showMessage(selected.signature().getFileName().toString(), "Validação em andamento. Aguarde o resultado.");
        new SwingWorker<VerificationResult, String>() {
            @Override protected VerificationResult doInBackground() throws Exception {
                boolean certificatesUpdated = false;
                if (anchors.count() == 0) {
                    certificatesUpdated = true;
                    publish("Preparando certificados de confiança para a primeira validação…");
                    List<String> failures = anchors.update(this::publish);
                    if (anchors.count() == 0) throw new IllegalStateException("Não foi possível obter certificados de confiança. Verifique a conexão ou consulte Sobre e configurações.\n" + String.join("\n", failures));
                }
                var intermediates = new IntermediateCertificateService(paths);
                if (!certificatesUpdated && !intermediates.isInstalled()) {
                    try { intermediates.update(this::publish); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw exception; }
                    catch (Exception exception) {
                        LOG.log(Level.WARNING, "Não foi possível obter o pacote de intermediárias do ITI", exception);
                        publish("Pacote de intermediárias indisponível. Validando com os certificados da assinatura e do cache…");
                    }
                }
                publish("Validando " + selected.signature().getFileName() + "…");
                long started = System.nanoTime();
                try { return engine.verify(selected); }
                catch (Exception exception) {
                    LOG.log(Level.WARNING, "Falha ao verificar " + selected.signature(), exception);
                    return VerificationResult.failure(selected, exception, (System.nanoTime() - started) / 1_000_000);
                }
            }
            @Override protected void process(List<String> messages) { status.setText(messages.get(messages.size() - 1)); }
            @Override protected void done() {
                try { displayResult(get()); }
                catch (Exception exception) {
                    status.setText("Não foi possível concluir a validação.");
                    showMessage(selected.signature().getFileName().toString(), cause(exception).getMessage());
                    showError(cause(exception));
                } finally { setBusy(false); }
            }
        }.execute();
    }

    private void showMessage(String title, String message) {
        summaryView.setText("<html><body style='margin:18px'><h3>" + VerificationSummaryHtml.escape(title)
                + "</h3><p>" + VerificationSummaryHtml.escape(message) + "</p></body></html>");
        summaryView.setCaretPosition(0);
    }

    public void displayResult(VerificationResult completed) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Atualize a janela na EDT.");
        input = completed.input(); result = completed;
        summaryView.setText(new VerificationSummaryHtml().document(List.of(completed)));
        summaryView.setCaretPosition(0);
        status.setText("Validação concluída: " + completed.status() + ".");
        refresh();
    }

    private void setBusy(boolean active) {
        busy = active; progress.setIndeterminate(active); progress.setVisible(active); refresh();
    }
    private void refresh() {
        upload.setEnabled(!busy); detached.setEnabled(!busy && input != null);
        export.setEnabled(!busy && result != null); update.setEnabled(!busy);
    }

    private void updateCertificates() {
        if (busy) return;
        setBusy(true); progress.setIndeterminate(true); status.setText("Atualizando certificados de confiança…");
        new SwingWorker<List<String>, String>() {
            @Override protected List<String> doInBackground() throws Exception { return anchors.update(this::publish); }
            @Override protected void process(List<String> messages) { status.setText(messages.get(messages.size() - 1)); }
            @Override protected void done() {
                try {
                    var failures = get(); int count = anchors.count();
                    int intermediates = new IntermediateCertificateService(paths).load().size();
                    status.setText(count + " raiz(es) e " + intermediates + " intermediária(s) disponível(is).");
                    if (!failures.isEmpty()) JOptionPane.showMessageDialog(SwingMainFrame.this, "A atualização não foi concluída para todos os certificados. As versões anteriores dos itens com falha foram preservadas.\n\n" + String.join("\n", failures), "Atualização parcial", JOptionPane.WARNING_MESSAGE);
                } catch (Exception e) { showError(cause(e)); }
                finally { progress.setIndeterminate(false); setBusy(false); }
            }
        }.execute();
    }

    private void exportReports() {
        List<VerificationResult> results = result == null ? List.<VerificationResult>of() : List.of(result); if (results.isEmpty()) return;
        var format = (ReportExportService.Format) JOptionPane.showInputDialog(this, "Formato do relatório de validação atual:", "Exportar relatório",
                JOptionPane.QUESTION_MESSAGE, null, ReportExportService.Format.values(), ReportExportService.Format.PDF);
        if (format == null) return;
        JFileChooser chooser = new JFileChooser(); String extension = format.name().toLowerCase(java.util.Locale.ROOT);
        chooser.setSelectedFile(new File("relatorio-verificacao." + extension));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path destination = chooser.getSelectedFile().toPath();
        if (!destination.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith("." + extension)) destination = destination.resolveSibling(destination.getFileName() + "." + extension);
        if (java.nio.file.Files.exists(destination) && JOptionPane.showConfirmDialog(this, "Substituir o arquivo existente?", "Exportar", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        final Path output = destination;
        setBusy(true); progress.setIndeterminate(true); status.setText("Gerando relatório…");
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { exporter.export(results, output, format); return null; }
            @Override protected void done() {
                try { get(); status.setText("Relatório salvo em " + output); }
                catch (Exception e) { showError(cause(e)); }
                finally { progress.setIndeterminate(false); setBusy(false); }
            }
        }.execute();
    }

    private void showAbout() {
        JTextArea info = new JTextArea("Verificador ICP-Brasil Desktop 0.1.0\n\nAplicativo independente baseado nos Códigos de Referência LABSEC 2.7.\nNão é o serviço oficial do ITI. A compatibilidade com assinaturas recentes depende do motor.\n\nDados locais: " + paths.data() + "\nCertificados raiz: " + paths.anchors() + "\nIntermediárias do ITI: " + paths.intermediates() + "\nLogs: " + paths.logs() + "\n\nAs raízes v5, v6, v12 e v13 são obtidas por HTTPS do repositório da AC-Raiz.\nAs intermediárias são obtidas do pacote oficial vigente do ITI, com conferência de SHA-512.\nElas completam cadeias, sem se tornarem raízes confiáveis.\nBouncy Castle 1.86; a cadeia v7/Ed521 permanece sem suporte.\nPara uso sem download automático, obtenha certificados raiz no repositório oficial do ITI\ne coloque os arquivos .crt/.cer/.pem na pasta de certificados.\nSomente adicione raízes nas quais confia: elas definem quais assinaturas serão aceitas.\n\nA seleção de um arquivo inicia a validação automaticamente.\nExporte o resultado antes de selecionar outro arquivo ou fechar.");
        info.setEditable(false); info.setLineWrap(true); info.setWrapStyleWord(true); info.setColumns(65); info.setRows(19); info.setCaretPosition(0);
        Object[] options = {"Fechar", "Abrir pasta de dados", "Abrir certificados"};
        int choice = JOptionPane.showOptionDialog(this, new JScrollPane(info), "Sobre e configurações", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
        if (choice == 1 || choice == 2) {
            try { Desktop.getDesktop().open((choice == 1 ? paths.data() : paths.anchors()).toFile()); }
            catch (Exception e) { showError(e); }
        }
    }
    private static Exception cause(Exception exception) { return exception.getCause() instanceof Exception cause ? cause : exception; }
    private void showError(Exception exception) { LOG.log(Level.WARNING, "Operação não concluída", exception); JOptionPane.showMessageDialog(this, exception.getMessage(), "Operação não concluída", JOptionPane.ERROR_MESSAGE); }
}
