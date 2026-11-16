package com.luminaread.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class EpubArchiveLimitTest {
    @TempDir Path dir;

    @Test void ordinaryEpubStillOpens() throws Exception {
        Path path = dir.resolve("normal.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("chapter.xhtml"));
            zip.write("<html><body><h1>Chapter</h1><p>This is a valid small book for reading.</p></body></html>".getBytes());
            zip.closeEntry();
        }
        assertFalse(new EpubParser().parse(path.toFile()).getChapters().isEmpty());
    }

    @Test void multipleAliasesCannotDuplicateTheSameChapter() throws Exception {
        Path path = dir.resolve("aliases.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("book.opf"));
            zip.write(("<package><manifest><item id='a' href='chapter.xhtml' media-type='application/xhtml+xml'/>"
                    + "<item id='b' href='CHAPTER.xhtml' media-type='application/xhtml+xml'/></manifest>"
                    + "<spine><itemref idref='a'/><itemref idref='b'/></spine></package>").getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("chapter.xhtml"));
            zip.write("<html><body><p>One chapter with enough content to read.</p></body></html>".getBytes());
            zip.closeEntry();
        }
        assertEquals(1, new EpubParser().parse(path.toFile()).getChapters().size());
    }

    @Test void oversizedEntryRejectedInBothZipReaders() throws Exception {
        Path path = dir.resolve("oversized.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("chapter.xhtml"));
            writeMegabytes(zip, 64); zip.write(0); zip.closeEntry();
        }
        assertThrows(IOException.class, () -> new EpubParser().parse(path.toFile()));
        byte[] archive = Files.readAllBytes(path);
        int central = -1;
        for (int i = 0; i < archive.length - 3; i++) {
            if (archive[i] == 'P' && archive[i + 1] == 'K' && archive[i + 2] == 1 && archive[i + 3] == 2) { central = i; break; }
        }
        assertTrue(central > 0);
        Files.write(path, java.util.Arrays.copyOf(archive, central));
        assertThrows(IOException.class, () -> new EpubParser().parse(path.toFile()));
    }

    @Test void totalExpandedSizeAndEntryCountAreBounded() throws Exception {
        Path path = dir.resolve("many.epub");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (int i = 0; i < 3; i++) {
                zip.putNextEntry(new ZipEntry("chapter" + i)); writeMegabytes(zip, 43); zip.closeEntry();
            }
        }
        assertThrows(IOException.class, () -> new EpubParser().parse(path.toFile()));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (int i = 0; i < 10001; i++) { zip.putNextEntry(new ZipEntry("d" + i + "/")); zip.closeEntry(); }
        }
        assertThrows(IOException.class, () -> new EpubParser().parse(path.toFile()));
    }

    private static void writeMegabytes(OutputStream out, int count) throws IOException {
        byte[] chunk = new byte[8192];
        for (int i = 0; i < count * 128; i++) out.write(chunk);
    }
}
