package com.luminaread.ui;

import com.luminaread.model.ReaderSettings;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.TtsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReaderScrollNavigationTest {
    @Test
    void wheelOpensAdjacentImagePagesWhenTheWholePageFits(@TempDir Path home) throws Exception {
        String main = Path.of(ReaderPanel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String tests = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path log = home.resolve("reader-scroll.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Duser.home=" + home,
                "-cp", main + File.pathSeparator + tests, Probe.class.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(25, TimeUnit.SECONDS), "Reader scroll probe timed out");
            assertEquals(0, child.exitValue(), Files.readString(log));
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            LibraryStorageService storage = new LibraryStorageService();
            storage.getSettings().setReadingMode(ReaderSettings.ReadingMode.SCROLL);
            Path file = Path.of(System.getProperty("user.home"), "short-pages.cbz");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
                for (int page = 1; page <= 3; page++) {
                    zip.putNextEntry(new ZipEntry("page-" + page + ".png"));
                    ImageIO.write(new BufferedImage(64, 8, BufferedImage.TYPE_INT_RGB), "png", zip);
                    zip.closeEntry();
                }
            }
            var book = storage.importBookFile(file.toFile(), false);
            ReaderPanel[] panel = new ReaderPanel[1];
            try {
                SwingUtilities.invokeAndWait(() -> {
                    panel[0] = new ReaderPanel(storage, new TtsService(), text -> "", () -> {}, theme -> {});
                    panel[0].setSize(960, 850);
                    layout(panel[0]);
                    panel[0].openBook(book);
                });

                var chapterField = ReaderPanel.class.getDeclaredField("currentChapterIndex");
                chapterField.setAccessible(true);
                var scrollField = ReaderPanel.class.getDeclaredField("editorScrollPane");
                scrollField.setAccessible(true);
                var automatic = ReaderPanel.class.getDeclaredMethod("performAutoScrollStep");
                automatic.setAccessible(true);
                var toggle = ReaderPanel.class.getDeclaredMethod("toggleAutoScroll");
                toggle.setAccessible(true);
                var contentField = ReaderPanel.class.getDeclaredField("currentContent");
                contentField.setAccessible(true);

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
                boolean[] ready = new boolean[1];
                while (!ready[0] && System.nanoTime() < deadline) {
                    SwingUtilities.invokeAndWait(() -> ready[0] = read(contentField, panel[0]) != null);
                    if (!ready[0]) Thread.sleep(20);
                }
                require(ready[0], "Comic failed to load");

                SwingUtilities.invokeAndWait(() -> {
                    layout(panel[0]);
                    JScrollPane scroll = (JScrollPane) read(scrollField, panel[0]);
                    require(scroll.getVerticalScrollBar().getMaximum()
                            == scroll.getVerticalScrollBar().getVisibleAmount(), "Fixture page must fit");
                    wheel(scroll, 1);
                    require(read(chapterField, panel[0]).equals(1), "Wheel did not open second page");
                    layout(panel[0]);
                    setTime(panel[0], "lastWheelChapterChange", System.nanoTime());
                    wheel(scroll, 1);
                    require(read(chapterField, panel[0]).equals(1), "Wheel burst skipped a page");
                    wheel(scroll, -1);
                    require(read(chapterField, panel[0]).equals(0), "Wheel did not return to first page");
                    storage.getSettings().setReadingMode(ReaderSettings.ReadingMode.PAGED);
                    panel[0].applyTheme(ThemePalette.byId("EINK_MATTE"));
                    wheel(scroll, 1);
                    require(read(chapterField, panel[0]).equals(0), "Paged mode must not turn pages with the wheel");

                    try {
                        toggle.invoke(panel[0]);
                        automatic.invoke(panel[0]);
                        require(read(chapterField, panel[0]).equals(0), "Autoscroll must pause on a short page");
                        setTime(panel[0], "pageShownAt", System.nanoTime() - TimeUnit.SECONDS.toNanos(2));
                        automatic.invoke(panel[0]);
                        require(read(chapterField, panel[0]).equals(1), "Autoscroll got stuck on a short page");
                    } catch (ReflectiveOperationException ex) {
                        throw new AssertionError(ex);
                    }
                });
            } finally {
                if (panel[0] != null) SwingUtilities.invokeAndWait(panel[0]::disposeResources);
            }
        }

        private static void layout(Container parent) {
            parent.doLayout();
            for (Component child : parent.getComponents()) {
                if (child instanceof Container container) layout(container);
            }
        }

        private static Object read(java.lang.reflect.Field field, ReaderPanel panel) {
            try {
                return field.get(panel);
            } catch (IllegalAccessException ex) {
                throw new AssertionError(ex);
            }
        }

        private static void setTime(ReaderPanel panel, String name, long timestamp) {
            try {
                var field = ReaderPanel.class.getDeclaredField(name);
                field.setAccessible(true);
                field.setLong(panel, timestamp);
            } catch (ReflectiveOperationException ex) {
                throw new AssertionError(ex);
            }
        }

        private static void wheel(JScrollPane pane, int rotation) {
            pane.dispatchEvent(new MouseWheelEvent(pane, MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), 0, 100, 100, 0, false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation));
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
