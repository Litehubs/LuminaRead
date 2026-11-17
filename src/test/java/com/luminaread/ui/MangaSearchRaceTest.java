package com.luminaread.ui;

import com.luminaread.model.manga.MangaItem;
import com.luminaread.model.manga.MangaSearchResult;

import com.luminaread.service.MangaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

final class MangaSearchRaceTest {
    @Test void oldResultsCannotReplaceNewSearchOrAnotherSection(@TempDir Path home) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"),
                Probe.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        } finally { process.destroyForcibly(); }
    }
    @Test void closingPanelCancelsRunningSearch(@TempDir Path home) throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"),
                CloseProbe.class.getName()).redirectErrorStream(true).start();
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
        finally { process.destroyForcibly(); }
    }
    public static class CloseProbe {
        public static void main(String[] args) throws Exception {
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), interrupted = new CountDownLatch(1);
            try {
                SwingUtilities.invokeAndWait(() -> Probe.panel = new MangaPanel(null, new MangaService() {
                    @Override public MangaSearchResult searchMangaDetailed(String query, String source) {
                        started.countDown();
                        try { release.await(); } catch (InterruptedException ex) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                        return new MangaSearchResult(List.of(), List.of());
                    }
                }, false));
                Probe.search("waiting");
                Probe.require(started.await(5, TimeUnit.SECONDS), "Search did not start");
                SwingUtilities.invokeAndWait(Probe.panel::disposeResources);
                Probe.require(interrupted.await(2, TimeUnit.SECONDS), "Closing the panel did not cancel its search");
                var pages = MangaPanel.class.getDeclaredField("currentPages"); pages.setAccessible(true);
                var render = MangaPanel.class.getDeclaredMethod("renderReaderPages"); render.setAccessible(true);
                SwingUtilities.invokeAndWait(() -> {
                    try { pages.set(Probe.panel, List.of("page")); render.invoke(Probe.panel); }
                    catch (Exception ex) { throw new RuntimeException("Rendering after close must be harmless", ex); }
                });
            } finally { release.countDown(); if (Probe.panel != null) SwingUtilities.invokeAndWait(Probe.panel::disposeResources); }
        }
    }
    public static class Probe {
        static CountDownLatch oldStarted = new CountDownLatch(1), oldRelease = new CountDownLatch(1), oldReturned = new CountDownLatch(1);
        static CountDownLatch oldInterrupted = new CountDownLatch(1);
        static MangaPanel panel;
        public static void main(String[] args) throws Exception {
            try {
                SwingUtilities.invokeAndWait(() -> panel = new MangaPanel(null, new MangaService() {
                    @Override public MangaSearchResult searchMangaDetailed(String query, String source) {
                        if (query.equals("old")) {
                            oldStarted.countDown();
                            boolean released = false;
                            while (!released) {
                                try {
                                    released = oldRelease.await(5, TimeUnit.SECONDS);
                                    if (!released) throw new AssertionError("Old search was not released");
                                } catch (InterruptedException ex) {
                                    oldInterrupted.countDown(); // Simulate a source that still returns after cancellation.
                                }
                            }
                            oldReturned.countDown();
                        }
                        return new MangaSearchResult(List.of(new MangaItem(query, query, "", "", "", "test", "", "")),
                                query.equals("new") ? List.of("MangaLib") : List.of());
                    }
                }, false));
                search("old");
                require(oldStarted.await(5, TimeUnit.SECONDS), "Old search did not start");
                search("new");
                require(oldInterrupted.await(5, TimeUnit.SECONDS), "New search did not cancel the previous worker");
                await(() -> labelExists(panel, "new"), "New result was not displayed");
                await(() -> labelExists(panel, "Найдено манги/манхвы: 1 · Не удалось загрузить: MangaLib"), "Source failure was not displayed");
                oldRelease.countDown();
                require(oldReturned.await(5, TimeUnit.SECONDS), "Old search did not finish");
                Thread.sleep(300);
                SwingUtilities.invokeAndWait(() -> {
                    require(labelExists(panel, "new"), "Old response replaced the new result");
                    require(!labelExists(panel, "old"), "Old result appeared");
                });
                oldStarted = new CountDownLatch(1); oldRelease = new CountDownLatch(1); oldReturned = new CountDownLatch(1);
                oldInterrupted = new CountDownLatch(1);
                search("old");
                require(oldStarted.await(5, TimeUnit.SECONDS), "Second old search did not start");
                SwingUtilities.invokeAndWait(() -> findCategory(panel).setSelectedIndex(1));
                require(oldInterrupted.await(5, TimeUnit.SECONDS), "Opening favorites did not cancel the previous worker");
                oldRelease.countDown();
                require(oldReturned.await(5, TimeUnit.SECONDS), "Second old search did not finish");
                Thread.sleep(300);
                SwingUtilities.invokeAndWait(() -> {
                    require(labelExists(panel, "Избранная манга (0)"), "Search replaced the selected section");
                    require(!labelExists(panel, "old"), "Old result appeared in favorites");
                });
            } finally {
                oldRelease.countDown();
                if (panel != null) SwingUtilities.invokeAndWait(panel::disposeResources);
            }
        }
        static void search(String query) throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                JTextField field = findSearch(panel);
                field.setText(query);
                field.postActionEvent();
            });
        }
        static JTextField findSearch(Container root) {
            for (Component child : root.getComponents()) {
                if (child instanceof JTextField field && field.getToolTipText() != null && field.getToolTipText().startsWith("Поиск манги")) return field;
                if (child instanceof Container nested) { JTextField found = findSearch(nested); if (found != null) return found; }
            }
            return null;
        }
        static JComboBox<?> findCategory(Container root) {
            for (Component child : root.getComponents()) {
                if (child instanceof JComboBox<?> combo && combo.getItemCount() > 0
                        && String.valueOf(combo.getItemAt(0)).contains("Популярное сейчас")) return combo;
                if (child instanceof Container nested) { JComboBox<?> found = findCategory(nested); if (found != null) return found; }
            }
            return null;
        }
        static boolean labelExists(Container root, String text) {
            for (Component child : root.getComponents()) {
                if (child instanceof JLabel label && text.equals(label.getText())) return true;
                if (child instanceof Container nested && labelExists(nested, text)) return true;
            }
            return false;
        }
        static void await(BooleanSupplier check, String error) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                boolean[] result = new boolean[1]; SwingUtilities.invokeAndWait(() -> result[0] = check.getAsBoolean());
                if (result[0]) return;
                Thread.sleep(20);
            }
            throw new AssertionError(error);
        }
        static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    }
}
