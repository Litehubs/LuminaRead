package com.luminaread.service;

import com.luminaread.service.tts.TtsEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TtsLifecycleTest {
    @Test void stopCallbackRunsOutsideMonitorAndCannotBreakCleanup() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        TtsEngine engine = new TtsEngine() {
            @Override public ProcessBuilder buildProcess(String text, int rate) {
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return new ProcessBuilder("/nonexistent/luminaread-test-tts");
            }
            @Override public boolean isAvailable() { return true; }
        };
        var service = new TtsService(engine);
        var monitorHeld = new java.util.concurrent.atomic.AtomicBoolean(true);
        var calls = new AtomicInteger();
        try {
            service.speakText("text", 175, 1, () -> {
                monitorHeld.set(Thread.holdsLock(service));
                calls.incrementAndGet();
                throw new IllegalStateException("Broken UI listener");
            });
            assertDoesNotThrow(service::stop);
            assertFalse(monitorHeld.get(), "Callbacks may wait for the UI, which also calls this service");
            assertFalse(service.isSpeaking());
            service.stop();
            assertEquals(1, calls.get());
        } finally { release.countDown(); service.stop(); }
    }

    @Test
    void unixSpeechTreatsOptionLikeBookTextAsData() {
        String text = "--phonout=chapter.txt";
        assertEquals(java.util.List.of("say", "-r", "175", "--", text),
                TtsService.buildMacProcess(text, 175).command());
        assertEquals(java.util.List.of("espeak", "-s", "175", "--", text),
                TtsService.buildLinuxProcess(text, 175, false).command());
        assertEquals(java.util.List.of("spd-say", "-r", "0", "--", text),
                TtsService.buildLinuxProcess(text, 175, true).command());
    }

    @Test void missingSpeechEngineStopsPlaybackAndNotifiesCaller() throws Exception {
        TtsEngine engine = new TtsEngine() {
            @Override public ProcessBuilder buildProcess(String text, int rate) { return new ProcessBuilder("/nonexistent/luminaread-test-tts"); }
            @Override public boolean isAvailable() { return true; }
        };
        TtsService service = new TtsService(engine);
        var finished = new java.util.concurrent.CountDownLatch(1);
        try {
            service.speakText("text", 150, 0, finished::countDown);
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertFalse(service.isSpeaking());
        } finally { service.stop(); }
    }

    @Test void windowsKeepsBookTextOutOfThePowerShellScript() {
        String text = "‘); Write-Output injected; $(1+1)";
        ProcessBuilder expected = TtsService.buildWindowsProcess("ordinary text", 175);
        ProcessBuilder actual = TtsService.buildWindowsProcess(text, 175);
        assertEquals(expected.command(), actual.command());
        assertEquals(text, actual.environment().get("LUMINAREAD_TTS_TEXT"));
    }

    @Test @EnabledOnOs({OS.MAC, OS.LINUX})
    void stoppedPlaybackCannotClearNewPlaybackOrNotifyItsCallback() throws Exception {
        TtsEngine engine = new TtsEngine() {
            @Override public ProcessBuilder buildProcess(String text, int rate) { return new ProcessBuilder("/bin/sh", "-c", "exec sleep 10"); }
            @Override public boolean isAvailable() { return true; }
        };
        TtsService service = new TtsService(engine);
        AtomicInteger oldCallback = new AtomicInteger(), newCallback = new AtomicInteger();
        try {
            service.speakText("old", 150, 0, oldCallback::incrementAndGet);
            waitForProcess(service);
            service.speakText("new", 150, 0, newCallback::incrementAndGet);
            waitForProcess(service);
            Thread.sleep(250);
            assertTrue(service.isSpeaking(), "Old completion must not stop new playback");
            assertEquals(1, oldCallback.get());
            assertEquals(0, newCallback.get());
            service.stop();
            assertEquals(1, newCallback.get());
            Thread.sleep(100);
            assertEquals(1, newCallback.get(), "Callback must be invoked only once");
        } finally { service.stop(); }
    }

    private static void waitForProcess(TtsService service) throws Exception {
        var field = TtsService.class.getDeclaredField("activeProcess"); field.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            synchronized (service) { if (field.get(service) != null) return; }
            Thread.sleep(10);
        }
        fail("Speech process did not start");
    }
}
