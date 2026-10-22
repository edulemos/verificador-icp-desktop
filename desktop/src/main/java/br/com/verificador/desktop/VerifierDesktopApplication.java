package br.com.verificador.desktop;

import br.com.verificador.desktop.config.AppPaths;
import br.com.verificador.desktop.engine.VerificationEngine;
import br.com.verificador.desktop.service.ReportExportService;
import br.com.verificador.desktop.service.TrustAnchorService;
import br.com.verificador.desktop.ui.SwingMainFrame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;
import javax.swing.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class VerifierDesktopApplication {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "false");
        AppPaths paths = AppPaths.defaults(); paths.initialize();
        var application = new SpringApplication(VerifierDesktopApplication.class);
        application.setHeadless(false); application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext context = application.run(args);
        boolean smoke = Arrays.asList(args).contains("--smoke-test");
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(new javax.swing.plaf.nimbus.NimbusLookAndFeel());
                var frame = new SwingMainFrame(context.getBean(VerificationEngine.class), context.getBean(TrustAnchorService.class), context.getBean(ReportExportService.class), paths);
                frame.addWindowListener(new WindowAdapter() { @Override public void windowClosed(WindowEvent event) { context.close(); } });
                if (smoke) {
                    frame.addNotify();
                    var content = frame.getContentPane();
                    content.setSize(1200, 820); layout(content);
                    var image = new java.awt.image.BufferedImage(content.getWidth(), content.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics(); content.printAll(graphics); graphics.dispose();
                    javax.imageio.ImageIO.write(image, "png", paths.data().resolve("swing-preview.png").toFile());
                    frame.dispose(); System.out.println("SWING_SMOKE_OK");
                } else frame.setVisible(true);
            } catch (Exception e) {
                e.printStackTrace(); context.close();
                if (!smoke) JOptionPane.showMessageDialog(null, e.toString(), "Falha ao iniciar", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
    private static void layout(java.awt.Container container) {
        container.doLayout();
        for (var child : container.getComponents()) if (child instanceof java.awt.Container nested) layout(nested);
    }
}
