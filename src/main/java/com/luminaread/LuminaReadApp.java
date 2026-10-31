package com.luminaread;

import com.luminaread.model.ReaderSettings;
import com.luminaread.service.DictionaryService;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.OnlineCatalogService;
import com.luminaread.service.TtsService;
import com.luminaread.ui.MainWindow;
import com.luminaread.ui.ModernUiFactory;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

public class LuminaReadApp {

    public static void main(String[] args) {
        if (!"true".equals(System.getProperty("luminaread.rebuilt"))) {
            if (tryAutoRebuildIfSourcesModified(args)) {
                System.exit(0);
                return;
            }
        }

        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", "LuminaRead");
        System.setProperty("apple.awt.application.appearance", "system");
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        ModernUiFactory.installGlobalUiFonts();

        LibraryStorageService storageService = new LibraryStorageService();
        if (storageService.getSettings().getTextAlignment() == ReaderSettings.TextAlignment.JUSTIFY) {
            storageService.getSettings().setTextAlignment(ReaderSettings.TextAlignment.LEFT);
            storageService.saveState();
        }
        ensureStarterBooksExist(storageService);

        File startupFile = args != null && args.length > 0 ? new File(args[0]) : null;

        OnlineCatalogService catalogService = new OnlineCatalogService();
        TtsService ttsService = new TtsService();
        DictionaryService dictionaryService = new DictionaryService();

        SwingUtilities.invokeLater(() -> {
            MainWindow window = new MainWindow(storageService, catalogService, ttsService, dictionaryService);
            window.setVisible(true);
            if (startupFile != null && startupFile.isFile()) window.importBookFromSystem(startupFile);
        });
    }

    private static boolean tryAutoRebuildIfSourcesModified(String[] args) {
        try {
            Path projectDir = Path.of(System.getProperty("user.dir"));
            Path srcDir = projectDir.resolve("src/main/java");
            if (!Files.isDirectory(srcDir)) {
                return false;
            }

            Path jarFile = projectDir.resolve("LuminaRead.jar");
            long jarTime = Files.exists(jarFile) ? Files.getLastModifiedTime(jarFile).toMillis() : 0L;

            List<String> javaFiles = new ArrayList<>();
            long newestSourceTime = 0L;
            try (Stream<Path> walk = Files.walk(srcDir)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                    javaFiles.add(p.toAbsolutePath().toString());
                    long t = Files.getLastModifiedTime(p).toMillis();
                    if (t > newestSourceTime) {
                        newestSourceTime = t;
                    }
                }
            }

            if (javaFiles.isEmpty() || newestSourceTime <= jarTime) {
                return false;
            }

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                return false;
            }

            Path classesDir = projectDir.resolve("build/classes");
            if (Files.exists(classesDir)) {
                try (Stream<Path> walk = Files.walk(classesDir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
                }
            }
            Files.createDirectories(classesDir);

            List<String> compileArgs = new ArrayList<>();
            compileArgs.add("-encoding");
            compileArgs.add("UTF-8");

            compileArgs.add("--release");
            compileArgs.add("17");
            compileArgs.add("-nowarn");
            compileArgs.add("-d");
            compileArgs.add(classesDir.toAbsolutePath().toString());
            compileArgs.addAll(javaFiles);

            int result = compiler.run(null, null, null, compileArgs.toArray(new String[0]));
            if (result != 0) {
                return false;
            }

            Files.deleteIfExists(jarFile);
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.luminaread.LuminaReadApp");

            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile.toFile()), manifest);
                 Stream<Path> walk = Files.walk(classesDir)) {
                for (Path p : walk.filter(Files::isRegularFile).toList()) {
                    String entryName = classesDir.relativize(p).toString().replace('\\', '/');
                    JarEntry entry = new JarEntry(entryName);
                    jos.putNextEntry(entry);
                    try (FileInputStream fis = new FileInputStream(p.toFile())) {
                        fis.transferTo(jos);
                    }
                    jos.closeEntry();
                }
            }

            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            cmd.add("-Dluminaread.rebuilt=true");
            cmd.add("-Dawt.useSystemAAFontSettings=on");
            cmd.add("-Dswing.aatext=true");
            cmd.add("-Dfile.encoding=UTF-8");
            if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
                cmd.add("-Dsun.java2d.metal=false");
                cmd.add("-Dapple.laf.useScreenMenuBar=true");
            }
            cmd.add("-cp");
            cmd.add(classesDir.toAbsolutePath().toString());
            cmd.add("com.luminaread.LuminaReadApp");
            if (args != null) {
                for (String a : args) cmd.add(a);
            }

            new ProcessBuilder(cmd).directory(projectDir.toFile()).start();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void ensureStarterBooksExist(LibraryStorageService storageService) {
        if (!storageService.getAllBooks().isEmpty()) {
            return;
        }
        try {
            Path booksDir = storageService.getBooksDir();
            Path sampleFb2 = booksDir.resolve("Белые_ночи_Достоевский_Демо.fb2");
            if (!Files.exists(sampleFb2)) {
                String fb2Xml = """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <FictionBook xmlns="http:
                          <description>
                            <title-info>
                              <genre>classic</genre>
                              <author>
                                <first-name>Фёдор</first-name>
                                <middle-name>Михайлович</middle-name>
                                <last-name>Достоевский</last-name>
                              </author>
                              <book-title>Белые ночи (Сентиментальный роман)</book-title>
                              <annotation>
                                <p>Лирическая повесть Ф. М. Достоевского о петербургском мечтателе, одиночестве и внезапной встрече в прозрачных сумерках белых ночей.</p>
                              </annotation>
                              <lang>ru</lang>
                              <sequence name="Русская классика" number="1"/>
                            </title-info>
                          </description>
                          <body>
                            <section>
                              <title><p>Ночь первая</p></title>
                              <epigraph>
                                <p>…Иль был он создан для того, чтобы побыть хотя мгновенье в соседстве сердца твоего?..</p>
                                <text-author>И. С. Тургенев</text-author>
                              </epigraph>
                              <p>Была чудная ночь, такая ночь, которая разве только и может быть тогда, когда мы молоды, любезный читатель. Небо было такое звездное, такое светлое небо, что, взглянув на него, невольно нужно было спросить себя: неужели же могут жить под таким небом разные сердитые и капризные люди? Это тоже молодой вопрос, любезный читатель, очень молодой, но пошли его вам господь чаще на душу!..</p>
                              <p>Говоря о капризных и разных сердитых господах, я не мог не припомнить и своего благонравного поведения во весь этот день. С самого утра меня стала мучить какая-то удивительная тоска. Мне вдруг показалось, что меня, одинокого, все покидают и что все от меня отступаются.</p>
                              <p>Я ходил по Петербургу долго, и наконец очутился на набережной канала. Тут не было ни души; в этот час и в этой части города всегда пустынно. Я шел и пел вполголоса, потому что, когда я счастлив, я непременно мурлыкаю про себя, как и всякий счастливый человек, у которого нет ни друзей, ни добрых знакомых.</p>
                            </section>
                            <section>
                              <title><p>Ночь вторая</p></title>
                              <p>— Ну вот, вы и выжили! — сказала она мне, смеясь и жмя мне обе руки. — А я уж здесь два часа дожидаюсь; вы не знаете, что со мной было целый этот день!</p>
                              <p>— Знаю, знаю... но к делу. Вы знаете ли, зачем я теперь пришел? Ведь не вздор же говорить, как вчера! Видите ли: нам надо вперед умнее поступать. Я вчера много об этом думал.</p>
                              <p>— В чем же, в чем это умнее? Ну, со своей стороны и я готова; но, по правде, со мной в жизнь ничего умнее нынешнего не случалось, право так!</p>
                            </section>
                          </body>
                        </FictionBook>
                        """;
                Files.writeString(sampleFb2, fb2Xml, StandardCharsets.UTF_8);
                storageService.importBookFile(sampleFb2.toFile(), false);
            }

            Path sampleGuide = booksDir.resolve("Руководство_LuminaRead_Pro.md");
            if (!Files.exists(sampleGuide)) {
                String mdText = """
                        Название: Полное руководство по возможностям LuminaRead Pro
                        Автор: Команда LuminaRead

                        # Глава 1. Быстрый старт и поддерживаемые форматы

                        Добро пожаловать в **LuminaRead** — кроссплатформенную настольную читалку для **macOS (MacBook)**, **Windows** и **Linux**, созданную по образцу **Moon+ Reader Pro**.

                        Приложение поддерживает прямое открытие и импорт следующих форматов:

                        > **FB2 и FB2.ZIP** (включая книги в кодировках UTF-8, Windows-1251 и KOI8-R с обложками и сносками), **EPUB 2/3**, **PDF**, **TXT**, **Markdown (.md)**, **HTML** и архивы комиксов **CBZ**.

                        Вы можете просто перетащить любой файл книги мышью в окно приложения или нажать **Cmd+O** (на MacBook) / **Ctrl+O** (на Windows и Linux).

                        # Глава 2. Функции чтения в стиле Moon+ Reader Pro

                        В режиме чтения вам доступны все профессиональные инструменты:

                        **1. Настройка типографики и 6 тем оформления (кнопка «Aa Вид и Тема»):** переключайтесь между темами *Чистый свет*, *Классическая сепия*, *Глубокая ночь (AMOLED)*, *Сумерки (Nord)*, *Электронные чернила (E-Ink)* и *Мятный комфорт*. Настраивайте шрифт, размер, межстрочный интервал, боковые поля, выравнивание и двухколоночный книжный разворот.

                        **2. Управление жестами и 9 зон клика:** клик по левой части страницы листает назад, по правой — вперед, клик в центр экрана включает полноэкранный **Дзен-режим** (также вызывается клавишей **F11**). Потяните мышью вверх или вниз вдоль **левого края страницы**, чтобы плавно изменить яркость экрана!

                        **3. Автопрокрутка и Озвучка книги (TTS):** запустите плавный автоскролл с настраиваемой скоростью или включите голосовое чтение книги (на MacBook используется нативный синтезатор речи macOS) со встроенным таймером сна на 15, 30 или 60 минут.

                        # Глава 3. Скачивание книг онлайн и OPDS-каталоги

                        В разделе **«Скачать книги»** встроен многопоточный поисковик и загрузчик книг из крупнейших бесплатных каталогов:

                        **• Flibusta OPDS:** поиск и скачивание книг на русском и иностранных языках напрямую через протокол OPDS.

                        **• Project Gutenberg (Gutendex API):** более 70 000 классических произведений в форматах EPUB, TXT и HTML.

                        **• Open Library & Internet Archive:** миллионы оцифрованных изданий с прямой загрузкой файлов.

                        **• Любые свои OPDS-каталоги и прямые ссылки:** вставляйте ссылку на любой OPDS-каталог или прямую ссылку на файл книги для мгновенного скачивания в библиотеку.
                        """;
                Files.writeString(sampleGuide, mdText, StandardCharsets.UTF_8);
                storageService.importBookFile(sampleGuide.toFile(), false);
            }
        } catch (Exception ignored) {
        }
    }
}
