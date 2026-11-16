package com.luminaread.ui;

import com.luminaread.service.MangaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MangaPageRetryTest {
    @Test void retryUpdatesOnlyTheFailedPage(@TempDir Path home) throws Exception {
        String main = Path.of(MangaPanel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String tests = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + home, "-cp", main + File.pathSeparator + tests,
                Probe.class.getName()).redirectErrorStream(true).start();
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
        finally { process.destroyForcibly(); }
    }
    public static class Probe {
        public static void main(String[] args) throws Exception {
            AtomicInteger calls = new AtomicInteger(); MangaPanel[] panel = new MangaPanel[1];
            try {
                SwingUtilities.invokeAndWait(() -> panel[0] = new MangaPanel(null, new MangaService() {
                    @Override public BufferedImage loadImageCached(String url) {
                        if (calls.incrementAndGet() == 1) throw new IllegalStateException("simulated image failure");
                        return new BufferedImage(10, 20, BufferedImage.TYPE_INT_RGB);
                    }
                }, false));
                var pages = MangaPanel.class.getDeclaredField("currentPages"); pages.setAccessible(true);
                var canvas = MangaPanel.class.getDeclaredField("readerCanvasPanel"); canvas.setAccessible(true);
                var render = MangaPanel.class.getDeclaredMethod("renderReaderPages"); render.setAccessible(true);
                SwingUtilities.invokeAndWait(() -> { try { pages.set(panel[0], List.of("page")); render.invoke(panel[0]); } catch (Exception ex) { throw new RuntimeException(ex); } });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); MangaPageView[] page = new MangaPageView[1];
                while (System.nanoTime() < deadline) {
                    boolean[] ready = {false};
                    SwingUtilities.invokeAndWait(() -> { try {
                        page[0] = (MangaPageView) ((JPanel) canvas.get(panel[0])).getComponent(0);
                        ready[0] = ((JLabel) page[0].getComponent(0)).getText().contains("Не удалось");
                    } catch (Exception ex) { throw new RuntimeException(ex); } });
                    if (ready[0]) break;
                    Thread.sleep(20);
                }
                SwingUtilities.invokeAndWait(() -> {
                    JLabel label = (JLabel) page[0].getComponent(0);
                    if (!label.getText().contains("Не удалось")) throw new AssertionError("Error was not displayed");
                    label.dispatchEvent(new MouseEvent(label, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 5, 5, 1, false, MouseEvent.BUTTON1));
                });
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean loaded = false;
                while (System.nanoTime() < deadline && !loaded) {
                    boolean[] value = {false}; SwingUtilities.invokeAndWait(() -> value[0] = !page[0].getComponent(0).isVisible());
                    loaded = value[0]; if (!loaded) Thread.sleep(20);
                }
                if (!loaded || calls.get() != 2) throw new AssertionError("Retry did not update just the failed page: calls=" + calls.get());
            } finally { if (panel[0] != null) SwingUtilities.invokeAndWait(panel[0]::disposeResources); }
        }
    }
}
