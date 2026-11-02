package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.service.DictionaryService;
import com.luminaread.service.IDictionaryService;
import com.luminaread.service.ITtsService;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.OnlineCatalogService;
import com.luminaread.service.TtsService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.io.File;
import javax.imageio.ImageIO;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.List;

public class MainWindow extends JFrame {
    private final MangaPanel mangaPanel;
    private final RanobePanel ranobePanel;
    private static final String VIEW_LIBRARY = "LIBRARY";
    private static final String VIEW_READER = "READER";
    private static final String VIEW_CATALOG = "CATALOG";
    private static final String VIEW_RANOBE = "RANOBE";
    private static final String VIEW_MANGA = "MANGA";
    private static final String VIEW_STATS = "STATS";
    private static final String VIEW_SETTINGS = "SETTINGS";

    private final LibraryStorageService storageService;
    private final JPanel sidebarPanel;
    private final CardLayout contentCardLayout;
    private final JPanel contentCardsPanel;

    private final LibraryPanel libraryPanel;
    private final ReaderPanel readerPanel;
    private final OnlineCatalogPanel onlineCatalogPanel;
    private final StatsAndNotesPanel statsAndNotesPanel;
    private final SettingsPanel settingsPanel;

    private String currentView = VIEW_LIBRARY;
    private ThemePalette currentTheme;

    public MainWindow(LibraryStorageService storageService,
                      OnlineCatalogService catalogService,
                      TtsService ttsService,
                      DictionaryService dictionaryService) {
        this(storageService, catalogService, (ITtsService) ttsService, (IDictionaryService) dictionaryService);
    }

    public MainWindow(LibraryStorageService storageService,
                      OnlineCatalogService catalogService,
                      ITtsService ttsService,
                      IDictionaryService dictionaryService) {
        super("LuminaRead — Минималистичная читалка и онлайн-каталог книг");
        this.storageService = storageService;
        this.currentTheme = ThemePalette.byId(storageService.getSettings().getThemeId());

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1280, 840);
        setMinimumSize(new Dimension(980, 660));
        setLocationRelativeTo(null);
        setupAppIcon();

        setLayout(new BorderLayout(0, 0));

        sidebarPanel = new JPanel();
        sidebarPanel.setLayout(new BoxLayout(sidebarPanel, BoxLayout.Y_AXIS));
        sidebarPanel.setPreferredSize(new Dimension(235, 800));
        sidebarPanel.setBorder(BorderFactory.createEmptyBorder(20, 16, 20, 16));

        contentCardLayout = new CardLayout();
        contentCardsPanel = new JPanel(contentCardLayout);

        libraryPanel = new LibraryPanel(
                storageService,
                this::openBookInReader,
                () -> switchView(VIEW_CATALOG)
        );

        readerPanel = new ReaderPanel(
                storageService,
                ttsService,
                dictionaryService,
                () -> {
                    libraryPanel.refreshBooksGrid();
                    switchView(VIEW_LIBRARY);
                },
                this::applyGlobalTheme
        );

        onlineCatalogPanel = new OnlineCatalogPanel(
                storageService,
                catalogService,
                book -> {
                    libraryPanel.refreshBooksGrid();
                    openBookInReader(book);
                }
        );

        statsAndNotesPanel = new StatsAndNotesPanel(
                storageService,
                (bookId, chapterIndex) -> {
                    for (Book b : storageService.getAllBooks()) {
                        if (b.getId().equals(bookId)) {
                            openBookInReader(b);
                            readerPanel.goToChapterAndPage(chapterIndex, 0);
                            break;
                        }
                    }
                }
        );

        settingsPanel = new SettingsPanel(
                storageService,
                catalogService,
                ttsService,
                this::applyGlobalTheme,
                () -> readerPanel.applyTheme(this.currentTheme),
                book -> {
                    libraryPanel.refreshBooksGrid();
                    openBookInReader(book);
                }
        );

        mangaPanel = new MangaPanel(sidebarVisible -> {
            sidebarPanel.setVisible(sidebarVisible);
            sidebarPanel.revalidate();
            sidebarPanel.repaint();
        });

        ranobePanel = new RanobePanel(sidebarVisible -> {
            sidebarPanel.setVisible(sidebarVisible);
            sidebarPanel.revalidate();
            sidebarPanel.repaint();
        });

        contentCardsPanel.add(libraryPanel, VIEW_LIBRARY);
        contentCardsPanel.add(readerPanel, VIEW_READER);
        contentCardsPanel.add(onlineCatalogPanel, VIEW_CATALOG);
        contentCardsPanel.add(ranobePanel, VIEW_RANOBE);
        contentCardsPanel.add(mangaPanel, VIEW_MANGA);
        contentCardsPanel.add(statsAndNotesPanel, VIEW_STATS);
        contentCardsPanel.add(settingsPanel, VIEW_SETTINGS);

        add(sidebarPanel, BorderLayout.WEST);
        add(contentCardsPanel, BorderLayout.CENTER);

        setupNativeMenuBar();
        setupDragAndDropImport();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                readerPanel.stopActiveFeatures();
                storageService.saveState();
            }
        });

        applyGlobalTheme(currentTheme);
    }

    private void rebuildSidebar() {
        sidebarPanel.removeAll();

        JLabel brandTitle = new JLabel("LuminaRead");
        brandTitle.setFont(ModernUiFactory.serifFont(Font.BOLD, 24));
        brandTitle.setForeground(currentTheme.getAccent());
        brandTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel brandSub = new JLabel("Reader Pro Edition");
        brandSub.setFont(ModernUiFactory.uiFont(Font.PLAIN, 12));
        brandSub.setForeground(currentTheme.getTextSecondary());
        brandSub.setAlignmentX(Component.LEFT_ALIGNMENT);

        sidebarPanel.add(brandTitle);
        sidebarPanel.add(Box.createVerticalStrut(2));
        sidebarPanel.add(brandSub);
        sidebarPanel.add(Box.createVerticalStrut(24));

        addNavButton("Моя библиотека", VIEW_LIBRARY);
        sidebarPanel.add(Box.createVerticalStrut(8));
        addNavButton("Чтение книги", VIEW_READER);
        sidebarPanel.add(Box.createVerticalStrut(8));
        addNavButton("Скачать книги", VIEW_CATALOG);
        sidebarPanel.add(Box.createVerticalStrut(8));
        addNavButton("Ранобэ и Новеллы", VIEW_RANOBE);
        sidebarPanel.add(Box.createVerticalStrut(8));
        addNavButton("Манга и Манхва", VIEW_MANGA);
        sidebarPanel.add(Box.createVerticalStrut(8));
        addNavButton("Цитаты и Статистика", VIEW_STATS);
        sidebarPanel.add(Box.createVerticalStrut(8));
        addNavButton("Настройки и Загрузки", VIEW_SETTINGS);

        sidebarPanel.add(Box.createVerticalGlue());

        JLabel tipLbl = new JLabel("<html><div style='width:185px;'>"
                + "<b>Совет:</b> перетащите любой файл <i>.fb2, .epub, .pdf, .txt, .md, .cbz</i> прямо в окно приложения для быстрого открытия."
                + "</div></html>");
        tipLbl.setFont(ModernUiFactory.uiFont(Font.PLAIN, 11));
        tipLbl.setForeground(currentTheme.getTextSecondary());
        tipLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebarPanel.add(tipLbl);

        sidebarPanel.revalidate();
        sidebarPanel.repaint();
    }

    private void addNavButton(String title, String viewKey) {
        boolean active = viewKey.equals(currentView);
        JButton btn = active
                ? ModernUiFactory.createPrimaryButton(title, currentTheme)
                : ModernUiFactory.createFlatButton(title, currentTheme);
        btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        btn.setMaximumSize(new Dimension(202, 38));
        btn.addActionListener(e -> switchView(viewKey));
        sidebarPanel.add(btn);
    }

    public void switchView(String viewKey) {
        this.currentView = viewKey;
        if (!VIEW_READER.equals(viewKey)) {
            readerPanel.stopActiveFeatures();
        }
        if (VIEW_LIBRARY.equals(viewKey)) {
            libraryPanel.refreshBooksGrid();
        } else if (VIEW_STATS.equals(viewKey)) {
            statsAndNotesPanel.refreshData();
        } else if (VIEW_SETTINGS.equals(viewKey)) {
            settingsPanel.refreshDownloadsSection();
        }
        sidebarPanel.setVisible(!VIEW_READER.equals(viewKey));
        contentCardLayout.show(contentCardsPanel, viewKey);
        rebuildSidebar();
    }

    public void openBookInReader(Book book) {
        readerPanel.openBook(book);
        switchView(VIEW_READER);
    }

    public void importBookFromSystem(File file) {
        if (file != null && file.isFile()) libraryPanel.importFiles(new File[]{file});
    }

    private void setupNativeMenuBar() {
        int shortcutMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("Файл");
        JMenuItem openItem = new JMenuItem("Открыть книгу / файл...");
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, shortcutMask));
        openItem.addActionListener(e -> libraryPanel.promptImportFiles());

        JMenuItem catalogItem = new JMenuItem("Скачать книги онлайн...");
        catalogItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_D, shortcutMask));
        catalogItem.addActionListener(e -> switchView(VIEW_CATALOG));

        JMenuItem settingsItem = new JMenuItem("Настройки и Загрузки...");
        settingsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, shortcutMask));
        settingsItem.addActionListener(e -> switchView(VIEW_SETTINGS));

        JMenuItem libraryItem = new JMenuItem("Моя библиотека");
        libraryItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_L, shortcutMask));
        libraryItem.addActionListener(e -> switchView(VIEW_LIBRARY));

        fileMenu.add(openItem);
        fileMenu.add(catalogItem);

        JMenuItem ranobeItem = new JMenuItem("Ранобэ и Новеллы...");
        ranobeItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, shortcutMask));
        ranobeItem.addActionListener(e -> switchView(VIEW_RANOBE));
        fileMenu.add(ranobeItem);

        JMenuItem mangaItem = new JMenuItem("Манга и Манхва...");
        mangaItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_M, shortcutMask));
        mangaItem.addActionListener(e -> switchView(VIEW_MANGA));
        fileMenu.add(mangaItem);

        fileMenu.add(settingsItem);
        fileMenu.add(libraryItem);

        JMenu viewMenu = new JMenu("Вид и Тема");
        for (ThemePalette palette : ThemePalette.PRESETS.values()) {
            JMenuItem themeItem = new JMenuItem("Тема: " + palette.getDisplayName());
            themeItem.addActionListener(e -> {
                storageService.getSettings().setThemeId(palette.getId());
                storageService.saveState();
                applyGlobalTheme(palette);
            });
            viewMenu.add(themeItem);
        }
        viewMenu.addSeparator();
        JMenuItem typographyItem = new JMenuItem("Открыть все настройки и загрузки...");
        typographyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, shortcutMask));
        typographyItem.addActionListener(e -> switchView(VIEW_SETTINGS));
        viewMenu.add(typographyItem);

        JMenuItem zenItem = new JMenuItem("Полноэкранный Дзен-режим чтения");
        zenItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0));
        zenItem.addActionListener(e -> {
            if (VIEW_READER.equals(currentView)) {
                readerPanel.toggleZenMode();
            }
        });
        viewMenu.add(zenItem);

        menuBar.add(fileMenu);
        menuBar.add(viewMenu);
        setJMenuBar(menuBar);
    }

    @SuppressWarnings("unchecked")
    private void setupDragAndDropImport() {
        new DropTarget(this, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    Object transferableData = dtde.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    if (transferableData instanceof List<?> fileList) {
                        List<File> bookFiles = new java.util.ArrayList<>();
                        for (Object obj : fileList) {
                            if (obj instanceof File f && f.isFile()) {
                                bookFiles.add(f);
                            }
                        }
                        if (!bookFiles.isEmpty()) libraryPanel.importFiles(bookFiles.toArray(new File[0]));
                        dtde.dropComplete(true);
                    } else dtde.dropComplete(false);
                } catch (Exception ignored) {
                    try { dtde.dropComplete(false); } catch (Exception ignoredAgain) {}
                }
            }
        }, true);
    }

    private void setupAppIcon() {
        try {
            Image icon = null;
            var res = getClass().getResource("/icons/app_icon.png");
            if (res != null) {
                icon = ImageIO.read(res);
            }
            if (icon == null) {
                File iconFile = new File("src/main/resources/icons/app_icon.png");
                if (iconFile.exists()) {
                    icon = ImageIO.read(iconFile);
                }
            }
            if (icon != null) {
                setIconImage(icon);
                setSafeTaskbarIcon(icon);
            }
        } catch (Throwable ignored) {}
    }

    public static boolean setSafeTaskbarIcon(Image icon) {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless()) {
                return false;
            }
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar != null && taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(icon);
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public void applyGlobalTheme(ThemePalette theme) {
        this.currentTheme = theme;
        sidebarPanel.setBackground(theme.getSurface());
        sidebarPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, theme.getBorder()),
                BorderFactory.createEmptyBorder(20, 16, 20, 16)
        ));
        libraryPanel.applyTheme(theme);
        readerPanel.applyTheme(theme);
        onlineCatalogPanel.applyTheme(theme);
        statsAndNotesPanel.applyTheme(theme);
        settingsPanel.applyTheme(theme);
        ranobePanel.applyTheme(theme);
        mangaPanel.applyTheme(theme);
        rebuildSidebar();
    }

}
