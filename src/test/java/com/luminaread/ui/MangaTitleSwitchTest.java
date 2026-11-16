package com.luminaread.ui;

import com.luminaread.service.MangaService;
import com.luminaread.model.manga.MangaChapter;
import com.luminaread.model.manga.MangaItem;
import org.junit.jupiter.api.Test;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MangaTitleSwitchTest {
    @Test void lateDetailsAreIgnoredEvenWhenSourceFinishesAfterCancellation() throws Exception {
        var started = new CountDownLatch(3);
        var cancelled = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var returned = new CountDownLatch(3);
        MangaService service = new MangaService() {
            private void delay(String id) {
                if (!"old".equals(id)) return;
                started.countDown();
                while (true) {
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Old response blocked");
                        returned.countDown();
                        return;
                    } catch (InterruptedException ex) {
                        cancelled.countDown();
                    }
                }
            }

            @Override public BufferedImage loadImageCached(String id) {
                delay(id);
                BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
                image.setRGB(0, 0, "old".equals(id) ? Color.RED.getRGB() : Color.GREEN.getRGB());
                return image;
            }

            @Override public List<MangaChapter> fetchChapters(MangaItem item) {
                delay(item.id);
                return List.of(new MangaChapter(item.id, "1", item.id + " chapter", "ru", "MangaLib"));
            }

            @Override public String fetchDescription(MangaItem item) {
                delay(item.id);
                return item.id + " description";
            }
        };

        MangaPanel[] panel = new MangaPanel[1];
        var show = MangaPanel.class.getDeclaredMethod("showMangaDetails", MangaItem.class);
        show.setAccessible(true);
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel[0] = new MangaPanel(null, service, false);
                open(show, panel[0], "old");
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> open(show, panel[0], "new"));
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            awaitNewDetails(panel[0]);
            release.countDown();
            assertTrue(returned.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> assertTrue(hasNewDetails(panel[0])));
        } finally {
            release.countDown();
        }
    }

    private static void open(java.lang.reflect.Method show, MangaPanel panel, String id) {
        try {
            show.invoke(panel, new MangaItem(id, id, "", "", id, "MangaLib", "", ""));
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static void awaitNewDetails(MangaPanel panel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean[] ready = {false};
            SwingUtilities.invokeAndWait(() -> ready[0] = hasNewDetails(panel));
            if (ready[0]) return;
            Thread.sleep(20);
        }
        fail("New title details were not shown");
    }

    private static boolean hasNewDetails(MangaPanel panel) {
        try {
            var description = MangaPanel.class.getDeclaredField("detailDesc");
            var chapters = MangaPanel.class.getDeclaredField("chapterJList");
            var cover = MangaPanel.class.getDeclaredField("detailCover");
            description.setAccessible(true);
            chapters.setAccessible(true);
            cover.setAccessible(true);
            var list = ((JList<?>) chapters.get(panel)).getModel();
            if (!"new description".equals(((JTextArea) description.get(panel)).getText())
                    || list.getSize() != 1 || !list.getElementAt(0).toString().contains("new chapter")
                    || !(((JLabel) cover.get(panel)).getIcon() instanceof ImageIcon icon)) return false;

            BufferedImage sample = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = sample.createGraphics();
            try {
                icon.paintIcon(null, graphics, 0, 0);
            } finally {
                graphics.dispose();
            }
            return sample.getRGB(sample.getWidth() / 2, sample.getHeight() / 2) == Color.GREEN.getRGB();
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
