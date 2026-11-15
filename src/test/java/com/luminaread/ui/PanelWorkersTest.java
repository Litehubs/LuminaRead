package com.luminaread.ui;

import org.junit.jupiter.api.Test;
import javax.swing.SwingWorker;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class PanelWorkersTest {
    @Test void closeInterruptsActiveWorkAndRejectsNewWork() throws Exception {
        PanelWorkers workers = new PanelWorkers(1);
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            protected Void doInBackground() {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException ex) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return null;
            }
        };
        assertTrue(workers.start(worker));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            workers.close();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(worker.isCancelled());
            assertFalse(workers.start(new SwingWorker<Void, Void>() { protected Void doInBackground() { return null; } }));
            workers.close();
        } finally { workers.close(); }
    }
    @Test void closeInterruptsImagesAndDropsTheirQueue() throws Exception {
        var workers = new PanelWorkers(1);
        var active = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var queuedRan = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            workers.execute(() -> {
                active.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(active.await(5, TimeUnit.SECONDS));
            workers.execute(() -> queuedRan.set(true));
            workers.close();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertFalse(queuedRan.get());
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> workers.execute(() -> {}));
        } finally {
            release.countDown();
            workers.close();
        }
    }

}
