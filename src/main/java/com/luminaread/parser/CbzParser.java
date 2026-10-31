package com.luminaread.parser;

import com.luminaread.model.BookContent;
import com.luminaread.model.Chapter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class CbzParser implements BookParser {

    @Override
    public BookContent parse(File file) throws Exception {
        BookContent content = new BookContent();
        String baseTitle = file.getName();
        int dot = baseTitle.lastIndexOf('.');
        if (dot > 0) baseTitle = baseTitle.substring(0, dot);

        content.setTitle(baseTitle);
        content.setAuthor("Комикс / Иллюстрированное издание");

        Path cacheDir = Path.of(System.getProperty("user.home"), ".luminaread", "cache",
                "cbz_" + file.getName().replaceAll("[^a-zA-Z0-9._\\-]", "_"));
        Files.createDirectories(cacheDir);

         record PageImg(String name, byte[] bytes, Path savedPath) {}
        List<PageImg> pages = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String lower = entry.getName().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                        || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".avif")) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream(32768);
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = zis.read(buf)) != -1) {
                        bos.write(buf, 0, r);
                    }
                    byte[] imgBytes = bos.toByteArray();
                    String safeName = entry.getName().replace('/', '_').replace('\\', '_');

                    if (lower.endsWith(".webp") || lower.endsWith(".avif")) {
                        safeName = safeName.replaceAll("(?i)\\.(webp|avif)$", ".png");
                        var bi = com.luminaread.service.MediaCacheService.getInstance().decodeBytes(imgBytes);
                        Path target = cacheDir.resolve(safeName);
                        if (bi != null) {
                            javax.imageio.ImageIO.write(bi, "png", target.toFile());
                            pages.add(new PageImg(entry.getName(), imgBytes, target));
                        } else {
                            Files.write(target, imgBytes);
                            pages.add(new PageImg(entry.getName(), imgBytes, target));
                        }
                    } else {
                        Path target = cacheDir.resolve(safeName);
                        Files.write(target, imgBytes);
                        pages.add(new PageImg(entry.getName(), imgBytes, target));
                    }
                }
            }
        }

        pages.sort(Comparator.comparing(PageImg::name, String.CASE_INSENSITIVE_ORDER));

        if (!pages.isEmpty()) {
            content.setCoverImage(pages.get(0).bytes());
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
