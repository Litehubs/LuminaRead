package com.luminaread.ui;

import org.junit.jupiter.api.Test;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class AutoScrollControllerTest {
    @Test void bothReadersStartAndStopTheirScrollController(@TempDir Path home) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"),
                ReaderProbe.class.getName()).redirectErrorStream(true).start();
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
        finally { process.destroyForcibly(); }
    }

    public static class ReaderProbe {
        public static void main(String[] args) throws Exception {
            MangaPanel[] manga = new MangaPanel[1]; RanobePanel[] ranobe = new RanobePanel[1];
            try {
                SwingUtilities.invokeAndWait(() -> {
                    manga[0] = new MangaPanel(null, new com.luminaread.service.MangaService(), false);
                    ranobe[0] = new RanobePanel(null, new com.luminaread.service.RanobeService(), false);
                });
                check(manga[0]); check(ranobe[0]);
            } finally {
                SwingUtilities.invokeAndWait(() -> { if (manga[0] != null) manga[0].disposeResources(); if (ranobe[0] != null) ranobe[0].disposeResources(); });
            }
        }
        private static void check(Object panel) throws Exception {
            var paneField = panel.getClass().getDeclaredField("readerScrollPane"); paneField.setAccessible(true);
            var controllerField = panel.getClass().getDeclaredField("autoScroll"); controllerField.setAccessible(true);
            var start = panel.getClass().getDeclaredMethod("startAutoScroll"); start.setAccessible(true);
            var stop = panel.getClass().getDeclaredMethod("stopAutoScroll"); stop.setAccessible(true);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    ((JScrollPane) paneField.get(panel)).getVerticalScrollBar().setValues(0, 10, 0, 200);
                    start.invoke(panel);
                    if (!((AutoScrollController) controllerField.get(panel)).isRunning()) throw new AssertionError("Autoscroll did not start");
                    stop.invoke(panel);
                    if (((AutoScrollController) controllerField.get(panel)).isRunning()) throw new AssertionError("Autoscroll did not stop");
                } catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
            });
        }
    }
    @Test void speedIsInPixelsPerSecondAndEndStopsPlayback() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane pane = new JScrollPane();
            var bar = pane.getVerticalScrollBar();
            bar.setValues(0, 10, 0, 200);
            AtomicInteger ends = new AtomicInteger(), lastPosition = new AtomicInteger();
            AutoScrollController controller = new AutoScrollController(pane, () -> 10, 100, false,
                    lastPosition::set, ends::incrementAndGet);
            controller.start();
            for (int i = 0; i < 10; i++) controller.tick();
            assertEquals(10, bar.getValue());
            assertEquals(10, lastPosition.get());
            bar.setValue(189);
            controller.tick();
            assertEquals(1, ends.get());
            assertFalse(controller.isRunning());
        });
    }

    @Test void readerCanWaitForContentBeforeScrolling() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane pane = new JScrollPane();
            pane.getVerticalScrollBar().setValues(0, 100, 0, 100);
            AtomicInteger ends = new AtomicInteger();
            AutoScrollController controller = new AutoScrollController(pane, () -> 40, 16, true,
                    value -> {}, ends::incrementAndGet);
            controller.start();
            controller.tick();
            assertTrue(controller.isRunning());
            assertEquals(0, ends.get());
            controller.stop();
        });
    }
}
