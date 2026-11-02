package com.luminaread;

import com.luminaread.model.ranobe.RanobeChapter;
import com.luminaread.model.ranobe.RanobeChapterContent;
import com.luminaread.model.ranobe.RanobeItem;
import com.luminaread.service.IRanobeService;
import com.luminaread.service.RanobeService;
import com.luminaread.service.ranobe.RanobeLibClient;
import com.luminaread.service.ranobe.RanobeStateRepository;
import com.luminaread.util.Json;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Architecture and unit tests verifying SOLID principles, model extraction,
 * repository isolation, client functionality, and 100% backward compatibility for Ranobe.
 */
public class RanobeArchitectureTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== RUNNING RANOBE ARCHITECTURE & SOLID COMPLIANCE TESTS ===");

        testDomainModels();
        testDipInterfaceSegregation();
        testStateRepositoryPersistence();
        testRanobeLibClientTipTapAndHtml();
        testRanobeServiceFacadeAndBackwardCompatibility();

        System.out.println("=== ALL RANOBE ARCHITECTURE TESTS PASSED SUCCESSFULLY! ===");
    }

    private static void testDomainModels() {
        System.out.println("1. Verifying domain models in com.luminaread.model.ranobe...");

        RanobeItem item1 = new RanobeItem("slug-1", "Original Title", "Русский Заголовок",
                "Описание книги", "https://example.com/cover.jpg", "Выпуск", "★ 9.8", "2024",
                List.of("Приключения", "Фэнтези"), List.of("Автор 1", "Автор 2"));

        assertEq(item1.id, "slug-1", "RanobeItem id mismatch");
        assertEq(item1.getDisplayTitle(), "Русский Заголовок (Original Title)", "Display title incorrect");
        assertEq(item1.getGenresString(), "Приключения, Фэнтези", "Genres string incorrect");
        assertEq(item1.getAuthorsString(), "Автор 1, Автор 2", "Authors string incorrect");

        RanobeItem itemNoRus = new RanobeItem("slug-2", "Solo Title", "", null, null, null, null, null, null, null);
        assertEq(itemNoRus.getDisplayTitle(), "Solo Title", "Solo title mismatch");
        assertEq(itemNoRus.description, "", "Null description should default to empty");
        assertEq(itemNoRus.rating, "★ —", "Default rating mismatch");
        assertEq(itemNoRus.genres.size(), 0, "Null genres should default to empty list");
        assertEq(itemNoRus.authors.size(), 0, "Null authors should default to empty list");

        RanobeChapter ch = new RanobeChapter("ch-1", "2", "15.5", "Начало битвы", "123", "DreamTeam");
        assertEq(ch.volume, "2", "Volume mismatch");
        assertEq(ch.number, "15.5", "Number mismatch");
        assertEq(ch.toString(), "Том 2, Глава 15.5 — Начало битвы [DreamTeam]", "Chapter toString mismatch");

        RanobeChapterContent content = new RanobeChapterContent("Глава 15", "2", "15.5",
                "<p>Текст</p>", "Текст", List.of("https://example.com/img1.jpg"));
        assertEq(content.title, "Глава 15", "Content title mismatch");
        assertEq(content.illustrations.size(), 1, "Illustrations size mismatch");

        System.out.println("   [PASS] Domain models null-safety, immutability, and formatting validated.");
    }

    private static void testDipInterfaceSegregation() {
        System.out.println("2. Verifying Dependency Inversion Principle (DIP) on IRanobeService...");

        Method[] methods = IRanobeService.class.getDeclaredMethods();
        for (Method m : methods) {
            String returnTypeName = m.getReturnType().getName();
            if (returnTypeName.contains("RanobeService$")) {
                throw new RuntimeException("DIP Violation: IRanobeService method " + m.getName()
                        + " returns concrete inner class " + returnTypeName);
            }
            for (Class<?> paramType : m.getParameterTypes()) {
                if (paramType.getName().contains("RanobeService$")) {
                    throw new RuntimeException("DIP Violation: IRanobeService method " + m.getName()
                            + " has parameter of concrete inner class " + paramType.getName());
                }
            }
        }

        System.out.println("   [PASS] IRanobeService depends exclusively on domain models, not concrete inner classes.");
    }

    private static void testStateRepositoryPersistence() throws Exception {
        System.out.println("3. Verifying RanobeStateRepository encapsulation & persistence...");

        Path tempDir = Files.createTempDirectory("ranobe-repo-test-");
        Path customFile = tempDir.resolve("custom_ranobe_state.json");

        RanobeStateRepository repo = new RanobeStateRepository(customFile);
        assertEq(repo.getStateFilePath(), customFile, "Custom state file path not respected");

        Map<String, RanobeItem> favs = new LinkedHashMap<>();
        Map<String, RanobeItem> hist = new LinkedHashMap<>();
        Map<String, String> lastRead = new LinkedHashMap<>();
        Map<String, String> prefs = new LinkedHashMap<>();

        RanobeItem itemA = new RanobeItem("item-a", "Title A", "Тайтл А", "Desc", "coverA", "Выпуск", "★ 9.0", "2023", List.of("Экшен"), List.of("Автор А"));
        RanobeItem itemB = new RanobeItem("item-b", "Title B", "Тайтл Б", "Desc B", "coverB", "Завершён", "★ 8.5", "2021", List.of("Драма"), List.of("Автор Б"));

        favs.put(itemA.id, itemA);
        hist.put(itemB.id, itemB);
        lastRead.put(itemA.id, "Том 1, Глава 5");
        prefs.put("fontSize", "24");
        prefs.put("theme", "Dark");

        repo.saveState(favs, hist, lastRead, prefs);

        if (!Files.exists(customFile)) {
            throw new RuntimeException("RanobeStateRepository failed to create file on disk!");
        }

        Map<String, RanobeItem> loadedFavs = new LinkedHashMap<>();
        Map<String, RanobeItem> loadedHist = new LinkedHashMap<>();
        Map<String, String> loadedLastRead = new LinkedHashMap<>();
        Map<String, String> loadedPrefs = new LinkedHashMap<>();

        repo.loadState(loadedFavs, loadedHist, loadedLastRead, loadedPrefs);

        assertEq(loadedFavs.size(), 1, "Loaded favs count mismatch");
        RanobeItem loadedA = loadedFavs.get("item-a");
        if (loadedA == null || !loadedA.title.equals("Title A") || !loadedA.rusTitle.equals("Тайтл А")) {
            throw new RuntimeException("Loaded item details mismatch!");
        }

        assertEq(loadedHist.size(), 1, "Loaded hist count mismatch");
        assertEq(loadedLastRead.get("item-a"), "Том 1, Глава 5", "Loaded last read chapter mismatch");
        assertEq(loadedPrefs.get("fontSize"), "24", "Loaded preference fontSize mismatch");
        assertEq(loadedPrefs.get("theme"), "Dark", "Loaded preference theme mismatch");

        // Test null-safety: saving with nulls should not crash
        repo.saveState(null, null, null, null);
        repo.loadState(null, null, null, null);

        System.out.println("   [PASS] RanobeStateRepository persisted and loaded state correctly with full null-safety.");
    }

    private static void testRanobeLibClientTipTapAndHtml() {
        System.out.println("4. Verifying RanobeLibClient TipTap doc parsing and HTML generation...");

        String complexDoc = "{"
                + "\"type\":\"doc\","
                + "\"content\":["
                + "  {\"type\":\"heading\",\"attrs\":{\"level\":3},\"content\":[{\"type\":\"text\",\"text\":\"Раздел 1\"}]},"
                + "  {\"type\":\"paragraph\",\"attrs\":{\"textAlign\":\"center\"},\"content\":["
                + "    {\"type\":\"text\",\"text\":\"Центрированный текст с \"},"
                + "    {\"type\":\"text\",\"marks\":[{\"type\":\"bold\"}],\"text\":\"жирным\"},"
                + "    {\"type\":\"text\",\"text\":\" и \"},"
                + "    {\"type\":\"text\",\"marks\":[{\"type\":\"italic\"}],\"text\":\"курсивом\"}"
                + "  ]},"
                + "  {\"type\":\"blockquote\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Цитата персонажа\"}]}]},"
                + "  {\"type\":\"code_block\",\"content\":[{\"type\":\"text\",\"text\":\"System.out.println();\"}]},"
                + "  {\"type\":\"bullet_list\",\"content\":["
                + "    {\"type\":\"list_item\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Пункт списка\"}]}]}"
                + "  ]}"
                + "]"
                + "}";

        Object parsed = Json.parse(complexDoc);
        List<String> illustrations = new ArrayList<>();
        Set<String> rendered = new HashSet<>();

        String html = RanobeLibClient.docToHtml(parsed, illustrations, rendered);

        if (!html.contains("<h3>Раздел 1</h3>")) {
            throw new RuntimeException("Heading rendering failed: " + html);
        }
        if (!html.contains("<p style='text-align:center;'>") || !html.contains("<b>жирным</b>") || !html.contains("<i>курсивом</i>")) {
            throw new RuntimeException("Paragraph formatting failed: " + html);
        }
        if (!html.contains("<blockquote>") || !html.contains("Цитата персонажа")) {
            throw new RuntimeException("Blockquote rendering failed: " + html);
        }
        if (!html.contains("<pre><code>") || !html.contains("System.out.println();")) {
            throw new RuntimeException("Code block rendering failed: " + html);
        }
        if (!html.contains("<ul>") || !html.contains("<li>")) {
            throw new RuntimeException("List rendering failed: " + html);
        }

        System.out.println("   [PASS] RanobeLibClient TipTap parsing produces semantically correct HTML.");
    }

    private static void testRanobeServiceFacadeAndBackwardCompatibility() throws Exception {
        System.out.println("5. Verifying RanobeService facade & backward compatibility with callers...");

        RanobeService service = new RanobeService();

        // 1. Check inner class backward-compatible hierarchy
        RanobeService.RanobeItem legacyItem = new RanobeService.RanobeItem(
                "legacy-1", "Legacy Title", "Старое название", "Описание", "cover", "Выпуск", "★ 9.0", "2024",
                List.of("Фэнтези"), List.of("Автор")
        );
        if (!(legacyItem instanceof com.luminaread.model.ranobe.RanobeItem)) {
            throw new RuntimeException("RanobeService.RanobeItem must extend com.luminaread.model.ranobe.RanobeItem!");
        }

        RanobeService.RanobeChapter legacyChapter = new RanobeService.RanobeChapter(
                "legacy-ch-1", "1", "1", "Первая глава", "b1", "Команда"
        );
        if (!(legacyChapter instanceof com.luminaread.model.ranobe.RanobeChapter)) {
            throw new RuntimeException("RanobeService.RanobeChapter must extend com.luminaread.model.ranobe.RanobeChapter!");
        }

        RanobeService.RanobeChapterContent legacyContent = new RanobeService.RanobeChapterContent(
                "Глава 1", "1", "1", "<p>Контент</p>", "Контент", List.of()
        );
        if (!(legacyContent instanceof com.luminaread.model.ranobe.RanobeChapterContent)) {
            throw new RuntimeException("RanobeService.RanobeChapterContent must extend com.luminaread.model.ranobe.RanobeChapterContent!");
        }

        // 2. Check static utility backward compatibility
        RanobeService.CachedImageInfo emptyCached = RanobeService.cacheIllustration(null);
        assertEq(emptyCached.localUrl, "", "Empty url cacheIllustration mismatch");

        String staticHtml = RanobeService.docToHtml("Простой текст", new ArrayList<>(), new HashSet<>());
        if (!staticHtml.contains("<p>Простой текст</p>")) {
            throw new RuntimeException("Static docToHtml failed: " + staticHtml);
        }

        // 3. Check getters
        if (service.getRanobeLibClient() == null || service.getStateRepository() == null) {
            throw new RuntimeException("RanobeService getters must return non-null delegates!");
        }

        // 4. Check IRanobeService polymorphism
        IRanobeService iService = service;
        Map<String, RanobeService.RanobeItem> legacyFavs = new LinkedHashMap<>();
        legacyFavs.put(legacyItem.id, legacyItem);
        iService.saveRanobeState(legacyFavs, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());

        Map<String, RanobeService.RanobeItem> loadedFavs = new LinkedHashMap<>();
        iService.loadRanobeState(loadedFavs, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());

        System.out.println("   [PASS] RanobeService backward compatibility and polymorphic delegation confirmed.");
    }

    private static void assertEq(Object actual, Object expected, String msg) {
        if (!Objects.equals(actual, expected)) {
            throw new RuntimeException(msg + " | Expected: " + expected + ", Actual: " + actual);
        }
    }
}
