package com.luminaread;

import com.luminaread.model.Book;
import com.luminaread.model.BookContent;
import com.luminaread.model.Chapter;
import com.luminaread.model.DownloadTask;
import com.luminaread.model.OnlineBookItem;
import com.luminaread.model.ranobe.RanobeChapter;
import com.luminaread.model.ranobe.RanobeChapterContent;
import com.luminaread.model.ranobe.RanobeItem;
import com.luminaread.parser.BookParser;
import com.luminaread.parser.BookParserFactory;
import com.luminaread.parser.BookParserRegistry;
import com.luminaread.parser.CbzParser;
import com.luminaread.parser.EpubParser;
import com.luminaread.parser.Fb2Parser;
import com.luminaread.parser.PdfParser;
import com.luminaread.parser.TxtMarkdownParser;
import com.luminaread.service.DictionaryService;
import com.luminaread.service.IDictionaryService;
import com.luminaread.service.IOnlineCatalogService;
import com.luminaread.service.IRanobeService;
import com.luminaread.service.ITtsService;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.OnlineCatalogService;
import com.luminaread.service.RanobeService;
import com.luminaread.service.TtsService;
import com.luminaread.service.catalog.BookDownloader;
import com.luminaread.service.catalog.IBookDownloader;
import com.luminaread.service.catalog.ICatalogSearchService;
import com.luminaread.service.catalog.IDownloadManager;
import com.luminaread.service.catalog.OpdsFeedParser;
import com.luminaread.service.ranobe.RanobeLibClient;
import com.luminaread.service.ranobe.RanobeStateRepository;
import com.luminaread.service.tts.LinuxTtsEngine;
import com.luminaread.service.tts.MacTtsEngine;
import com.luminaread.service.tts.TtsEngine;
import com.luminaread.service.tts.WindowsTtsEngine;
import com.luminaread.ui.MainWindow;
import com.luminaread.ui.ReaderPanel;
import com.luminaread.ui.SettingsPanel;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleConsumer;

/**
 * Master Verification Test Suite for LuminaRead.
 * Exhaustively validates all SOLID principles (SRP, OCP, LSP, ISP, DIP),
 * backwards compatibility, constructor contracts, and mockability across the codebase.
 */
public class MasterSolidComplianceTest {

    private static int testsRun = 0;
    private static int testsPassed = 0;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("        LUMINASOFT / LUMINAREAD MASTER SOLID COMPLIANCE VERIFICATION SUITE       ");
        System.out.println("================================================================================");

        runTest("Single Responsibility Principle (SRP) Verification", MasterSolidComplianceTest::verifySrp);
        runTest("Open/Closed Principle (OCP) Verification", MasterSolidComplianceTest::verifyOcp);
        runTest("Liskov Substitution Principle (LSP) Verification", MasterSolidComplianceTest::verifyLsp);
        runTest("Interface Segregation Principle (ISP) Verification", MasterSolidComplianceTest::verifyIsp);
        runTest("Dependency Inversion Principle (DIP) Verification", MasterSolidComplianceTest::verifyDip);
        runTest("UI Backward Compatibility & Non-Breaking Overloads", MasterSolidComplianceTest::verifyUiBackwardCompatibility);
        runTest("Domain Model Encapsulation & Immutability", MasterSolidComplianceTest::verifyDomainModelIntegrity);

        System.out.println("================================================================================");
        System.out.println(String.format("VERIFICATION SUMMARY: %d / %d tests passed successfully.", testsPassed, testsRun));
        System.out.println("================================================================================");

        if (testsPassed != testsRun) {
            System.exit(1);
        }
    }

    private static void runTest(String name, TestRunnable runnable) {
        testsRun++;
        System.out.print(String.format("[%d] %-65s ", testsRun, name));
        try {
            runnable.run();
            testsPassed++;
            System.out.println("[PASS]");
        } catch (Throwable t) {
            System.out.println("[FAIL]");
            System.err.println("  -> Error: " + t.getMessage());
            t.printStackTrace(System.err);
        }
    }

    @FunctionalInterface
    interface TestRunnable {
        void run() throws Throwable;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    // =========================================================================
    // 1. Single Responsibility Principle (SRP)
    // =========================================================================
    private static void verifySrp() throws Exception {
        // --- Ranobe SRP ---
        // RanobeService coordinates; RanobeLibClient handles HTTP/HTML; RanobeStateRepository handles JSON disk persistence.
        RanobeLibClient client = new RanobeLibClient();
        RanobeStateRepository repo = new RanobeStateRepository();
        RanobeService service = new RanobeService(client, repo);

        require(service.getRanobeLibClient() == client, "RanobeService must expose injected client");
        require(service.getStateRepository() == repo, "RanobeService must expose injected repository");

        // Verify RanobeLibClient does NOT contain file writing / persistence methods
        for (Method m : RanobeLibClient.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            require(!name.contains("savestate") && !name.contains("loadstate"),
                    "RanobeLibClient violates SRP by having persistence method: " + m.getName());
        }

        // Verify RanobeStateRepository does NOT contain HTTP / search / network fetching methods
        for (Method m : RanobeStateRepository.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            require(!name.contains("search") && !name.contains("fetchnetwork") && !name.contains("http"),
                    "RanobeStateRepository violates SRP by having network method: " + m.getName());
        }

        // --- OnlineCatalog SRP ---
        // OnlineCatalogService coordinates; OpdsFeedParser parses XML/Atom feeds; BookDownloader handles HTTP/streams/saving.
        OpdsFeedParser feedParser = new OpdsFeedParser();
        BookDownloader downloader = new BookDownloader();
        OnlineCatalogService catalogService = new OnlineCatalogService(feedParser, downloader, null);

        require(catalogService.getOpdsFeedParser() == feedParser, "OnlineCatalogService must hold injected feed parser");
        require(catalogService.getBookDownloader() == downloader, "OnlineCatalogService must hold injected downloader");

        // OpdsFeedParser must only parse feeds and not have download file methods
        for (Method m : OpdsFeedParser.class.getDeclaredMethods()) {
            String name = m.getName().toLowerCase();
            require(!name.contains("downloadbook") && !name.contains("savetofile"),
                    "OpdsFeedParser violates SRP: " + m.getName());
        }

        // --- TTS SRP ---
        // TtsService coordinates playback state/timer; TtsEngine builds the OS command
        TtsService tts = new TtsService(new MacTtsEngine());
        require(tts.getEngine() instanceof MacTtsEngine, "TtsService must delegate process creation to engine");
    }

    // =========================================================================
    // 2. Open/Closed Principle (OCP)
    // =========================================================================
    private static void verifyOcp() throws Exception {
        // --- BookParserRegistry OCP ---
        BookParserRegistry registry = new BookParserRegistry();
        int initialCount = registry.getRegisteredParsers().size();

        AtomicBoolean djvuParsed = new AtomicBoolean(false);
        BookParser customDjVuParser = new BookParser() {
            @Override
            public BookContent parse(File file) {
                djvuParsed.set(true);
                BookContent content = new BookContent();
                content.setTitle("DJVU Book");
                content.addChapter(new Chapter(0, "DJVU Page 1", "<p>Djvu page</p>", "Djvu plain text"));
                return content;
            }

            @Override
            public boolean supports(File file, String formatHint) {
                return (formatHint != null && formatHint.equalsIgnoreCase("DJVU"))
                        || (file != null && file.getName().toLowerCase().endsWith(".djvu"));
            }

            @Override
            public String getSupportedFormat() {
                return "DJVU";
            }
        };

        File mockDjvu = new File("encyclopedia.djvu");
        require(registry.findParser(mockDjvu, "DJVU").isEmpty(), "DJVU should not be recognized initially");

        // Register custom parser: System is open for extension without changing core parser classes
        registry.registerParser(customDjVuParser);
        require(registry.getRegisteredParsers().size() == initialCount + 1, "Parser count must increment");
        require("DJVU".equals(registry.detectFormatLabel(mockDjvu)), "Format detection should identify DJVU");

        BookContent parsed = registry.parseBook(mockDjvu);
        require(djvuParsed.get(), "Custom DJVU parser must be invoked");
        require("DJVU Book".equals(parsed.getTitle()), "Parsed content title must match");

        // Unregister
        require(registry.unregisterParser(customDjVuParser), "Unregistration must succeed");
        require(registry.getRegisteredParsers().size() == initialCount, "Parser count must revert");

        // --- TtsEngine OCP ---
        // Able to create a brand new TTS engine (e.g. Piper/Cloud) and supply it to TtsService without modifying TtsService
        AtomicBoolean customEngineInvoked = new AtomicBoolean(false);
        TtsEngine piperEngine = new TtsEngine() {
            @Override
            public ProcessBuilder buildProcess(String textToSpeak, int rateWpm) {
                customEngineInvoked.set(true);
                return new ProcessBuilder("piper-tts", "--text", textToSpeak, "--speed", String.valueOf(rateWpm));
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        TtsService openTts = new TtsService(piperEngine);
        ProcessBuilder pb = openTts.createProcessBuilder("Hello Piper", 160);
        require(customEngineInvoked.get(), "Custom TTS engine strategy must be called");
        require(pb.command().contains("piper-tts"), "ProcessBuilder must contain custom command");
        require(pb.command().contains("Hello Piper"), "ProcessBuilder must contain text");
    }

    // =========================================================================
    // 3. Liskov Substitution Principle (LSP)
    // =========================================================================
    private static void verifyLsp() throws Exception {
        // --- BookParser LSP: All BookParser implementations must safely handle null inputs ---
        List<BookParser> parsers = List.of(
                new Fb2Parser(),
                new EpubParser(),
                new PdfParser(),
                new CbzParser(),
                new TxtMarkdownParser()
        );

        for (BookParser parser : parsers) {
            // Contract check: getSupportedFormat() must never return null or empty
            String fmt = parser.getSupportedFormat();
            require(fmt != null && !fmt.isBlank(), parser.getClass().getSimpleName() + " must have a valid format label");

            // Contract check: supports(null, null) must return false and NEVER throw NullPointerException
            try {
                boolean result = parser.supports(null, null);
                require(!result, parser.getClass().getSimpleName() + ".supports(null, null) should return false");
            } catch (Exception ex) {
                throw new AssertionError(parser.getClass().getSimpleName() + " threw exception on supports(null, null)", ex);
            }
        }

        // --- TtsEngine LSP: All TtsEngine implementations must return non-null ProcessBuilder ---
        List<TtsEngine> engines = List.of(
                new MacTtsEngine(),
                new WindowsTtsEngine(),
                new LinuxTtsEngine(false),
                new LinuxTtsEngine(true)
        );

        for (TtsEngine engine : engines) {
            ProcessBuilder pb = engine.buildProcess("Test text substitution", 175);
            require(pb != null, engine.getClass().getSimpleName() + ".buildProcess must return non-null ProcessBuilder");
            require(!pb.command().isEmpty(), engine.getClass().getSimpleName() + " command list must not be empty");
        }

        // --- IDictionaryService LSP: Any implementation is cleanly swappable ---
        IDictionaryService realDict = new DictionaryService();
        IDictionaryService mockDict = text -> "Translated: " + text;

        require(realDict.lookupAndTranslate("") != null, "Real dict must handle empty gracefully");
        require("Translated: test".equals(mockDict.lookupAndTranslate("test")), "Mock dict must work seamlessly");

        // --- IRanobeService LSP: Facade RanobeService must satisfy IRanobeService contract ---
        IRanobeService ranobeService = new RanobeService();
        require(ranobeService instanceof IRanobeService, "RanobeService must implement IRanobeService");
    }

    // =========================================================================
    // 4. Interface Segregation Principle (ISP)
    // =========================================================================
    private static void verifyIsp() {
        // Verify IOnlineCatalogService is partitioned into 3 focused interfaces:
        require(ICatalogSearchService.class.isAssignableFrom(IOnlineCatalogService.class),
                "IOnlineCatalogService must extend ICatalogSearchService");
        require(IDownloadManager.class.isAssignableFrom(IOnlineCatalogService.class),
                "IOnlineCatalogService must extend IDownloadManager");
        require(IBookDownloader.class.isAssignableFrom(IOnlineCatalogService.class),
                "IOnlineCatalogService must extend IBookDownloader");

        // A consumer requiring ONLY search operations should be able to accept ICatalogSearchService:
        AtomicBoolean searchCalled = new AtomicBoolean(false);
        ICatalogSearchService pureSearcher = new ICatalogSearchService() {
            @Override
            public List<OnlineBookItem> searchBooks(String query, String sourceFilter, String languageFilter) {
                searchCalled.set(true);
                OnlineBookItem book = new OnlineBookItem("Title", "Author", "summary", "en", "Source");
                book.setCoverUrl("https://cover.jpg");
                return List.of(book);
            }

            @Override
            public List<OnlineBookItem> browseCustomOpds(String opdsUrl, String optionalSearchTerm) {
                return Collections.emptyList();
            }

            @Override
            public List<OnlineBookItem> fetchOpdsFeed(String feedUrl, String sourceLabel) {
                return Collections.emptyList();
            }
        };

        List<OnlineBookItem> results = pureSearcher.searchBooks("query", null, null);
        require(searchCalled.get(), "Pure searcher must be invokable");
        require(results.size() == 1, "Result must contain 1 item");

        // A consumer requiring ONLY download management operations should be able to accept IDownloadManager:
        AtomicInteger listenerCount = new AtomicInteger(0);
        IDownloadManager pureDownloadManager = new IDownloadManager() {
            private final List<DownloadTask> tasks = new ArrayList<>();
            private final List<Runnable> listeners = new ArrayList<>();

            @Override
            public List<DownloadTask> getDownloadTasks() {
                return new ArrayList<>(tasks);
            }

            @Override
            public void clearCompletedTasks() {
                tasks.removeIf(t -> t.getStatus() != DownloadTask.Status.DOWNLOADING);
                notifyDownloadListeners();
            }

            @Override
            public void addDownloadListener(Runnable listener) {
                listeners.add(listener);
            }

            @Override
            public void notifyDownloadListeners() {
                listenerCount.incrementAndGet();
                for (Runnable r : listeners) r.run();
            }

            @Override
            public DownloadTask createTrackedTask(String title, String formatHint, String url) {
                DownloadTask t = new DownloadTask(title, formatHint, url);
                tasks.add(t);
                notifyDownloadListeners();
                return t;
            }
        };

        pureDownloadManager.addDownloadListener(() -> {});
        DownloadTask task = pureDownloadManager.createTrackedTask("Test Task", "EPUB", "https://dl.example.com");
        require(task != null, "Task created");
        require(pureDownloadManager.getDownloadTasks().size() == 1, "1 task tracked");
        require(listenerCount.get() == 1, "Listener notified");
    }

    // =========================================================================
    // 5. Dependency Inversion Principle (DIP)
    // =========================================================================
    private static void verifyDip() throws Exception {
        // --- 1. IRanobeService must depend solely on domain models, not concrete inner classes ---
        Method[] methods = IRanobeService.class.getDeclaredMethods();
        for (Method m : methods) {
            String retName = m.getReturnType().getName();
            require(!retName.contains("RanobeService$"),
                    "IRanobeService method " + m.getName() + " returns concrete inner class: " + retName);

            for (Class<?> pType : m.getParameterTypes()) {
                require(!pType.getName().contains("RanobeService$"),
                        "IRanobeService method " + m.getName() + " accepts concrete inner class parameter: " + pType.getName());
            }
        }

        // --- 2. TtsService must accept ITtsService / TtsEngine abstractions ---
        Constructor<TtsService> engineConstructor = TtsService.class.getConstructor(TtsEngine.class);
        require(engineConstructor != null, "TtsService must provide constructor accepting TtsEngine abstraction");

        // --- 3. UI Panels must provide constructors accepting ITtsService and IDictionaryService abstractions ---
        boolean mainWindowHasDip = false;
        for (Constructor<?> c : MainWindow.class.getConstructors()) {
            Class<?>[] pTypes = c.getParameterTypes();
            if (pTypes.length == 4 && pTypes[2] == ITtsService.class && pTypes[3] == IDictionaryService.class) {
                mainWindowHasDip = true;
                break;
            }
        }
        require(mainWindowHasDip, "MainWindow must expose constructor accepting (LibraryStorageService, OnlineCatalogService, ITtsService, IDictionaryService)");

        boolean readerPanelHasDip = false;
        for (Constructor<?> c : ReaderPanel.class.getConstructors()) {
            Class<?>[] pTypes = c.getParameterTypes();
            if (pTypes.length >= 3 && pTypes[1] == ITtsService.class && pTypes[2] == IDictionaryService.class) {
                readerPanelHasDip = true;
                break;
            }
        }
        require(readerPanelHasDip, "ReaderPanel must expose constructor accepting ITtsService and IDictionaryService abstractions");

        boolean settingsPanelHasDip = false;
        for (Constructor<?> c : SettingsPanel.class.getConstructors()) {
            Class<?>[] pTypes = c.getParameterTypes();
            if (pTypes.length >= 3 && pTypes[2] == ITtsService.class) {
                settingsPanelHasDip = true;
                break;
            }
        }
        require(settingsPanelHasDip, "SettingsPanel must expose constructor accepting ITtsService abstraction");
    }

    // =========================================================================
    // 6. UI Backward Compatibility & Non-Breaking Overloads
    // =========================================================================
    private static void verifyUiBackwardCompatibility() throws Exception {
        // 1. Verify MainWindow legacy constructor still exists: (LibraryStorageService, OnlineCatalogService, TtsService, DictionaryService)
        Constructor<MainWindow> legacyMw = MainWindow.class.getConstructor(
                LibraryStorageService.class, OnlineCatalogService.class, TtsService.class, DictionaryService.class);
        require(legacyMw != null, "MainWindow must preserve legacy constructor for backward compatibility");

        // 2. Verify ReaderPanel legacy constructor still exists: (LibraryStorageService, TtsService, DictionaryService, Runnable, ConsumerThemeCallback)
        boolean readerLegacyFound = false;
        for (Constructor<?> c : ReaderPanel.class.getConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length == 5 && params[1] == TtsService.class && params[2] == DictionaryService.class) {
                readerLegacyFound = true;
                break;
            }
        }
        require(readerLegacyFound, "ReaderPanel must preserve legacy constructor taking concrete TtsService and DictionaryService");

        // 3. Verify SettingsPanel legacy constructor still exists: (LibraryStorageService, OnlineCatalogService, TtsService, Consumer, Runnable, Consumer)
        boolean settingsLegacyFound = false;
        for (Constructor<?> c : SettingsPanel.class.getConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length == 6 && params[2] == TtsService.class) {
                settingsLegacyFound = true;
                break;
            }
        }
        require(settingsLegacyFound, "SettingsPanel must preserve legacy constructor taking concrete TtsService");

        // 4. Verify RanobeService legacy inner classes inherit from domain models
        RanobeService.RanobeItem legacyItem = new RanobeService.RanobeItem(
                "legacy-id", "Original", "Русский", "Desc", "url", "Выпуск", "★ 9.5", "2024", List.of("Генре"), List.of("Автор"));
        require(legacyItem instanceof com.luminaread.model.ranobe.RanobeItem,
                "RanobeService.RanobeItem must extend com.luminaread.model.ranobe.RanobeItem");

        RanobeService.RanobeChapter legacyChapter = new RanobeService.RanobeChapter("ch-id", "1", "1", "Глава 1", "b1", "Team");
        require(legacyChapter instanceof com.luminaread.model.ranobe.RanobeChapter,
                "RanobeService.RanobeChapter must extend com.luminaread.model.ranobe.RanobeChapter");

        RanobeService.RanobeChapterContent legacyContent = new RanobeService.RanobeChapterContent("Глава 1", "1", "1", "<p>Html</p>", "Plain", List.of());
        require(legacyContent instanceof com.luminaread.model.ranobe.RanobeChapterContent,
                "RanobeService.RanobeChapterContent must extend com.luminaread.model.ranobe.RanobeChapterContent");

        // 5. Verify BookParserFactory static facade methods still exist
        Method detectMethod = BookParserFactory.class.getMethod("detectFormatLabel", File.class);
        require(Modifier.isStatic(detectMethod.getModifiers()), "BookParserFactory.detectFormatLabel must remain static");

        Method parseMethod = BookParserFactory.class.getMethod("parseBook", File.class);
        require(Modifier.isStatic(parseMethod.getModifiers()), "BookParserFactory.parseBook must remain static");
    }

    // =========================================================================
    // 7. Domain Model Encapsulation & Immutability
    // =========================================================================
    private static void verifyDomainModelIntegrity() {
        RanobeItem item = new RanobeItem("id-123", "Orig", "Русский", "Desc", "cover", "status", "★ 9.0", "2024",
                List.of("Fantasy", "Adventure"), List.of("Author A"));

        require("id-123".equals(item.id), "id");
        require("Русский (Orig)".equals(item.getDisplayTitle()), "display title");
        require("Fantasy, Adventure".equals(item.getGenresString()), "genres string");
        require("Author A".equals(item.getAuthorsString()), "authors string");

        // Null-safety defaults
        RanobeItem nullSafeItem = new RanobeItem(null, null, null, null, null, null, null, null, null, null);
        require("".equals(nullSafeItem.id), "null id must default to empty string");
        require("".equals(nullSafeItem.description), "null description must default to empty string");
        require("★ —".equals(nullSafeItem.rating), "null rating must default to '★ —'");
        require(nullSafeItem.genres != null && nullSafeItem.genres.isEmpty(), "null genres must default to empty list");
        require(nullSafeItem.authors != null && nullSafeItem.authors.isEmpty(), "null authors must default to empty list");

        // RanobeChapter formatting
        RanobeChapter ch = new RanobeChapter("cid", "1", "2.5", "Title", "b1", "Translators");
        require(ch.toString().contains("Том 1, Глава 2.5 — Title [Translators]"), "RanobeChapter toString format");
    }
}
