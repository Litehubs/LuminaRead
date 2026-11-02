package com.luminaread;

import com.luminaread.model.BookContent;
import com.luminaread.model.Chapter;
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
import com.luminaread.service.ITtsService;
import com.luminaread.service.TtsService;
import com.luminaread.service.tts.LinuxTtsEngine;
import com.luminaread.service.tts.MacTtsEngine;
import com.luminaread.service.tts.TtsEngine;
import com.luminaread.service.tts.WindowsTtsEngine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class ParserAndServiceRefactorTest {

    public static void main(String[] args) throws Exception {
        System.out.println("============================================================");
        System.out.println("  RUNNING SOLID & UB-MODE REFACTORING VERIFICATION SUITE");
        System.out.println("============================================================");

        int passed = 0;

        testBookParserImplementations();
        System.out.println("  [PASS] Test 1: BookParser implementations supports() & getSupportedFormat()");
        passed++;

        testBookParserRegistryAndOcpExtension();
        System.out.println("  [PASS] Test 2: BookParserRegistry OCP extensibility & fallback handling");
        passed++;

        testBookParserFactoryBackwardCompatibility();
        System.out.println("  [PASS] Test 3: BookParserFactory static facade backward compatibility");
        passed++;

        testTtsEngineStrategiesAndDIP();
        System.out.println("  [PASS] Test 4: TtsEngine strategies (Mac/Win/Linux) & TtsService DIP injection");
        passed++;

        testDictionaryServiceDIP();
        System.out.println("  [PASS] Test 5: IDictionaryService abstraction & DictionaryService implementation");
        passed++;

        System.out.println("============================================================");
        System.out.println("  SUMMARY: " + passed + " passed, 0 failed");
        System.out.println("============================================================");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + message);
        }
    }

    private static void testBookParserImplementations() {
        Fb2Parser fb2 = new Fb2Parser();
        require("FB2".equals(fb2.getSupportedFormat()), "Fb2Parser format must be FB2");
        require(fb2.supports(new File("book.fb2"), null), "Fb2Parser must support .fb2 files");
        require(fb2.supports(new File("book.fb2.zip"), null), "Fb2Parser must support .fb2.zip files");
        require(fb2.supports(null, "FB2"), "Fb2Parser must support FB2 format hint");
        require(!fb2.supports(new File("book.epub"), "EPUB"), "Fb2Parser must not support EPUB");

        EpubParser epub = new EpubParser();
        require("EPUB".equals(epub.getSupportedFormat()), "EpubParser format must be EPUB");
        require(epub.supports(new File("novel.epub"), null), "EpubParser must support .epub files");
        require(epub.supports(null, "EPUB"), "EpubParser must support EPUB hint");
        require(!epub.supports(new File("doc.pdf"), "PDF"), "EpubParser must not support PDF");

        PdfParser pdf = new PdfParser();
        require("PDF".equals(pdf.getSupportedFormat()), "PdfParser format must be PDF");
        require(pdf.supports(new File("manual.pdf"), null), "PdfParser must support .pdf files");
        require(pdf.supports(null, "PDF"), "PdfParser must support PDF hint");
        require(!pdf.supports(new File("comic.cbz"), "CBZ"), "PdfParser must not support CBZ");

        CbzParser cbz = new CbzParser();
        require("CBZ".equals(cbz.getSupportedFormat()), "CbzParser format must be CBZ");
        require(cbz.supports(new File("manga.cbz"), null), "CbzParser must support .cbz files");
        require(cbz.supports(null, "CBZ"), "CbzParser must support CBZ hint");

        TxtMarkdownParser txt = new TxtMarkdownParser();
        require("TXT".equals(txt.getSupportedFormat()), "TxtMarkdownParser format must be TXT");
        require(txt.supports(new File("notes.txt"), null), "TxtMarkdownParser must support .txt");
        require(txt.supports(new File("readme.md"), null), "TxtMarkdownParser must support .md");
        require(txt.supports(new File("index.html"), null), "TxtMarkdownParser must support .html");
        require(txt.supports(new File("doc.rtf"), null), "TxtMarkdownParser must support .rtf");
        require(txt.supports(null, "MD"), "TxtMarkdownParser must support MD hint");
    }

    private static void testBookParserRegistryAndOcpExtension() throws Exception {
        BookParserRegistry registry = new BookParserRegistry();
        List<BookParser> defaults = registry.getRegisteredParsers();
        require(defaults.size() >= 5, "Default registry must have at least 5 parsers registered");
        require(registry.getFallbackParser() instanceof TxtMarkdownParser, "Default fallback parser must be TxtMarkdownParser");

        // Verify findParser
        Optional<BookParser> foundFb2 = registry.findParser(new File("test.fb2"), "FB2");
        require(foundFb2.isPresent() && foundFb2.get() instanceof Fb2Parser, "Registry should find Fb2Parser");

        Optional<BookParser> foundPdf = registry.findParser(new File("test.pdf"), "PDF");
        require(foundPdf.isPresent() && foundPdf.get() instanceof PdfParser, "Registry should find PdfParser");

        // OCP Demonstration: Register a new custom parser without modifying existing classes
        AtomicBoolean customParsed = new AtomicBoolean(false);
        BookParser customMobiParser = new BookParser() {
            @Override
            public BookContent parse(File file) {
                customParsed.set(true);
                BookContent content = new BookContent();
                content.setTitle("Custom Mobi");
                Chapter ch = new Chapter(0, "Mobi Ch 1", "<p>Custom mobi</p>", "Custom parsed mobi content");
                content.addChapter(ch);
                return content;
            }

            @Override
            public boolean supports(File file, String formatHint) {
                return (formatHint != null && formatHint.equalsIgnoreCase("MOBI"))
                        || (file != null && file.getName().toLowerCase().endsWith(".mobi"));
            }

            @Override
            public String getSupportedFormat() {
                return "MOBI";
            }
        };

        File mockMobi = new File("great_novel.mobi");
        // Before registration, format detection returns default TXT
        require("TXT".equals(registry.detectFormatLabel(mockMobi)), "Unregistered format should default to TXT");

        registry.registerParser(customMobiParser);

        // After registration, format detection detects MOBI (Open for extension!)
        require("MOBI".equals(registry.detectFormatLabel(mockMobi)), "Registered format should detect MOBI");
        Optional<BookParser> foundCustom = registry.findParser(mockMobi, "MOBI");
        require(foundCustom.isPresent() && foundCustom.get() == customMobiParser, "Registry should resolve custom parser");

        BookContent parsedCustom = registry.parseBook(mockMobi);
        require(customParsed.get(), "Custom parser should be executed by registry.parseBook");
        require("Custom Mobi".equals(parsedCustom.getTitle()), "Parsed content should match custom parser output");

        // Unregister
        require(registry.unregisterParser(customMobiParser), "Unregister should succeed");
        require("TXT".equals(registry.detectFormatLabel(mockMobi)), "After unregistration, format detection should revert to TXT");
    }

    private static void testBookParserFactoryBackwardCompatibility() throws Exception {
        Path tempTxt = Files.createTempFile("luminaread-factory-test-", ".txt");
        try {
            Files.writeString(tempTxt, "Line 1\nLine 2\nLine 3");
            String format = BookParserFactory.detectFormatLabel(tempTxt.toFile());
            require("TXT".equals(format), "BookParserFactory.detectFormatLabel must detect TXT");

            BookContent content = BookParserFactory.parseBook(tempTxt.toFile());
            require(content != null, "BookParserFactory.parseBook must return content");
            require(!content.getChapters().isEmpty(), "BookParserFactory.parseBook must have chapters");
            require(content.getChapters().get(0).getPlainText().contains("Line 2"), "Content must match");
        } finally {
            Files.deleteIfExists(tempTxt);
        }
    }

    private static void testTtsEngineStrategiesAndDIP() {
        // Verify interfaces
        require(ITtsService.class.isAssignableFrom(TtsService.class), "TtsService must implement ITtsService");
        require(TtsEngine.class.isAssignableFrom(MacTtsEngine.class), "MacTtsEngine must implement TtsEngine");
        require(TtsEngine.class.isAssignableFrom(WindowsTtsEngine.class), "WindowsTtsEngine must implement TtsEngine");
        require(TtsEngine.class.isAssignableFrom(LinuxTtsEngine.class), "LinuxTtsEngine must implement TtsEngine");

        // Verify MacTtsEngine
        MacTtsEngine macEngine = new MacTtsEngine();
        ProcessBuilder macPb = macEngine.buildProcess("Hello Mac", 200);
        require(macPb.command().contains("say"), "Mac command must invoke 'say'");
        require(macPb.command().contains("200"), "Mac command must include rate 200");
        require(macPb.command().contains("Hello Mac"), "Mac command must include text");

        // Verify WindowsTtsEngine
        WindowsTtsEngine winEngine = new WindowsTtsEngine();
        ProcessBuilder winPb = winEngine.buildProcess("Hello 'Windows'", 175);
        require("powershell.exe".equalsIgnoreCase(winPb.command().get(0)), "Win command must invoke powershell.exe");
        String script = winPb.command().get(winPb.command().size() - 1);
        require(script.contains("''Windows''"), "Win engine must escape single quotes by doubling");

        // Verify LinuxTtsEngine
        LinuxTtsEngine linuxEngine = new LinuxTtsEngine();
        ProcessBuilder espeakPb = linuxEngine.buildProcess("Hello Linux", 180, false);
        require("espeak".equals(espeakPb.command().get(0)), "Linux engine should invoke espeak when false");
        ProcessBuilder spdPb = linuxEngine.buildProcess("Hello Linux", 180, true);
        require("spd-say".equals(spdPb.command().get(0)), "Linux engine should invoke spd-say when true");

        // Verify DIP constructor injection in TtsService
        AtomicBoolean customEngineInvoked = new AtomicBoolean(false);
        TtsEngine mockEngine = new TtsEngine() {
            @Override
            public ProcessBuilder buildProcess(String textToSpeak, int rateWpm) {
                customEngineInvoked.set(true);
                return new ProcessBuilder("echo", textToSpeak);
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };

        ITtsService serviceWithMock = new TtsService(mockEngine);
        require(serviceWithMock instanceof TtsService, "Service must be instance of TtsService");
        TtsService concrete = (TtsService) serviceWithMock;
        require(concrete.getEngine() == mockEngine, "TtsService must hold injected mock engine");

        ProcessBuilder customPb = concrete.createProcessBuilder("Test Mock", 150);
        require(customEngineInvoked.get(), "createProcessBuilder must delegate to injected TtsEngine");
        require(customPb.command().contains("Test Mock"), "Command must contain passed text");

        // Verify resolveEngineForOs
        require(TtsService.resolveEngineForOs("Mac OS X") instanceof MacTtsEngine, "Mac OS should resolve MacTtsEngine");
        require(TtsService.resolveEngineForOs("Windows 10") instanceof WindowsTtsEngine, "Windows should resolve WindowsTtsEngine");
        require(TtsService.resolveEngineForOs("Linux") instanceof LinuxTtsEngine, "Linux should resolve LinuxTtsEngine");
    }

    private static void testDictionaryServiceDIP() {
        require(IDictionaryService.class.isAssignableFrom(DictionaryService.class),
                "DictionaryService must implement IDictionaryService");

        IDictionaryService service = new DictionaryService();
        String emptyResult = service.lookupAndTranslate("");
        require(emptyResult.contains("Выделите слово"), "Blank text should return helpful instruction");

        String nullResult = service.lookupAndTranslate(null);
        require(nullResult.contains("Выделите слово"), "Null text should return helpful instruction");

        // Verify custom IDictionaryService substitution (LSP/DIP)
        IDictionaryService mockDict = text -> "Mock translation for: " + text;
        require("Mock translation for: book".equals(mockDict.lookupAndTranslate("book")),
                "Custom IDictionaryService must be substitutable");
    }
}
