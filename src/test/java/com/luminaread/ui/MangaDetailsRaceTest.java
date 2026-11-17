package com.luminaread.ui;

import com.luminaread.model.manga.MangaItem;
import com.luminaread.model.manga.MangaChapter;

import com.luminaread.service.MangaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

final class MangaDetailsRaceTest {
    @Test void failedChaptersAreVisibleAndFailedPagesDoNotAdvanceProgress(@TempDir Path home) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"),
                FailureProbe.class.getName()).redirectErrorStream(true).start();
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
        finally { process.destroyForcibly(); }
    }
    public static class FailureProbe {
        public static void main(String[] args) throws Exception {
            CountDownLatch releasePages = new CountDownLatch(1);
            try {
                SwingUtilities.invokeAndWait(() -> Probe.panel = new MangaPanel(null, new MangaService() {
                    @Override public List<MangaChapter> fetchChapters(MangaItem item) { throw new IllegalStateException("offline"); }
                    @Override public List<String> fetchPageUrls(MangaChapter chapter) {
                        try { releasePages.await(); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                        throw new IllegalStateException("offline");
                    }
                    @Override public String fetchDescription(MangaItem item) { return ""; }
                    @Override public BufferedImage loadImageCached(String url) { return null; }
                }, false));
                Probe.open("broken");
                var status = MangaPanel.class.getDeclaredField("chapterStatus"); status.setAccessible(true);
                Probe.await(() -> {
                    try { return ((JLabel) status.get(Probe.panel)).getText().contains("Не удалось загрузить главы"); }
                    catch (Exception ex) { throw new RuntimeException(ex); }
                }, "Chapter error was not shown");
                Probe.await(() -> MangaSearchRaceTest.Probe.labelExists(Probe.panel, "Обложка недоступна"),
                        "Cover failure left the loading indicator active");
                var chapters = MangaPanel.class.getDeclaredField("chapters"); chapters.setAccessible(true);
                var pages = MangaPanel.class.getDeclaredField("currentPages"); pages.setAccessible(true);
                var canvas = MangaPanel.class.getDeclaredField("readerCanvasPanel"); canvas.setAccessible(true);
                var render = MangaPanel.class.getDeclaredMethod("renderReaderPages"); render.setAccessible(true);
                var open = MangaPanel.class.getDeclaredMethod("openChapterAtIndex", int.class); open.setAccessible(true);
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        ((MangaChapterList) chapters.get(Probe.panel)).setChapters(List.of(new MangaChapter("c", "1", "", "ru", "MangaLib")));
                        pages.set(Probe.panel, List.of("old-page"));
                        open.invoke(Probe.panel, 0);
                        render.invoke(Probe.panel);
                        Probe.require(MangaSearchRaceTest.Probe.labelExists((Container) canvas.get(Probe.panel),
                                "⏳ Загрузка страниц главы 1..."), "Refreshing the layout replaced the pending chapter");
                    } catch (Exception ex) { throw new RuntimeException(ex); }
                });
                releasePages.countDown();
                Probe.await(() -> MangaSearchRaceTest.Probe.labelExists(Probe.panel, "⚠️ Не удалось загрузить страницы для этой главы."), "Page error was not shown");
                var progress = MangaPanel.class.getDeclaredField("lastReadChapterMap"); progress.setAccessible(true);
                SwingUtilities.invokeAndWait(() -> {
                    try { Probe.require(((java.util.Map<?, ?>) progress.get(Probe.panel)).isEmpty(), "Failed chapter changed progress"); }
                    catch (Exception ex) { throw new RuntimeException(ex); }
                });
            } finally {
                releasePages.countDown();
                if (Probe.panel != null) SwingUtilities.invokeAndWait(Probe.panel::disposeResources);
            }
        }
    }
    @Test void switchingTitlesNeverShowsChaptersFromPreviousTitle(@TempDir Path home) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"),
                Probe.class.getName()).redirectErrorStream(true).start();
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
        finally { process.destroyForcibly(); }
    }
    @Test void lateResponsesCannotReplaceCurrentTitle(@TempDir Path home) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"),
                LateProbe.class.getName()).redirectErrorStream(true).start();
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
        finally { process.destroyForcibly(); }
    }
    @Test void closingPanelCancelsDetailCoverLoad(@TempDir Path home) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"),
                CloseCoverProbe.class.getName()).redirectErrorStream(true).start();
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
        finally { process.destroyForcibly(); }
    }
    public static class CloseCoverProbe {
        public static void main(String[] args) throws Exception {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), interrupted = new CountDownLatch(1);
            try {
                SwingUtilities.invokeAndWait(() -> Probe.panel = new MangaPanel(null, new MangaService() {
                    @Override public BufferedImage loadImageCached(String url) {
                        started.countDown();
                        try { release.await(); } catch (InterruptedException ex) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                        return null;
                    }
                    @Override public List<MangaChapter> fetchChapters(MangaItem item) { return List.of(); }
                    @Override public String fetchDescription(MangaItem item) { return ""; }
                }, false));
                LateProbe.open("A");
                Probe.require(started.await(5, TimeUnit.SECONDS), "Image task did not start");
                SwingUtilities.invokeAndWait(Probe.panel::disposeResources);
                Probe.require(interrupted.await(5, TimeUnit.SECONDS), "Closing the panel did not interrupt cover loading");
            } finally { release.countDown(); if (Probe.panel != null) SwingUtilities.invokeAndWait(Probe.panel::disposeResources); }
        }
    }
    public static class LateProbe {
        static final CountDownLatch started = new CountDownLatch(3), release = new CountDownLatch(1), finished = new CountDownLatch(3);
        static final CountDownLatch interrupted = new CountDownLatch(3);
        static void blockOld(String id) {
            if (!"A".equals(id)) return;
            started.countDown();
            while (true) {
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Old requests were not released");
                    break;
                } catch (InterruptedException ex) {
                    interrupted.countDown();
                }
            }
            finished.countDown();
        }
        public static void main(String[] args) throws Exception {
            try {
                SwingUtilities.invokeAndWait(() -> Probe.panel = new MangaPanel(null, new MangaService() {
                    @Override public List<MangaChapter> fetchChapters(MangaItem item) {
                        blockOld(item.id);
                        return List.of(new MangaChapter(item.id, "1", "Chapter " + item.id, "ru", "MangaLib"));
                    }
                    @Override public String fetchDescription(MangaItem item) { blockOld(item.id); return "Description " + item.id; }
                    @Override public BufferedImage loadImageCached(String id) {
                        blockOld(id);
                        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = image.createGraphics(); g.setColor("A".equals(id) ? Color.RED : Color.GREEN); g.fillRect(0, 0, 4, 4); g.dispose();
                        return image;
                    }
                }, false));
                open("A");
                Probe.require(started.await(5, TimeUnit.SECONDS), "A requests did not start");
                open("B");
                Probe.require(interrupted.await(5, TimeUnit.SECONDS), "Previous detail requests were not cancelled");
                Probe.await(() -> currentDetailsAreB(), "B data did not appear");
                release.countDown();
                Probe.require(finished.await(5, TimeUnit.SECONDS), "A requests did not finish");
                Thread.sleep(300);
                SwingUtilities.invokeAndWait(() -> Probe.require(currentDetailsAreB(), "Late A data replaced B"));
            } finally { release.countDown(); if (Probe.panel != null) SwingUtilities.invokeAndWait(Probe.panel::disposeResources); }
        }
        static void open(String id) throws Exception {
            var method = MangaPanel.class.getDeclaredMethod("showMangaDetails", MangaItem.class);
            method.setAccessible(true);
            SwingUtilities.invokeAndWait(() -> {
                try { method.invoke(Probe.panel, new MangaItem(id, id, "", "", id, "MangaLib", "", "")); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
        }
        static boolean currentDetailsAreB() {
            try {
                var descField = MangaPanel.class.getDeclaredField("detailDesc"); descField.setAccessible(true);
                var coverField = MangaPanel.class.getDeclaredField("detailCover"); coverField.setAccessible(true);
                JTextArea desc = (JTextArea) descField.get(Probe.panel); JLabel cover = (JLabel) coverField.get(Probe.panel);
                if (!(cover.getIcon() instanceof ImageIcon icon)) return false;
                BufferedImage rendered = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = rendered.createGraphics(); icon.paintIcon(cover, graphics, 0, 0); graphics.dispose();
                int pixel = rendered.getRGB(rendered.getWidth()/2, rendered.getHeight()/2);
                return "Description B".equals(desc.getText()) && Probe.chapterText().contains("Chapter B") && (pixel & 0x00ffffff) == 0x0000ff00;
            } catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
        }
    }
    public static class Probe {
        static final CountDownLatch bStarted = new CountDownLatch(1), bRelease = new CountDownLatch(1);
        static MangaPanel panel;
        public static void main(String[] args) throws Exception {
            try {
                SwingUtilities.invokeAndWait(() -> panel = new MangaPanel(null, new MangaService() {
                    @Override public List<MangaChapter> fetchChapters(MangaItem item) {
                        if (item.id.equals("B")) {
                            bStarted.countDown();
                            try { if (!bRelease.await(5, TimeUnit.SECONDS)) throw new AssertionError("B was not released"); }
                            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
                        }
                        return List.of(new MangaChapter(item.id, "1", "Chapter " + item.id, "ru", "MangaLib"));
                    }
                    @Override public String fetchDescription(MangaItem item) { return "Description " + item.id; }
                    @Override public BufferedImage loadImageCached(String url) { return null; }
                }, false));
                open("A");
                await(() -> chapterText().contains("Chapter A"), "A chapters not displayed");
                open("B");
                require(bStarted.await(5, TimeUnit.SECONDS), "B request did not start");
                SwingUtilities.invokeAndWait(() -> {
                    JButton sort = findButton(panel, "Порядок: 1 → N");
                    require(sort != null, "Sort button missing");
                    sort.doClick();
                    require(!chapterText().contains("Chapter A"), "Previous title chapters reappeared while B was loading");
                });
                bRelease.countDown();
                await(() -> chapterText().contains("Chapter B"), "B chapters not displayed");
            } finally { bRelease.countDown(); if (panel != null) SwingUtilities.invokeAndWait(panel::disposeResources); }
        }
        static void open(String id) throws Exception {
            var method = MangaPanel.class.getDeclaredMethod("showMangaDetails", MangaItem.class);
            method.setAccessible(true);
            SwingUtilities.invokeAndWait(() -> {
                try { method.invoke(panel, new MangaItem(id, id, "", "", "", "MangaLib", "", "")); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            });
        }
        static String chapterText() {
            JList<?> list = findList(panel);
            if (list == null) throw new AssertionError("Chapter list missing");
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < list.getModel().getSize(); i++) result.append(list.getModel().getElementAt(i));
            return result.toString();
        }
        static JList<?> findList(Container root) {
            for (Component child : root.getComponents()) {
                if (child instanceof JList<?> list) return list;
                if (child instanceof Container nested) { JList<?> found = findList(nested); if (found != null) return found; }
            } return null;
        }
        static JButton findButton(Container root, String text) {
            for (Component child : root.getComponents()) {
                if (child instanceof JButton button && text.equals(button.getText())) return button;
                if (child instanceof Container nested) { JButton found = findButton(nested, text); if (found != null) return found; }
            } return null;
        }
        static void await(BooleanSupplier check, String error) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                boolean[] result = new boolean[1]; SwingUtilities.invokeAndWait(() -> result[0] = check.getAsBoolean());
                if (result[0]) return; Thread.sleep(20);
            } throw new AssertionError(error);
        }
        static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    }
}
