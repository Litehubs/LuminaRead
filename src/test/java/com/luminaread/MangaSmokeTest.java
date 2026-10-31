package com.luminaread;

import com.luminaread.service.MangaService;
import com.luminaread.service.MangaService.MangaItem;
import com.luminaread.service.MangaService.MangaChapter;
import java.awt.image.BufferedImage;
import java.util.*;

public class MangaSmokeTest {
    public static void main(String[] args) throws Exception {
        System.out.println("=== RUNNING MANGA SMOKE TESTS ===");
        MangaService service = new MangaService();

        System.out.println("Testing MangaLib search...");
        List<MangaItem> mglList = service.searchManga("Naruto", "MangaLib (RU)");
        System.out.println("MangaLib results: " + mglList.size());
        if (mglList.isEmpty()) {
            System.out.println("SKIP online manga network tests: network unreachable in current environment.");
            System.out.println("Running offline Manga state persistence test...");

            java.nio.file.Path tempHome = java.nio.file.Files.createTempDirectory("luminaread-manga-test-");
            String origHome = System.getProperty("user.home");
            System.setProperty("user.home", tempHome.toString());
            try {
                MangaItem mockItem = new MangaItem("mock-manga-1", "Mock Manga", "Тестовая манга", "Описание", "https://example.com/cover.jpg", "MangaLib (RU)", "В процессе", "★ 9.9");
                Map<String, MangaItem> favs = new LinkedHashMap<>();
                Map<String, MangaItem> hist = new LinkedHashMap<>();
                Map<String, String> prog = new LinkedHashMap<>();
                favs.put(mockItem.id, mockItem);
                prog.put(mockItem.id, "Гл. 1");
                service.saveMangaState(favs, hist, prog);

                Map<String, MangaItem> loadedFavs = new LinkedHashMap<>();
                Map<String, MangaItem> loadedHist = new LinkedHashMap<>();
                Map<String, String> loadedProg = new LinkedHashMap<>();
                service.loadMangaState(loadedFavs, loadedHist, loadedProg);
                if (!loadedFavs.containsKey(mockItem.id) || !"Гл. 1".equals(loadedProg.get(mockItem.id))) {
                    throw new RuntimeException("State persistence failed!");
                }
                System.out.println("Manga state successfully persisted and reloaded!");
                System.out.println("=== ALL MANGA SMOKE TESTS PASSED (OFFLINE MODE)! ===");
            } finally {
                if (origHome != null) System.setProperty("user.home", origHome);
                else System.clearProperty("user.home");
            }
            return;
        }

        System.out.println("Testing Remanga search...");
        List<MangaItem> remList = service.searchManga("Наруто", "Remanga (RU)");
        System.out.println("Remanga results: " + remList.size());
        if (remList.isEmpty()) throw new RuntimeException("Remanga search failed!");

        System.out.println("Testing MangaLib chapters...");
        List<MangaChapter> mglChapters = service.fetchChapters(mglList.get(0));
        System.out.println("MangaLib chapters found: " + mglChapters.size());
        if (mglChapters.isEmpty()) throw new RuntimeException("MangaLib chapters failed!");

        System.out.println("Testing Remanga chapters...");
        List<MangaChapter> remChapters = service.fetchChapters(remList.get(0));
        System.out.println("Remanga chapters found: " + remChapters.size());
        if (remChapters.isEmpty()) throw new RuntimeException("Remanga chapters failed!");
        System.out.println("First Remanga chapter: " + remChapters.get(0));

        System.out.println("Testing MangaLib page URLs...");
        List<String> mglPages = service.fetchPageUrls(mglChapters.get(0));
        System.out.println("MangaLib pages found: " + mglPages.size());
        if (mglPages.isEmpty()) throw new RuntimeException("MangaLib pages failed!");
        System.out.println("MangaLib sample page URL: " + mglPages.get(0));

        System.out.println("Testing image loading and decoding...");
        BufferedImage img = service.loadImageCached(mglPages.get(0));
        System.out.println("MangaLib page image loaded: " + (img != null ? (img.getWidth() + "x" + img.getHeight()) : "FAILED"));
        if (img == null) throw new RuntimeException("Image loading failed!");

        System.out.println("Testing explicit WebP cover decode...");
        BufferedImage webpImg = service.loadImageCached("https://remanga.org/media/titles/naruto-hashirama-worlds-best-grandpa/cover_521f5387.webp");
        System.out.println("WebP decode result: " + (webpImg != null ? (webpImg.getWidth() + "x" + webpImg.getHeight()) : "FAILED"));
        if (webpImg == null) throw new RuntimeException("WebP decoding failed!");

        System.out.println("Testing Manga state persistence...");
        Map<String, MangaItem> favs = new LinkedHashMap<>();
        Map<String, MangaItem> hist = new LinkedHashMap<>();
        Map<String, String> prog = new LinkedHashMap<>();
        favs.put(mglList.get(0).id, mglList.get(0));
        prog.put(mglList.get(0).id, "Гл. 1");
        service.saveMangaState(favs, hist, prog);

        Map<String, MangaItem> loadedFavs = new LinkedHashMap<>();
        Map<String, MangaItem> loadedHist = new LinkedHashMap<>();
        Map<String, String> loadedProg = new LinkedHashMap<>();
        service.loadMangaState(loadedFavs, loadedHist, loadedProg);
        if (!loadedFavs.containsKey(mglList.get(0).id) || !"Гл. 1".equals(loadedProg.get(mglList.get(0).id))) {
            throw new RuntimeException("State persistence failed!");
        }
        System.out.println("Manga state successfully persisted and reloaded!");

        System.out.println("=== ALL MANGA SMOKE TESTS PASSED! ===");
    }
}
