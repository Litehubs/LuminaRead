package com.luminaread.ui;

import com.luminaread.service.RanobeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RanobeCoverRaceTest {
    @Test void oldCoverCannotReplaceNewAndClosingCancelsLoad(@TempDir Path home) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"),
                Probe.class.getName()).redirectErrorStream(true).start();
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
        finally { process.destroyForcibly(); }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            CountDownLatch oldStarted = new CountDownLatch(1), oldRelease = new CountDownLatch(1);
            CountDownLatch closeStarted = new CountDownLatch(1), interrupted = new CountDownLatch(1);
            RanobePanel[] panel = new RanobePanel[1];
            try {
                SwingUtilities.invokeAndWait(() -> panel[0] = new RanobePanel(null, new RanobeService() {
                    @Override public List<RanobeChapter> fetchChapters(String id) { return List.of(); }
                    @Override public RanobeItem fetchRanobeDetails(com.luminaread.model.ranobe.RanobeItem item) { return new RanobeItem(item); }
                }, false, url -> {
                    try {
                        if (url.equals("A")) { oldStarted.countDown(); oldRelease.await(); }
                        if (url.equals("C")) { closeStarted.countDown(); new CountDownLatch(1).await(); }
                    } catch (InterruptedException ex) { interrupted.countDown(); Thread.currentThread().interrupt(); return null; }
                    BufferedImage image = new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics(); g.setColor(url.equals("A") ? Color.RED : Color.GREEN); g.fillRect(0, 0, 5, 5); g.dispose();
                    return image;
                }));
                open(panel[0], "A");
                if (!oldStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Old image did not start");
                open(panel[0], "B");
                awaitGreen(panel[0]);
                oldRelease.countDown();
                Thread.sleep(300);
                SwingUtilities.invokeAndWait(() -> { if (!isGreen(panel[0])) throw new AssertionError("Old cover replaced new cover"); });
                open(panel[0], "C");
                if (!closeStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Slow image did not start");
                SwingUtilities.invokeAndWait(panel[0]::disposeResources);
                if (!interrupted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Close did not interrupt image task");
            } finally { oldRelease.countDown(); if (panel[0] != null) SwingUtilities.invokeAndWait(panel[0]::disposeResources); }
        }
        private static void open(RanobePanel panel, String id) throws Exception {
            var method = RanobePanel.class.getDeclaredMethod("showRanobeDetails", com.luminaread.model.ranobe.RanobeItem.class);
            method.setAccessible(true);
            var item = new com.luminaread.model.ranobe.RanobeItem(id, id, "", "", id, "", "", "", List.of(), List.of());
            SwingUtilities.invokeAndWait(() -> { try { method.invoke(panel, item); } catch (Exception e) { throw new RuntimeException(e); } });
        }
        private static void awaitGreen(RanobePanel panel) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < end) {
                boolean[] found = {false}; SwingUtilities.invokeAndWait(() -> found[0] = isGreen(panel));
                if (found[0]) return;
                Thread.sleep(20);
            }
            throw new AssertionError("New cover not displayed");
        }
        private static boolean isGreen(RanobePanel panel) {
            try {
                var f = RanobePanel.class.getDeclaredField("detailCover"); f.setAccessible(true);
                if (!(((JLabel) f.get(panel)).getIcon() instanceof ImageIcon icon)) return false;
                BufferedImage rendered = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rendered.createGraphics(); icon.paintIcon(null, g, 0, 0); g.dispose();
                return (rendered.getRGB(rendered.getWidth() / 2, rendered.getHeight() / 2) & 0xffffff) == 0x00ff00;
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        }
    }
}
