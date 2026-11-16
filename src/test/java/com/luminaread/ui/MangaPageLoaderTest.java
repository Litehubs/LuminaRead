package com.luminaread.ui;

import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MangaPageLoaderTest {
    @Test void obsoleteResultIsDiscardedAndCurrentResultRunsOnEventThread() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), completed = new CountDownLatch(1);
        AtomicInteger generation = new AtomicInteger(1);
        AtomicBoolean staleDelivered = new AtomicBoolean(), onEventThread = new AtomicBoolean();
        try {
            MangaPageLoader loader = new MangaPageLoader(executor, url -> {
                if (url.equals("old")) {
                    started.countDown();
                    try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return null; }
                }
                return new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            }, token -> token == generation.get());
            loader.load("old", 1, image -> staleDelivered.set(true));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            generation.incrementAndGet();
            loader.load("new", 2, image -> { onEventThread.set(SwingUtilities.isEventDispatchThread()); completed.countDown(); });
            release.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertFalse(staleDelivered.get());
            assertTrue(onEventThread.get());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void failureIsReportedOnEventThread() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean reported = new AtomicBoolean();
        try {
            MangaPageLoader loader = new MangaPageLoader(executor, url -> { throw new IllegalStateException("test"); }, token -> true);
            loader.load("broken", 1, image -> { reported.set(image == null && SwingUtilities.isEventDispatchThread()); completed.countDown(); });
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(reported.get());
        } finally { executor.shutdownNow(); }
    }

    @Test void resultQueuedBeforeNavigationIsStillDiscarded() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch eventThreadBlocked = new CountDownLatch(1), releaseEventThread = new CountDownLatch(1);
        AtomicInteger generation = new AtomicInteger(1);
        AtomicBoolean delivered = new AtomicBoolean();
        try {
            SwingUtilities.invokeLater(() -> {
                eventThreadBlocked.countDown();
                try { releaseEventThread.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            });
            assertTrue(eventThreadBlocked.await(5, TimeUnit.SECONDS));
            MangaPageLoader loader = new MangaPageLoader(executor,
                    url -> new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), token -> token == generation.get());
            loader.load("page", 1, image -> delivered.set(true));
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
            generation.incrementAndGet();
            releaseEventThread.countDown();
            SwingUtilities.invokeAndWait(() -> {});
            assertFalse(delivered.get());
        } finally { releaseEventThread.countDown(); executor.shutdownNow(); }
    }
}
