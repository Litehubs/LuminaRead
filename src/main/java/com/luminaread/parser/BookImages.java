package com.luminaread.parser;

import com.luminaread.util.GeneratedCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** Stores embedded images by content, independently of book names and archive paths. */
final class BookImages {
    private static final Set<String> EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp");

    private BookImages() {}

    static Path save(byte[] bytes, String extension) throws IOException {
        if (!EXTENSIONS.contains(extension)) throw new IllegalArgumentException("Unsupported image extension");
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by Java", ex);
        }
        Path path = Path.of(System.getProperty("user.home"), ".luminaread", "cache", "books", hash + extension);
        if (!Files.isRegularFile(path) || Files.size(path) != bytes.length) {
            GeneratedCache.write(path, output -> output.write(bytes));
        }
        return path;
    }
}
