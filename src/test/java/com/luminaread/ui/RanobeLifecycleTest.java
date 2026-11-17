package com.luminaread.ui;

import com.luminaread.service.RanobeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class RanobeLifecycleTest {
    @Test void failedLoadsAreVisibleAndDoNotSaveProgress(@TempDir Path home) throws Exception {
        runProbe(home, FailureProbe.class);
    }

    private void runProbe(Path home, Class<?> probe) throws Exception {
        Path log = home.resolve("probe.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Duser.home=" + home,
                "-cp", System.getProperty("java.class.path"), probe.getName())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), () -> "Ranobe probe timed out: " + readLog(log));
            assertEquals(0, process.exitValue(), () -> readLog(log));
        } finally {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static String readLog(Path log) {
        try {
            return Files.readString(log, StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            return ex.toString();
        }
    }

    public static class FailureProbe {
        public static void main(String[] args) throws Exception {
            RanobePanel[] panel = new RanobePanel[1];
            CountDownLatch releaseChapter = new CountDownLatch(1);
            try {
                SwingUtilities.invokeAndWait(() -> panel[0] = new RanobePanel(null, new RanobeService() {
                    @Override public List<RanobeChapter> fetchChapters(String slug) { throw new IllegalStateException("offline"); }
                    @Override public RanobeChapterContent fetchChapterContent(com.luminaread.model.ranobe.RanobeChapter chapter, String slug) throws Exception {
                        if ("1".equals(chapter.number)) throw new java.io.IOException("offline");
                        if (!releaseChapter.await(5, TimeUnit.SECONDS)) throw new AssertionError("Chapter was not released");
                        return new RanobeChapterContent("new", "1", "2", "<p>new chapter text</p>",
                                "new chapter text", List.of());
                    }
                    @Override public void saveRanobeState(
                            java.util.Map<String, ? extends com.luminaread.model.ranobe.RanobeItem> favorites,
                            java.util.Map<String, ? extends com.luminaread.model.ranobe.RanobeItem> history,
                            java.util.Map<String, String> progress, java.util.Map<String, String> preferences) {
                        throw new java.io.UncheckedIOException(new java.io.IOException("read-only disk"));
                    }
                    @Override public RanobeItem fetchRanobeDetails(com.luminaread.model.ranobe.RanobeItem item) { return new RanobeItem(item); }
                }, false));
                var details = RanobePanel.class.getDeclaredMethod("showRanobeDetails", com.luminaread.model.ranobe.RanobeItem.class); details.setAccessible(true);
                var item = new com.luminaread.model.ranobe.RanobeItem("id", "title", "", "", "", "", "", "", List.of(), List.of());
                SwingUtilities.invokeAndWait(() -> { try { details.invoke(panel[0], item); } catch (Exception e) { throw new RuntimeException(e); } });
                var status = RanobePanel.class.getDeclaredField("chapterStatus"); status.setAccessible(true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean found = false;
                while (System.nanoTime() < deadline && !found) {
                    boolean[] value = new boolean[1];
                    SwingUtilities.invokeAndWait(() -> { try { value[0] = ((javax.swing.JLabel) status.get(panel[0])).getText().contains("Не удалось загрузить главы"); } catch (Exception e) { throw new RuntimeException(e); } });
                    found = value[0]; if (!found) Thread.sleep(20);
                }
                if (!found) throw new AssertionError("Chapter error not visible");
                var list = RanobePanel.class.getDeclaredField("chapters"); list.setAccessible(true);
                var open = RanobePanel.class.getDeclaredMethod("openChapterAtIndex", int.class); open.setAccessible(true);
                var chapter = new com.luminaread.model.ranobe.RanobeChapter("id|1|1|", "1", "1", "title", "", "");
                SwingUtilities.invokeAndWait(() -> { try { ((RanobeChapterList) list.get(panel[0])).setChapters(List.of(chapter)); open.invoke(panel[0], 0); } catch (Exception e) { throw new RuntimeException(e); } });
                var editor = RanobePanel.class.getDeclaredField("textEditorPane"); editor.setAccessible(true);
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                found = false;
                while (System.nanoTime() < deadline && !found) {
                    boolean[] value = new boolean[1];
                    SwingUtilities.invokeAndWait(() -> { try {
                        var doc = ((javax.swing.JEditorPane) editor.get(panel[0])).getDocument();
                        value[0] = doc.getText(0, doc.getLength()).contains("Не удалось загрузить главу");
                    } catch (Exception e) { throw new RuntimeException(e); } });
                    found = value[0]; if (!found) Thread.sleep(20);
                }
                if (!found) throw new AssertionError("Reading error not visible");
                var progress = RanobePanel.class.getDeclaredField("lastReadChapterMap"); progress.setAccessible(true);
                SwingUtilities.invokeAndWait(() -> { try { if (!((java.util.Map<?, ?>) progress.get(panel[0])).isEmpty()) throw new AssertionError("Failed chapter saved progress"); } catch (ReflectiveOperationException e) { throw new RuntimeException(e); } });
                var currentContent = RanobePanel.class.getDeclaredField("currentContent");
                currentContent.setAccessible(true);
                var render = RanobePanel.class.getDeclaredMethod("renderChapterText");
                render.setAccessible(true);
                var nextChapter = new com.luminaread.model.ranobe.RanobeChapter("id|1|2|", "1", "2", "new", "", "");
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        currentContent.set(panel[0], new com.luminaread.model.ranobe.RanobeChapterContent("old", "1", "1",
                                "<p>old chapter text</p>", "old chapter text", List.of()));
                        ((RanobeChapterList) list.get(panel[0])).setChapters(List.of(nextChapter));
                        open.invoke(panel[0], 0);
                        render.invoke(panel[0]);
                        if (!editorText(panel[0], editor).contains("Загрузка главы 2")) {
                            throw new AssertionError("Layout update displayed the old chapter during a pending load");
                        }
                    } catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
                });
                releaseChapter.countDown();
                MangaDetailsRaceTest.Probe.await(() -> editorText(panel[0], editor).contains("new chapter text"),
                        "Saving progress failed and prevented the loaded chapter from rendering");
            } finally {
                releaseChapter.countDown();
                if (panel[0] != null) SwingUtilities.invokeAndWait(panel[0]::disposeResources);
            }
        }

        private static String editorText(RanobePanel panel, java.lang.reflect.Field editor) {
            try {
                var document = ((javax.swing.JEditorPane) editor.get(panel)).getDocument();
                return document.getText(0, document.getLength());
            } catch (IllegalAccessException | javax.swing.text.BadLocationException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
    @Test void closingPanelCancelsSearch(@TempDir Path home) throws Exception {
        runProbe(home, Probe.class);
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), interrupted = new CountDownLatch(1);
            RanobePanel[] panel = new RanobePanel[1];
            try {
                SwingUtilities.invokeAndWait(() -> panel[0] = new RanobePanel(null, new RanobeService() {
                    @Override public List<RanobeItem> searchRanobe(String query) {
                        started.countDown();
                        try { release.await(); } catch (InterruptedException ex) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                        return List.of();
                    }
                }, false));
                var search = RanobePanel.class.getDeclaredMethod("performSearch", String.class);
                search.setAccessible(true);
                SwingUtilities.invokeAndWait(() -> { try { search.invoke(panel[0], "waiting"); } catch (Exception ex) { throw new RuntimeException(ex); } });
                if (!started.await(5, TimeUnit.SECONDS)) throw new AssertionError("Search did not start");
                SwingUtilities.invokeAndWait(panel[0]::disposeResources);
                if (!interrupted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Closing did not cancel search");
            } finally { release.countDown(); if (panel[0] != null) SwingUtilities.invokeAndWait(panel[0]::disposeResources); }
        }
    }
}
