package com.luminaread.parser;

import com.luminaread.model.BookContent;
import com.luminaread.model.Chapter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class CbzParser implements BookParser {
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 10000;

    static boolean isPageImage(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".avif");
    }

    @Override
    public boolean supports(File file, String formatHint) {
        if (formatHint != null && "CBZ".equalsIgnoreCase(formatHint)) {
            return true;
        }
        if (file != null && file.getName().toLowerCase(Locale.ROOT).endsWith(".cbz")) {
            return true;
        }
        return false;
    }

    @Override
    public String getSupportedFormat() {
        return "CBZ";
    }

    @Override
    public BookContent parse(File file) throws Exception {
        BookContent content = new BookContent();
        String baseTitle = file.getName();
        int dot = baseTitle.lastIndexOf('.');
        if (dot > 0) baseTitle = baseTitle.substring(0, dot);

        content.setTitle(baseTitle);
        content.setAuthor("Комикс / Иллюстрированное издание");

        if (Files.size(file.toPath()) > MAX_ARCHIVE_BYTES) {
            throw new IOException("Размер файла CBZ превышает допустимый предел");
        }
        Path cacheRoot = Path.of(System.getProperty("user.home"), ".luminaread", "cache");
        Files.createDirectories(cacheRoot);
        Path cacheDir = Files.createTempDirectory(cacheRoot, "cbz_");
        cacheDir.toFile().deleteOnExit();

        record PageImg(String name, Path savedPath) {}
        List<PageImg> pages = new ArrayList<>();
        byte[] cover = null;
        String coverName = null;
        long total = 0;
        int entries = 0;
        boolean completed = false;

        try {
          try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("Слишком много файлов в CBZ");
                String lower = entry.getName().toLowerCase(Locale.ROOT);
                boolean image = !entry.isDirectory() && isPageImage(lower);
                ByteArrayOutputStream bos = image ? new ByteArrayOutputStream(32768) : null;
                byte[] buf = new byte[8192];
                int r;
                long entrySize = 0;
                while ((r = zis.read(buf)) != -1) {
                    if (r > MAX_ENTRY_BYTES - entrySize || r > MAX_EXPANDED_BYTES - total)
                        throw new IOException("Размер распакованного CBZ превышает допустимый предел");
                    if (bos != null) bos.write(buf, 0, r);
                    entrySize += r;
                    total += r;
                }
                if (image) {
                    byte[] imgBytes = bos.toByteArray();
                    if (coverName == null || String.CASE_INSENSITIVE_ORDER.compare(entry.getName(), coverName) < 0) {
                        coverName = entry.getName();
                        cover = imgBytes;
                    }
                    String extension = lower.substring(lower.lastIndexOf('.'));
                    String safeName = String.format(Locale.ROOT, "%08d", entries) + extension;

                    if (lower.endsWith(".webp") || lower.endsWith(".avif")) {
                        safeName = safeName.replaceAll("(?i)\\.(webp|avif)$", ".png");
                        var bi = com.luminaread.service.MediaCacheService.getInstance().decodeBytes(imgBytes);
                        Path target = cacheDir.resolve(safeName);
                        target.toFile().deleteOnExit();
                        if (bi != null) {
                            com.luminaread.util.GeneratedCache.write(target, out -> {
                                if (!javax.imageio.ImageIO.write(bi, "png", out)) throw new IOException("Нет кодировщика PNG");
                            });
                            pages.add(new PageImg(entry.getName(), target));
                        } else {
                            com.luminaread.util.GeneratedCache.write(target, out -> out.write(imgBytes));
                            pages.add(new PageImg(entry.getName(), target));
                        }
                    } else {
                        Path target = cacheDir.resolve(safeName);
                        target.toFile().deleteOnExit();
                        com.luminaread.util.GeneratedCache.write(target, out -> out.write(imgBytes));
                        pages.add(new PageImg(entry.getName(), target));
                    }
                }
            }
          }
          completed = true;
        } finally {
            if (!completed) {
                try (var files = Files.list(cacheDir)) {
                    for (Path cached : files.toList()) Files.deleteIfExists(cached);
                } finally {
                    Files.deleteIfExists(cacheDir);
                    com.luminaread.util.GeneratedCache.invalidate(cacheRoot);
                }
            }
        }

        pages.sort(Comparator.comparing(PageImg::name, String.CASE_INSENSITIVE_ORDER));

        if (!pages.isEmpty()) {
            content.setCoverImage(cover);
        }

        int index = 0;
        for (PageImg page : pages) {
            String pageTitle = "Страница " + (index + 1);
            String html = "<div style='text-align:center; margin: 8px 0;'>"
                    + "<img src='" + page.savedPath().toUri() + "' width='540' />"
                    + "<p style='font-size:12px; color:gray;'>" + pageTitle + "</p></div>";
            content.addChapter(new Chapter(index++, pageTitle, html, pageTitle));
        }

        if (content.getChapters().isEmpty()) {
            content.addChapter(new Chapter(0, "Пустой архив CBZ", "<p>В архиве не найдено изображений.</p>", ""));
        }
        return content;
    }
}
