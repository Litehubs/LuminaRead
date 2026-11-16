package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.BookContent;
import com.luminaread.model.Chapter;
import com.luminaread.service.ITtsService;
import com.luminaread.service.LibraryStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ReaderPanelLifecycleTest {
    @Test void changingBooksAndLeavingReaderRejectsLateResults(@TempDir Path home) throws Exception {
        String main = Path.of(ReaderPanel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String tests = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + home, "-cp", main + File.pathSeparator + tests,
                Probe.class.getName()).redirectErrorStream(true).start();
        try { assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
        finally { process.destroyForcibly(); }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            CountDownLatch oldStarted = new CountDownLatch(1), oldRelease = new CountDownLatch(1);
            CountDownLatch leaveStarted = new CountDownLatch(1), leaveRelease = new CountDownLatch(1);
            ReaderPanel[] panel = new ReaderPanel[1];
            try {
                LibraryStorageService storage = new LibraryStorageService() {
                    @Override public BookContent loadBookContent(Book book) {
                        CountDownLatch started = book.getTitle().equals("old") ? oldStarted : leaveStarted;
                        CountDownLatch release = book.getTitle().equals("old") ? oldRelease : leaveRelease;
                        if (!book.getTitle().equals("new")) {
                            started.countDown();
                            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) {}
                        }
                        BookContent content = new BookContent(); content.setTitle(book.getTitle());
                        content.addChapter(new Chapter(0, "chapter", "<p>" + book.getTitle() + " text</p>", book.getTitle() + " text"));
                        return content;
                    }
                    @Override public synchronized void saveState() {
                        throw new java.io.UncheckedIOException(new java.io.IOException("read-only disk"));
                    }
                };
                ITtsService tts = new ITtsService() {
                    public void speakText(String text, int rate, int sleep, Runnable stopped) {}
                    public void stop() {}
                    public boolean isSpeaking() { return false; }
                };
                SwingUtilities.invokeAndWait(() -> panel[0] = new ReaderPanel(storage, tts, text -> "", () -> {}, theme -> {}));
                SwingUtilities.invokeAndWait(() -> panel[0].openBook(book("old")));
                if (!oldStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Old book did not start");
                SwingUtilities.invokeAndWait(() -> panel[0].openBook(book("new")));
                awaitTitle(panel[0], "new");
                SwingUtilities.invokeAndWait(() -> {
                    if (!MangaSearchRaceTest.Probe.labelExists(panel[0], "Прогресс не сохранён")) {
                        throw new AssertionError("Reading progress write failure was not reported");
                    }
                });
                oldRelease.countDown();
                Thread.sleep(150);
                SwingUtilities.invokeAndWait(() -> { if (!"new".equals(title(panel[0]))) throw new AssertionError("Old book replaced new book"); });
                SwingUtilities.invokeAndWait(() -> panel[0].openBook(book("leave")));
                if (!leaveStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Book load did not start");
                SwingUtilities.invokeAndWait(panel[0]::stopActiveFeatures);
                leaveRelease.countDown();
                Thread.sleep(150);
                SwingUtilities.invokeAndWait(() -> { if (title(panel[0]) != null) throw new AssertionError("Book appeared after leaving reader"); });
            } finally {
                oldRelease.countDown(); leaveRelease.countDown();
                if (panel[0] != null) SwingUtilities.invokeAndWait(panel[0]::disposeResources);
            }
        }
        private static Book book(String name) { return new Book(name, "", "dummy", "TXT"); }
        private static void awaitTitle(ReaderPanel panel, String title) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                boolean[] found = {false}; SwingUtilities.invokeAndWait(() -> found[0] = title.equals(title(panel)));
                if (found[0]) return;
                Thread.sleep(20);
            }
            throw new AssertionError("New book did not load");
        }
        private static String title(ReaderPanel panel) {
            try {
                var field = ReaderPanel.class.getDeclaredField("currentContent"); field.setAccessible(true);
                BookContent content = (BookContent) field.get(panel);
                return content == null ? null : content.getTitle();
            } catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
        }
    }
}
