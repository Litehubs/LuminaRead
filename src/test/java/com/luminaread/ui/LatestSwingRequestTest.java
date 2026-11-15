package com.luminaread.ui;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LatestSwingRequestTest {
    @Test void replacementInterruptsOldRequestAndOnlyPublishesCurrentResult() throws Exception {
        var request = new LatestSwingRequest<String>();
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var published = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var result = new AtomicReference<String>();
        try {
            SwingUtilities.invokeAndWait(() -> request.start(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    interrupted.countDown();
                    release.await();
                }
                throw new IOException("stale failure");
            }, value -> calls.incrementAndGet(), error -> calls.incrementAndGet()));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            SwingUtilities.invokeAndWait(() -> request.start(() -> "current", value -> {
                calls.incrementAndGet();
                result.set(value);
                published.countDown();
            }, error -> calls.incrementAndGet()));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(published.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals("current", result.get());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(request::cancel);
        }
    }

    @Test void leavingSearchSuppressesCallbacksButActiveFailureKeepsItsCause() throws Exception {
        var request = new LatestSwingRequest<String>();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelledCallbacks = new AtomicInteger();
        var reported = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        IOException expected = new IOException("offline");

        try {
            SwingUtilities.invokeAndWait(() -> request.start(() -> {
                started.countDown();
                release.await();
                return "stale";
            }, value -> cancelledCallbacks.incrementAndGet(), error -> cancelledCallbacks.incrementAndGet()));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(request::cancel);
            release.countDown();

            SwingUtilities.invokeAndWait(() -> request.start(() -> {
                throw expected;
            }, value -> reported.countDown(), error -> {
                failure.set(error);
                reported.countDown();
            }));
            assertTrue(reported.await(5, TimeUnit.SECONDS));
            assertSame(expected, failure.get());
            assertEquals(0, cancelledCallbacks.get());
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(request::cancel);
        }
    }
}
