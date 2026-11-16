package com.luminaread.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class CbzArchiveSafetyTest {
    @TempDir Path home;

    @Test void oversizedIgnoredEntryMustBeRejectedWithoutLeavingPartialCache() throws Exception {
        Path path = home.resolve("comic.cbz");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("01.png")); zip.write(new byte[]{1, 2, 3}); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("ignored.bin"));
            byte[] chunk = new byte[8192];
            for (int i = 0; i < 4096; i++) zip.write(chunk);
            zip.write(0); zip.closeEntry();
        }
        String old = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            assertThrows(IOException.class, () -> new CbzParser().parse(path.toFile()));
            Path cache = home.resolve(".luminaread/cache");
            if (Files.exists(cache)) try (var entries = Files.list(cache)) { assertEquals(0, entries.count()); }
        } finally { if (old == null) System.clearProperty("user.home"); else System.setProperty("user.home", old); }
    }

    @Test void validPagesAreSortedAndFlattenedNamesDoNotCollide() throws Exception {
        Path path = home.resolve("normal.cbz");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("same_a.png")); zip.write(new byte[]{2}); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("same/a.png")); zip.write(new byte[]{1}); zip.closeEntry();
        }
        String old = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            var result = new CbzParser().parse(path.toFile());
            assertEquals(2, result.getChapters().size());
            assertArrayEquals(new byte[]{1}, result.getCoverImage());
            try (var directories = Files.list(home.resolve(".luminaread/cache"))) {
                Path folder = directories.findFirst().orElseThrow();
                try (var files = Files.list(folder)) { assertEquals(2, files.count()); }
            }
        } finally { if (old == null) System.clearProperty("user.home"); else System.setProperty("user.home", old); }
    }

    @Test void oversizedImageAndTooManyEntriesAreRejected() throws Exception {
        Path path = home.resolve("large.cbz");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("page.png"));
            byte[] chunk = new byte[8192];
            for (int i = 0; i < 4096; i++) zip.write(chunk);
            zip.write(0); zip.closeEntry();
        }
        String old = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            assertThrows(IOException.class, () -> new CbzParser().parse(path.toFile()));
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
                for (int i = 0; i < 10001; i++) { zip.putNextEntry(new ZipEntry("d" + i + "/")); zip.closeEntry(); }
            }
            assertThrows(IOException.class, () -> new CbzParser().parse(path.toFile()));
        } finally { if (old == null) System.clearProperty("user.home"); else System.setProperty("user.home", old); }
    }

    @Test void totalExpansionIsBoundedAcrossEntries() throws Exception {
        Path path = home.resolve("many.cbz");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            byte[] chunk = new byte[8192];
            for (int entry = 0; entry < 9; entry++) {
                zip.putNextEntry(new ZipEntry("data" + entry));
                for (int i = 0; i < 4096; i++) zip.write(chunk);
                zip.closeEntry();
            }
        }
        String old = System.getProperty("user.home");
        try {
            System.setProperty("user.home", home.toString());
            assertThrows(IOException.class, () -> new CbzParser().parse(path.toFile()));
        } finally { if (old == null) System.clearProperty("user.home"); else System.setProperty("user.home", old); }
    }
}
