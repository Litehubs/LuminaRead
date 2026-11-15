package com.luminaread;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class StartupNoRebuildTest {
    @Test void startupDoesNotCompileOrReplaceExistingJar(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("src/main/java/Dummy.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Dummy {}", StandardCharsets.UTF_8);
        Path jar = directory.resolve("LuminaRead.jar");
        byte[] original = "existing user jar".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, original);
        Files.setLastModifiedTime(jar, FileTime.fromMillis(1000));
        String classes = Path.of(LuminaReadApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true", "-Duser.home=" + directory.resolve("home"),
                "-cp", classes, "com.luminaread.LuminaReadApp")
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Startup did not finish in headless mode");
            assertArrayEquals(original, Files.readAllBytes(jar), "Startup must not rewrite an existing JAR");
            assertFalse(Files.exists(directory.resolve("build/classes")), "Startup must not compile project sources");
        } finally { child.destroyForcibly(); }
    }
}
