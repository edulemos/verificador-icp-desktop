package br.com.verificador.desktop.ui;

import br.com.verificador.desktop.config.AppPaths;
import br.com.verificador.desktop.engine.VerificationEngine;
import br.com.verificador.desktop.model.*;
import br.com.verificador.desktop.service.TrustAnchorService;
import br.com.verificador.desktop.service.IntermediateCertificateService;
import br.com.verificador.desktop.service.VerificationSummaryHtml;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.List;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

public final class SwingMainFrame extends JFrame {
    private static final Logger LOG = Logger.getLogger(SwingMainFrame.class.getName());
    private final VerificationEngine engine;
    private final TrustAnchorService anchors;
    private final AppPaths paths;
    private final JButton upload = new JButton("Selecionar arquivo…");
    private final JEditorPane summaryView = new JEditorPane("text/html", "");
    private final JLabel status = new JLabel("Selecione ou arraste um PDF para iniciar.");
    private final JProgressBar progress = new JProgressBar();
    private VerificationResult result;
    private boolean busy;

    public SwingMainFrame(VerificationEngine engine, TrustAnchorService anchors, AppPaths paths) {
        super("Verificador ICP-Brasil Desktop");
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("A janela deve ser criada na EDT.");
        this.engine = engine; this.anchors = anchors; this.paths = paths;
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
        JPanel root = new JPanel(new BorderLayout(0, 22));
        root.setBorder(new EmptyBorder(24, 28, 20, 28));
        setContentPane(root);
        JPanel header = new JPanel(new BorderLayout(24, 0));
        header.setOpaque(false);
        JPanel titles = new JPanel(new GridLayout(0, 1, 0, 8)); titles.setOpaque(false);
        JLabel title = new JLabel("Verificador ICP-Brasil");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26));
        titles.add(title);
        titles.add(new JLabel("Selecione ou arraste um PDF para validar automaticamente."));
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
        showMessage("Nenhum arquivo selecionado", "Arraste um arquivo PDF para esta janela ou use Selecionar arquivo. Somente arquivos .pdf são aceitos.");
        content.add(new JScrollPane(summaryView), BorderLayout.CENTER);
        root.add(content, BorderLayout.CENTER);

        JPanel feedback = new JPanel(new BorderLayout(12, 0)); feedback.setOpaque(false);
        feedback.add(status, BorderLayout.CENTER);
        progress.setPreferredSize(new Dimension(150, 16)); progress.setVisible(false);
        feedback.add(progress, BorderLayout.EAST);
        JButton repository = new JButton("<html><u>github</u></html>");
        repository.setForeground(new Color(34, 86, 139));
        repository.setBorderPainted(false); repository.setContentAreaFilled(false);
        repository.setMargin(new Insets(0, 0, 0, 0));
        repository.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        repository.setToolTipText("Abrir o repositório do projeto no GitHub");
        repository.getAccessibleContext().setAccessibleName("Repositório do projeto no GitHub");
        repository.addActionListener(event -> openRepository());
        JPanel links = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)); links.setOpaque(false);
        links.add(new JLabel("projeto open source hospedado no "));
        links.add(repository);
        JPanel footer = new JPanel(new BorderLayout(0, 10)); footer.setOpaque(false);
        footer.add(feedback, BorderLayout.NORTH); footer.add(links, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);
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
                    return validateFile(new VerificationInput(file.toPath(), null));
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
        chooser.setDialogTitle("Selecionar PDF assinado");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("Documento PDF (*.pdf)", "pdf"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            validateFile(new VerificationInput(chooser.getSelectedFile().toPath(), null));
        }
    }

    private boolean validateFile(VerificationInput selected) {
        if (busy) return false;
        if (!selected.signature().getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            status.setText("Somente arquivos PDF (.pdf) são permitidos.");
            return false;
        }
        try { selected.validate(); }
        catch (Exception exception) { showError(exception); return false; }
        result = null;
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
                    if (anchors.count() == 0) throw new IllegalStateException("Não foi possível obter certificados de confiança. Verifique a conexão e os certificados na pasta de dados do aplicativo.\n" + String.join("\n", failures));
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
        return true;
    }

    private void showMessage(String title, String message) {
        summaryView.setText("<html><body style='margin:18px'><h3>" + VerificationSummaryHtml.escape(title)
                + "</h3><p>" + VerificationSummaryHtml.escape(message) + "</p></body></html>");
        summaryView.setCaretPosition(0);
    }

    public void displayResult(VerificationResult completed) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Atualize a janela na EDT.");
        result = completed;
        summaryView.setText(new VerificationSummaryHtml().document(List.of(completed)));
        summaryView.setCaretPosition(0);
        status.setText("Validação concluída: " + completed.status() + ".");
        refresh();
    }

    private void setBusy(boolean active) {
        busy = active; progress.setIndeterminate(active); progress.setVisible(active); refresh();
    }
    private void refresh() { upload.setEnabled(!busy); }

    private void openRepository() {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                throw new java.io.IOException("Não foi possível abrir o navegador. Acesse https://github.com/edulemos/verificador-icp-desktop");
            Desktop.getDesktop().browse(URI.create("https://github.com/edulemos/verificador-icp-desktop"));
        } catch (Exception exception) { showError(exception); }
    }

    private static Exception cause(Exception exception) { return exception.getCause() instanceof Exception cause ? cause : exception; }
    private void showError(Exception exception) { LOG.log(Level.WARNING, "Operação não concluída", exception); JOptionPane.showMessageDialog(this, exception.getMessage(), "Operação não concluída", JOptionPane.ERROR_MESSAGE); }
}
