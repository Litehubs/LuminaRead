package com.luminaread;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class LauncherSelectionTest {
    @EnabledOnOs({OS.MAC, OS.LINUX})
    @Test void unixLaunchersPreferBuildAndFallBackToBundledJar(@TempDir Path temp) throws Exception {
        Path fakeBin = Files.createDirectory(temp.resolve("bin"));
        Path capture = temp.resolve("arguments.txt");
        Path java = fakeBin.resolve("java");
        Files.writeString(java, "#!/bin/sh\nif [ \"$1\" = -version ]; then echo 'openjdk version \"17\"' >&2; exit 0; fi\nprintf '%s\\n' \"$@\" > \"$LUMINA_TEST_CAPTURE\"\n");
        assertTrue(java.toFile().setExecutable(true));
        for (String name : new String[]{"Start_LuminaRead_Mac.command", "Start_LuminaRead_Linux.sh"}) {
            Path work = Files.createDirectory(temp.resolve(name));
            Path script = Files.copy(Path.of(name), work.resolve(name));
            Files.writeString(work.resolve("LuminaRead.jar"), "bundled");
            Files.createDirectory(work.resolve("target"));
            Files.writeString(work.resolve("target/LuminaRead.jar"), "built");
            run(script, fakeBin, capture);
            assertTrue(Files.readString(capture).contains("target/LuminaRead.jar"));
            Files.delete(work.resolve("target/LuminaRead.jar"));
            run(script, fakeBin, capture);
            assertTrue(Files.readString(capture).contains("LuminaRead.jar"));
            assertFalse(Files.readString(capture).contains("target/LuminaRead.jar"));
            assertTrue(Files.readString(capture).contains("book with spaces.fb2"));
        }
    }

    private static void run(Path script, Path fakeBin, Path capture) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bash", script.toString(), "book with spaces.fb2").redirectErrorStream(true);
        builder.environment().put("PATH", fakeBin + File.pathSeparator + System.getenv("PATH"));
        builder.environment().put("LUMINA_TEST_CAPTURE", capture.toString());
        Process process = builder.start();
        try { assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
        finally { process.destroyForcibly(); }
    }
}
