package com.luminaread.ui;

import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ReaderDictionaryLookupTest {
    @Test void newestLookupWinsEvenWhenOlderRequestIgnoresInterrupt() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch oldFinished = new CountDownLatch(1), shown = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        AtomicBoolean onEdt = new AtomicBoolean();
        ReaderDictionaryLookup lookup = new ReaderDictionaryLookup(query -> {
            if (query.equals("old")) {
                started.countDown();
                while (release.getCount() != 0) {
                    try { release.await(); } catch (InterruptedException ignored) { /* Simulate a slow provider. */ }
                }
                oldFinished.countDown();
            }
            return query;
        });
        try {
            SwingUtilities.invokeAndWait(() -> lookup.lookup("old", result::set, ex -> fail(ex)));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> lookup.lookup("new", value -> {
                result.set(value); onEdt.set(SwingUtilities.isEventDispatchThread()); shown.countDown();
            }, ex -> fail(ex)));
            assertTrue(shown.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(oldFinished.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals("new", result.get());
            assertTrue(onEdt.get());
        } finally { release.countDown(); SwingUtilities.invokeAndWait(lookup::close); }
    }

    @Test void closeSuppressesLateResultAndRejectsNewRequests() throws Exception {
        CountDownLatch started = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicBoolean callback = new AtomicBoolean();
        ReaderDictionaryLookup lookup = new ReaderDictionaryLookup(query -> {
            started.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException expected) { }
            finished.countDown();
            return "late";
        });
        SwingUtilities.invokeAndWait(() -> lookup.lookup("word", value -> callback.set(true), ex -> callback.set(true)));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(lookup::close);
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(callback.get());
            assertThrows(IllegalStateException.class, () -> lookup.lookup("word", value -> {}, ex -> {}));
        });
    }

    @Test void unexpectedFailureIsReportedOnEventThread() throws Exception {
        CountDownLatch reported = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean onEdt = new AtomicBoolean();
        ReaderDictionaryLookup lookup = new ReaderDictionaryLookup(query -> { throw new IllegalStateException("failure"); });
        try {
            SwingUtilities.invokeAndWait(() -> lookup.lookup("word", value -> fail("unexpected success"), ex -> {
                failure.set(ex); onEdt.set(SwingUtilities.isEventDispatchThread()); reported.countDown();
            }));
            assertTrue(reported.await(5, TimeUnit.SECONDS));
            assertEquals("failure", failure.get().getMessage());
            assertTrue(onEdt.get());
        } finally { SwingUtilities.invokeAndWait(lookup::close); }
    }
}
