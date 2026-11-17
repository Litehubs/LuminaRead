package com.luminaread.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProfileLockTest {
    @Test void excludesASecondProcessUntilTheProfileCloses(@TempDir Path profile) throws Exception {
        try (ProfileLock lock = ProfileLock.acquire(profile)) {
            assertThrows(IOException.class, () -> ProfileLock.acquire(profile));
            assertEquals(2, tryFromAnotherProcess(profile));
        }
        assertEquals(0, tryFromAnotherProcess(profile));
    }

    private static int tryFromAnotherProcess(Path profile) throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), Probe.class.getName(), profile.toString()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Lock probe did not exit");
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }

    public static final class Probe {
        public static void main(String[] args) {
            try (ProfileLock lock = ProfileLock.acquire(Path.of(args[0]))) {
                // The other process may acquire the profile only after the first releases it.
            } catch (IOException ex) {
                System.exit(2);
            }
        }
    }
}
