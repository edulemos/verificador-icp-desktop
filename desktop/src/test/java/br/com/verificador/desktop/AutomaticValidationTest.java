package br.com.verificador.desktop;

import br.com.verificador.desktop.config.AppPaths;
import br.com.verificador.desktop.model.*;
import br.com.verificador.desktop.service.*;
import br.com.verificador.desktop.ui.SwingMainFrame;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticValidationTest {
    @TempDir Path temporary;

    @Test void fileDropStartsBackgroundValidationAndReplacesPreviousSummary() throws Exception {
        var fixture = new SignedFixtures();
        var paths = new AppPaths(temporary.resolve("data")); paths.initialize();
        Files.write(paths.anchors().resolve("test-root.crt"), fixture.root.getEncoded());
        // Keep this UI regression offline; package integration is covered separately.
        try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(paths.intermediates().resolve("iti-ac-vigente.zip")))) { }
        var selected = new VerificationInput(Files.writeString(temporary.resolve("documento.PDF"), "conteudo"), null);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var window = new AtomicReference<SwingMainFrame>();
        var completed = new VerificationResult(selected, VerificationStatus.NOT_VERIFIED, "Sem assinatura digital", List.of(), null, Instant.now(), 1);
        SwingUtilities.invokeAndWait(() -> {
            var frame = new SwingMainFrame(input -> {
                try {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    assertEquals(selected, input);
                    entered.countDown();
                    assertTrue(finish.await(5, TimeUnit.SECONDS));
                    return completed;
                } catch (Throwable error) { failure.set(error); throw new IllegalStateException(error); }
            }, new TrustAnchorService(paths), paths);
            window.set(frame);
            frame.displayResult(new VerificationResult(new VerificationInput(temporary.resolve("anterior.pdf"), null), VerificationStatus.NOT_VERIFIED, "Anterior", List.of(), null, Instant.now(), 1));
            var target = pane(frame.getContentPane());
            var transfer = files(selected.signature().toFile());
            var support = new TransferHandler.TransferSupport(target, transfer);
            assertTrue(target.getTransferHandler().canImport(support));
            assertTrue(target.getTransferHandler().importData(support));
            assertFalse(target.getTransferHandler().canImport(support), "Não deve substituir o arquivo durante a validação");
            assertFalse(frame.getRootPane().getDefaultButton().isEnabled());
            assertFalse(pane(frame.getContentPane()).getText().contains("anterior.pdf"));
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); finish.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            var done = new AtomicReference<Boolean>(false);
            while (!done.get() && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> done.set(window.get().getRootPane().getDefaultButton().isEnabled()));
                if (!done.get()) Thread.sleep(20);
            }
            assertTrue(done.get(), "A seleção deve ser reativada ao concluir");
            assertNull(failure.get());
            SwingUtilities.invokeAndWait(() -> {
                String html = pane(window.get().getContentPane()).getText();
                assertTrue(html.contains("documento.PDF"));
                assertTrue(html.contains("Sem assinatura digital"));
                assertFalse(html.contains("anterior.pdf"));
            });
        } finally {
            finish.countDown();
            SwingUtilities.invokeAndWait(() -> window.get().dispose());
        }
    }
    @Test void rejectsOtherExtensionsFromDragAndSelectionWithoutStartingValidation() throws Exception {
        var paths = new AppPaths(temporary.resolve("data")); paths.initialize();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        SwingUtilities.invokeAndWait(() -> {
            var frame = new SwingMainFrame(input -> { calls.incrementAndGet(); throw new AssertionError("Arquivo não PDF chegou ao motor"); }, new TrustAnchorService(paths), paths);
            try {
                var target = pane(frame.getContentPane());
                var select = SwingMainFrame.class.getDeclaredMethod("validateFile", VerificationInput.class);
                select.setAccessible(true);
                for (String name : List.of("documento.xml", "documento.txt", "documento.pdf.exe", "assinatura.p7s", "sem-extensao")) {
                    var file = Files.writeString(temporary.resolve(name), "conteudo").toFile();
                    assertFalse(target.getTransferHandler().importData(new TransferHandler.TransferSupport(target, files(file))), name);
                    assertEquals(false, select.invoke(frame, new VerificationInput(file.toPath(), null)), name);
                }
                assertEquals(0, calls.get());
                assertTrue(frame.getRootPane().getDefaultButton().isEnabled());
                assertTrue(target.getText().contains("Nenhum arquivo selecionado"));
            } catch (Exception error) { throw new RuntimeException(error); }
            finally { frame.dispose(); }
        });
    }
    private static java.awt.datatransfer.Transferable files(java.io.File... files) {
        return new java.awt.datatransfer.Transferable() {
            public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.javaFileListFlavor};
            }
            public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
                return java.awt.datatransfer.DataFlavor.javaFileListFlavor.equals(flavor);
            }
            public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) { return List.of(files); }
        };
    }
    private static JEditorPane pane(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JEditorPane editor) return editor;
            if (child instanceof Container nested) { var found = pane(nested); if (found != null) return found; }
        }
        return null;
    }
}
