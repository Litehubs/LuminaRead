package com.luminaread;

import com.luminaread.config.ReaderDisplayConfig;
import com.luminaread.config.SettingsJsonRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class StartupNoRebuildTest {
    @Test void startupPreservesExistingJarAndReaderPreferences(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/main/java/Dummy.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Dummy {}", StandardCharsets.UTF_8);
        Path jar = directory.resolve("LuminaRead.jar");
        byte[] original = "existing user jar".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, original);
        Files.setLastModifiedTime(jar, FileTime.fromMillis(1000));
        Path home = directory.resolve("home");
        SettingsJsonRepository settings = new SettingsJsonRepository(home.resolve(".luminaread/settings.json"));
        ReaderDisplayConfig display = new ReaderDisplayConfig();
        display.setTextAlignment(ReaderDisplayConfig.TextAlignment.JUSTIFY);
        settings.save(display, null);

        String classes = Path.of(LuminaReadApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + home,
                "-cp", classes, "com.luminaread.LuminaReadApp")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Startup did not finish in headless mode");
            assertEquals(0, child.exitValue());
            ReaderDisplayConfig reloaded = new ReaderDisplayConfig();
            assertTrue(settings.load(reloaded, null));
            assertEquals(ReaderDisplayConfig.TextAlignment.JUSTIFY, reloaded.getTextAlignment(),
                    "Startup must preserve the chosen alignment");
            assertArrayEquals(original, Files.readAllBytes(jar), "Startup must not rewrite an existing JAR");
            assertFalse(Files.exists(directory.resolve("build/classes")), "Startup must not compile project sources");
        } finally { child.destroyForcibly(); }
    }
}
