package com.luminaread.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Reclaims old generated assets before the reader has opened any document in this process. */
public final class CacheCleanup {
    private static final Logger LOG = Logger.getLogger(CacheCleanup.class.getName());
    private static final Pattern BOOK_IMAGE = Pattern.compile("[0-9a-f]{64}\\.(?:jpe?g|png|gif|webp)");
    private static final Pattern COVER = Pattern.compile("img_[0-9a-f]{1,32}\\.png");
    private static final Pattern PDF_DIR = Pattern.compile("pdf_[0-9a-f-]{36}");
    private static final Pattern PDF_PAGE = Pattern.compile("page_[0-9]+\\.(?:png|jpg|tiff)");
    private static final Pattern CBZ_DIR = Pattern.compile("cbz_[0-9]+");
    private static final Pattern CBZ_PAGE = Pattern.compile("[0-9]{8}\\.(?:jpe?g|png|gif|webp|avif)");

    private CacheCleanup() {}

    public static void prune(Path root) {
        try {
            prune(root, Instant.now().minus(Duration.ofDays(30)));
            trimToBudget(root);
            com.luminaread.util.GeneratedCache.invalidate(root);
        } catch (IOException | SecurityException ex) {
            LOG.log(Level.FINE, "Не удалось завершить очистку кэша", ex);
        }
    }

    static void prune(Path root, Instant cutoff) throws IOException {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return;
        try (var dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir, NOFOLLOW_LINKS)) continue;
                String name = dir.getFileName().toString();
                if (name.equals("books")) pruneFiles(dir, BOOK_IMAGE.asMatchPredicate(), cutoff);
                else if (name.equals("ranobe")) pruneFiles(dir, COVER.asMatchPredicate(), cutoff);
                else if (PDF_DIR.matcher(name).matches()) pruneDocument(dir, PDF_PAGE, cutoff);
                else if (CBZ_DIR.matcher(name).matches()) pruneDocument(dir, CBZ_PAGE, cutoff);
            }
        }
    }

    private record Asset(Path file, BasicFileAttributes attributes) {}

    private static void trimToBudget(Path root) throws IOException {
        long total = com.luminaread.util.GeneratedCache.measure(root);
        long limit = com.luminaread.util.GeneratedCache.MAX_BYTES;
        if (total <= limit) return;
        var assets = new java.util.ArrayList<Asset>();
        try (var dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir, NOFOLLOW_LINKS)) continue;
                String name = dir.getFileName().toString();
                Pattern pattern = generatedFiles(name);
                if (pattern == null) continue;
                try (var files = Files.newDirectoryStream(dir, file -> pattern.matcher(file.getFileName().toString()).matches())) {
                    for (Path file : files) {
                        var attrs = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
                        if (attrs.isRegularFile()) assets.add(new Asset(file, attrs));
                    }
                }
            }
        }
        assets.sort(java.util.Comparator.comparing(asset -> asset.attributes().lastModifiedTime()));
        for (Asset asset : assets) {
            if (total <= limit * 3 / 4) break;
            if (Files.deleteIfExists(asset.file())) total -= asset.attributes().size();
        }
    }

    private static Pattern generatedFiles(String name) {
        if (name.equals("books")) return BOOK_IMAGE;
        if (name.equals("ranobe")) return COVER;
        if (PDF_DIR.matcher(name).matches()) return PDF_PAGE;
        if (CBZ_DIR.matcher(name).matches()) return CBZ_PAGE;
        return null;
    }

    private static void pruneDocument(Path dir, Pattern pages, Instant cutoff) throws IOException {
        if (!Files.getLastModifiedTime(dir, NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) return;
        pruneFiles(dir, pages.asMatchPredicate(), cutoff);
        try {
            Files.deleteIfExists(dir);
        } catch (java.nio.file.DirectoryNotEmptyException ignored) {
            // A recent or unrecognized file remains.
        }
    }

    private static void pruneFiles(Path dir, Predicate<String> generated, Instant cutoff) throws IOException {
        try (var files = Files.newDirectoryStream(dir, file -> generated.test(file.getFileName().toString()))) {
            for (Path file : files) {
                try {
                    var attrs = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
                    if (attrs.isRegularFile() && attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                    }
                } catch (java.nio.file.NoSuchFileException ignored) {
                    // Another process already cleaned this asset.
                }
            }
        }
    }
}
