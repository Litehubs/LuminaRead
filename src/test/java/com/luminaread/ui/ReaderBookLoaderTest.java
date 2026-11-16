package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.BookContent;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ReaderBookLoaderTest {
    @Test void newerBookWinsEvenWhenOldLoadIgnoresInterrupt() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), loaded = new CountDownLatch(1);
        AtomicReference<String> shown = new AtomicReference<>();
        AtomicBoolean onEventThread = new AtomicBoolean();
        ReaderBookLoader loader = new ReaderBookLoader(book -> {
            if (book.getTitle().equals("old")) {
                started.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { /* Simulate a non-interruptible reader. */ }
                }
            }
            BookContent result = new BookContent(); result.setTitle(book.getTitle()); return result;
        });
        try {
            SwingUtilities.invokeAndWait(() -> loader.open(book("old"), content -> shown.set(content.getTitle()), ex -> fail(ex)));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> loader.open(book("new"), content -> {
                shown.set(content.getTitle()); onEventThread.set(SwingUtilities.isEventDispatchThread()); loaded.countDown();
            }, ex -> fail(ex)));
            assertTrue(loaded.await(5, TimeUnit.SECONDS));
            release.countDown();
            Thread.sleep(150);
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals("new", shown.get());
            assertTrue(onEventThread.get());
        } finally { release.countDown(); SwingUtilities.invokeAndWait(loader::close); }
    }

    @Test void cancellationSuppressesLateFailureAndCloseRejectsNewWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicBoolean callback = new AtomicBoolean();
        ReaderBookLoader loader = new ReaderBookLoader(book -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException ignored) { }
            finished.countDown();
            throw new IllegalStateException("late failure");
        });
        try {
            SwingUtilities.invokeAndWait(() -> loader.open(book("test"), content -> callback.set(true), error -> callback.set(true)));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(loader::close);
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class,
                    () -> loader.open(book("later"), content -> {}, error -> {})));
            assertFalse(callback.get());
        } finally { release.countDown(); SwingUtilities.invokeAndWait(loader::close); }
    }

    private static Book book(String title) { return new Book(title, "", "dummy", "TXT"); }
}
