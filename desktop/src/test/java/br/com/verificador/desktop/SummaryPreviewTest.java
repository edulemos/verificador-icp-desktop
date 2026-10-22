package br.com.verificador.desktop;

import br.com.verificador.desktop.config.AppPaths;
import br.com.verificador.desktop.engine.IcpBrasilEngine;
import br.com.verificador.desktop.model.VerificationInput;
import br.com.verificador.desktop.service.*;
import br.com.verificador.desktop.ui.SwingMainFrame;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SummaryPreviewTest {
    @TempDir Path temporary;
    @Test
    @EnabledIfSystemProperty(named = "verificador.previewFile", matches = ".+")
    void rendersActualSummaryWithoutChangingVerdict() throws Exception {
        var paths = new AppPaths(temporary.resolve("data")); paths.initialize();
        try (var roots = Files.list(Path.of(System.getProperty("verificador.previewRoots")))) {
            for (Path root : roots.filter(Files::isRegularFile).toList()) Files.copy(root, paths.anchors().resolve(root.getFileName()));
        }
        var engine = new IcpBrasilEngine(paths);
        var result = engine.verify(new VerificationInput(Path.of(System.getProperty("verificador.previewFile")), null));
        assertFalse(result.signatures().isEmpty());
        assertNotEquals(br.com.verificador.desktop.model.SignerIdentity.EMPTY, result.signatures().get(0).identity());
        Path output = Path.of(System.getProperty("verificador.previewOutput")); Files.createDirectories(output);
        Files.writeString(output.resolve("resumo-validacao.html"), new VerificationSummaryHtml().document(List.of(result)));
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (var look : UIManager.getInstalledLookAndFeels()) if ("Nimbus".equals(look.getName())) UIManager.setLookAndFeel(look.getClassName());
                var frame = new SwingMainFrame(engine, new TrustAnchorService(paths), paths);
                try {
                    frame.addNotify(); frame.displayResult(result);
                    frame.getContentPane().setSize(1200, 820); layout(frame.getContentPane());
                    var image = new BufferedImage(1200, 820, BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics(); frame.getContentPane().printAll(graphics); graphics.dispose();
                    ImageIO.write(image, "png", output.resolve("interface-resumo.png").toFile());
                    var pane = htmlPane(frame.getContentPane()); assertNotNull(pane);
                    pane.setSize(1050, 760); pane.doLayout();
                    image = new BufferedImage(1050, 760, BufferedImage.TYPE_INT_RGB);
                    graphics = image.createGraphics(); pane.printAll(graphics); graphics.dispose();
                    ImageIO.write(image, "png", output.resolve("resumo-validacao.png").toFile());
                } finally { frame.dispose(); }
            } catch (Exception exception) { throw new RuntimeException(exception); }
        });
        System.out.println("SUMMARY_PREVIEW_OK status=" + result.status() + " signatures=" + result.signatures().size()
                + " identified=" + result.signatures().stream().filter(s -> s.identity().serialNumber() != null).count());
    }
    private static void layout(Container container) { container.doLayout(); for (var child : container.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static JEditorPane htmlPane(Container container) {
        for (var child : container.getComponents()) {
            if (child instanceof JEditorPane pane) return pane;
            if (child instanceof Container nested) { var found = htmlPane(nested); if (found != null) return found; }
        }
        return null;
    }
}
