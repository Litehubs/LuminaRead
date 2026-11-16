package com.luminaread.ui;

import com.luminaread.model.manga.MangaChapter;
import com.luminaread.model.manga.MangaItem;
import com.luminaread.model.manga.MangaSearchResult;
import com.luminaread.service.IMangaService;
import com.luminaread.service.YandexAuthService.YandexUser;
import com.luminaread.service.YandexAuthService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class MangaPanel extends JPanel {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(MangaPanel.class.getName());

    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_FIT_WIDTH = 2;

    public static final int MODE_SCROLL = 0;
    public static final int MODE_PAGED = 1;

    private final IMangaService mangaService;
    private final YandexAuthService yandexService = YandexAuthService.getInstance();
    private final MangaPageLoader pageLoader;
    private final AtomicInteger currentLoadToken = new AtomicInteger(0);
    private final PanelWorkers workers = new PanelWorkers(6);
    private volatile boolean disposed;
    private final Consumer<YandexUser> authListener = user -> SwingUtilities.invokeLater(() -> {
        if (!disposed) updateAuthButtonState();
    });
    private final LatestSwingRequest<MangaSearchResult> mangaSearch = new LatestSwingRequest<>();
    private final LatestSwingRequest<ImageIcon> detailCoverRequest = new LatestSwingRequest<>();
    private final LatestSwingRequest<List<MangaChapter>> detailChaptersRequest = new LatestSwingRequest<>();
    private final LatestSwingRequest<String> detailDescriptionRequest = new LatestSwingRequest<>();
    private final Consumer<Boolean> onToggleSidebar;

    private final MotionCardPanel mainContainer = new MotionCardPanel();

    private final Map<String, MangaItem> favoriteManga = new LinkedHashMap<>();
    private final Map<String, MangaItem> historyManga = new LinkedHashMap<>();
    private final Map<String, String> lastReadChapterMap = new LinkedHashMap<>();
    private final Map<String, String> readerPrefs = new LinkedHashMap<>();

    private int readingMode = MODE_SCROLL;
    private int alignmentMode = ALIGN_CENTER;
    private int zoomWidth = 860;
    private int pageGap = 0;
    private double dimmerOpacity = 0.0;
    private Color readerBgColor = new Color(14, 15, 20);
    private boolean autoHideSidebar = false;
    private int autoScrollSpeed = 45;

    private final JTextField searchField = new JTextField();
    private final JComboBox<String> sourceSelector = new JComboBox<>(new String[]{
            "Все источники", "MangaLib (RU)", "Remanga (RU)", "MangaDex (EN/RU)"
    });
    private final JButton clearHistoryButton = new JButton("Очистить историю");
    private final JComboBox<String> categoryTabs = new JComboBox<>(new String[]{
            "🔥 Популярное сейчас", "⭐ Избранная манга", "🕒 История чтения манги"
    });
    private final SmoothScrollUtil.ResponsiveListPanel gridContainer = new SmoothScrollUtil.ResponsiveListPanel(new GridLayout(0, 2, 14, 14));
    private final JLabel statusLabel = new JLabel("Готово к поиску манги и манхвы");
    private final JButton authBtn = new JButton("🟡 Войти через Яндекс ID");

    private MangaItem currentManga;
    private final JLabel detailTitle = new JLabel();
    private final JLabel detailBadges = new JLabel();
    private final JTextArea detailDesc = new JTextArea();
    private final JLabel detailCover = new JLabel();
    private final JButton favToggleBtn = new JButton("☆ В избранное манги");
    private final DefaultListModel<MangaChapter> chapterListModel = new DefaultListModel<>();
    private final JList<MangaChapter> chapterJList = new JList<>(chapterListModel);
    private final JLabel chapterStatus = new JLabel(" ");
    private final JComboBox<String> languageCombo = new JComboBox<>();
    private final JButton sortOrderBtn = new JButton("Порядок: 1 → N");
    private final MangaChapterList chapters = new MangaChapterList();
    private boolean updatingLanguage = false;

    private final MangaReaderCanvas readerCanvasPanel = new MangaReaderCanvas();
    private JScrollPane readerScrollPane;
    private final JLabel readerTitleLabel = new JLabel("Чтение");

    private final JButton btnModeScroll = new JButton("📜 Лента");
    private final JButton btnModePaged = new JButton("📖 Постранично");

    private final JButton prevPageBtn = new JButton("◀");
    private final JLabel pageCounterLabel = new JLabel("Стр. 1 / 1");
    private final JButton nextPageBtn = new JButton("▶");

    private final JButton autoScrollBtn = new JButton("▶ Автоскролл");
    private final JLabel scrollProgressLabel = new JLabel("0%");

    private final JButton settingsPopupBtn = new JButton("⚙️ Настройки");
    private final JButton zenToggleBtn = new JButton("⛶ Дзен");
    private final JButton prevChapterBtn = new JButton("⏮");
    private final JButton nextChapterBtn = new JButton("⏭");

    private boolean chapterLoading;
    private List<String> currentPages = new ArrayList<>();
    private int currentChapterIndex = 0;
    private int currentPageIndex = 0;
    private boolean isZenActive = false;
    private AutoScrollController autoScroll;

    private ThemePalette currentTheme = ThemePalette.byId("NIGHT_AMOLED");

    MangaPanel(Consumer<Boolean> onToggleSidebar, IMangaService mangaService, boolean initialSearch) {
        this.onToggleSidebar = onToggleSidebar;
        this.mangaService = java.util.Objects.requireNonNull(mangaService);
        this.pageLoader = new MangaPageLoader(workers, mangaService::loadImageCached,
                token -> !disposed && token == currentLoadToken.get());
        setLayout(new BorderLayout());
        setBackground(new Color(20, 22, 29));

        loadPreferences();

        mainContainer.addCard("EXPLORE", buildExploreView());
        mainContainer.addCard("DETAILS", buildDetailsView());
        mainContainer.addCard("READER", buildReaderView());

        add(mainContainer, BorderLayout.CENTER);

        yandexService.addAuthListener(authListener);
        updateAuthButtonState();

        if (initialSearch) performSearch("");
    }

    void setAnimationsEnabled(boolean enabled) {
        mainContainer.setAnimationsEnabled(enabled);
    }

    void cancelViewTransition() {
        mainContainer.cancelTransition();
    }

    public void disposeResources() {
        if (disposed) return;
        disposed = true;
        cancelViewTransition();
        yandexService.removeAuthListener(authListener);
        mangaSearch.cancel();
        cancelDetailRequests();
        currentLoadToken.incrementAndGet();
        workers.close();
        if (autoScroll != null) autoScroll.stop();
    }

    private void loadPreferences() {
        mangaService.loadMangaState(favoriteManga, historyManga, lastReadChapterMap, readerPrefs);
        readingMode = ReaderPreferenceValues.integer(readerPrefs, "readingMode", readingMode, MODE_SCROLL, MODE_PAGED);
        alignmentMode = ReaderPreferenceValues.integer(readerPrefs, "alignmentMode", alignmentMode, ALIGN_LEFT, ALIGN_FIT_WIDTH);
        zoomWidth = ReaderPreferenceValues.integer(readerPrefs, "zoomWidth", zoomWidth, 500, 1800);
        pageGap = ReaderPreferenceValues.integer(readerPrefs, "pageGap", pageGap, 0, 40);
        dimmerOpacity = ReaderPreferenceValues.integer(readerPrefs, "dimmerPercent", (int) (dimmerOpacity * 100), 0, 70) / 100.0;
        try { if (readerPrefs.containsKey("bgColor")) readerBgColor = new Color(Integer.parseInt(readerPrefs.get("bgColor"), 16)); }
        catch (NumberFormatException ex) { /* Keep the default color. */ }
        autoHideSidebar = ReaderPreferenceValues.bool(readerPrefs, "autoHideSidebar", autoHideSidebar);
        autoScrollSpeed = ReaderPreferenceValues.integer(readerPrefs, "autoScrollSpeed", autoScrollSpeed, 10, 180);
    }

    private void savePreferences() {
        readerPrefs.put("readingMode", String.valueOf(readingMode));
        readerPrefs.put("alignmentMode", String.valueOf(alignmentMode));
        readerPrefs.put("zoomWidth", String.valueOf(zoomWidth));
        readerPrefs.put("pageGap", String.valueOf(pageGap));
        readerPrefs.put("dimmerPercent", String.valueOf((int) Math.round(dimmerOpacity * 100)));
        readerPrefs.put("bgColor", String.format("%06x", readerBgColor.getRGB() & 0xFFFFFF));
        readerPrefs.put("autoHideSidebar", String.valueOf(autoHideSidebar));
        readerPrefs.put("autoScrollSpeed", String.valueOf(autoScrollSpeed));
        try {
            persistListState();
        } catch (java.io.UncheckedIOException | SecurityException ex) {
            UiPersistenceErrors.show(this, "Не удалось сохранить настройки и прогресс манги.", ex);
        }
    }

    private void persistListState() {
        mangaService.saveMangaState(favoriteManga, historyManga, lastReadChapterMap, readerPrefs);
    }

    private void clearHistory() {
        if (historyManga.isEmpty() || !CatalogListControls.confirmClear(this)) return;
        CatalogListControls.edit(this, historyManga, historyManga::clear, this::persistListState,
                this::onCategoryChanged);
    }

    private void updateAuthButtonState() {
        YandexUser ya = yandexService.getCurrentUser();
        if (ya != null && ya.isAuthenticated()) {
            authBtn.setText("🟡 " + ya.getDisplayTitle());
            authBtn.setBackground(new Color(255, 214, 0));
            authBtn.setForeground(new Color(20, 20, 20));
        } else {
            authBtn.setText("🟡 Войти через Яндекс ID");
            authBtn.setBackground(new Color(55, 60, 75));
            authBtn.setForeground(new Color(230, 235, 245));
        }
    }

    private JPanel buildExploreView() {
        authBtn.addActionListener(e -> showAuthDialog());
        updateAuthButtonState();
        categoryTabs.addActionListener(e -> onCategoryChanged());
        sourceSelector.addActionListener(e -> {
            if (categoryTabs.getSelectedIndex() == 0) performSearch(searchField.getText());
        });
        clearHistoryButton.setVisible(false);
        clearHistoryButton.addActionListener(e -> clearHistory());

        searchField.setToolTipText("Поиск манги, манхвы и маньхуа");
        searchField.addActionListener(e -> startSearchFromField());
        JButton find = new JButton("🔍 Найти мангу / манхву");
        find.setBackground(new Color(92, 107, 192));
        find.addActionListener(e -> startSearchFromField());

        JPanel filters = CatalogExploreView.filters(categoryTabs, sourceSelector, clearHistoryButton);
        return new CatalogExploreView("📖 LuminaRead — Манга и манхва", new Color(255, 138, 101),
                authBtn, filters, searchField, find, gridContainer, statusLabel);
    }

    private void startSearchFromField() {
        if (categoryTabs.getSelectedIndex() == 0) performSearch(searchField.getText());
        else categoryTabs.setSelectedIndex(0);
    }

    private void onCategoryChanged() {
        mangaSearch.cancel();
        int idx = categoryTabs.getSelectedIndex();
        clearHistoryButton.setVisible(idx == 2 && !historyManga.isEmpty());
        if (idx == 0) {
            performSearch(searchField.getText());
        } else if (idx == 1) {
            renderMangaItems(new ArrayList<>(favoriteManga.values()), "Избранная манга (" + favoriteManga.size() + ")");
        } else if (idx == 2) {
            renderMangaItems(new ArrayList<>(historyManga.values()), "История чтения манги (" + historyManga.size() + ")");
        }
    }

    private JPanel buildDetailsView() {
        JPanel panel = new JPanel(new BorderLayout(16, 16));
        panel.setBackground(new Color(20, 22, 29));
        panel.setBorder(new EmptyBorder(18, 22, 18, 22));

        JPanel topNav = new JPanel(new BorderLayout());
        topNav.setOpaque(false);
        JButton backBtn = new JButton("← Назад в каталог манги");
        backBtn.setFocusPainted(false);
        backBtn.addActionListener(e -> {
            cancelDetailRequests();
            currentLoadToken.incrementAndGet();
            if (onToggleSidebar != null) onToggleSidebar.accept(true);
            mainContainer.showCard("EXPLORE");
        });

        favToggleBtn.setFocusPainted(false);
        favToggleBtn.addActionListener(e -> {
            if (currentManga == null) return;
            if (favoriteManga.containsKey(currentManga.id)) {
                favoriteManga.remove(currentManga.id);
                favToggleBtn.setText("☆ В избранное манги");
            } else {
                favoriteManga.put(currentManga.id, currentManga);
                favToggleBtn.setText("★ В избранном манги");
            }
            savePreferences();
        });

        topNav.add(backBtn, BorderLayout.WEST);
        topNav.add(favToggleBtn, BorderLayout.EAST);

        TitleDetailsCard infoCard = new TitleDetailsCard(detailCover, detailTitle, detailBadges,
                detailDesc, new Color(255, 167, 38));

        JLabel langLabel = new JLabel("Язык глав:");
        langLabel.setForeground(new Color(180, 185, 200));
        langLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        langLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        infoCard.addCoverControl(Box.createVerticalStrut(10));
        infoCard.addCoverControl(langLabel);
        infoCard.addCoverControl(Box.createVerticalStrut(4));

        languageCombo.setMaximumSize(new Dimension(150, 28));
        languageCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        languageCombo.setEnabled(false);
        languageCombo.addActionListener(e -> onLanguageChanged());
        infoCard.addCoverControl(languageCombo);

        infoCard.addCoverControl(Box.createVerticalStrut(8));
        sortOrderBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        sortOrderBtn.setMaximumSize(new Dimension(150, 28));
        sortOrderBtn.setFocusPainted(false);
        sortOrderBtn.addActionListener(e -> toggleChapterSortOrder());
        infoCard.addCoverControl(sortOrderBtn);

        readerTitleLabel.putClientProperty("html.disable", Boolean.TRUE);
        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false);
        body.add(infoCard, BorderLayout.NORTH);
        body.add(new TitleChapterBrowser(chapterJList, chapterStatus, null,
                new Color(92, 107, 192), this::openChapterAtIndex), BorderLayout.CENTER);

        panel.add(topNav, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private void toggleChapterSortOrder() {
        chapters.toggleOrder();
        sortOrderBtn.setText(chapters.isAscending() ? "Порядок: 1 → N" : "Порядок: N → 1");
        applyLanguageFilter();
    }

    private JPanel buildReaderView() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(readerBgColor);

        JPanel toolbar = new JPanel(new BorderLayout(8, 0)) {
            @Override
            public void doLayout() {
                super.doLayout();
                Component west = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.WEST);
                Component east = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.EAST);
                Component center = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.CENTER);
                if (west != null && east != null && west.isVisible() && east.isVisible()) {
                    int westRight = west.getX() + west.getWidth();
                    if (east.getX() < westRight + 6) {
                        if (center != null) {
                            center.setBounds(0, 0, 0, 0);
                        }
                        int newEastX = westRight + 6;
                        int availW = Math.max(0, getWidth() - newEastX - 4);
                        east.setBounds(newEastX, east.getY(), availW, east.getHeight());
                    }
                }
            }
        };
        toolbar.setBackground(new Color(26, 28, 36));
        toolbar.setBorder(new EmptyBorder(8, 14, 8, 14));

        JPanel leftNav = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftNav.setOpaque(false);

        JButton backBtn = new JButton("← К главам");
        backBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        backBtn.setFocusPainted(false);
        backBtn.addActionListener(e -> {
            stopAutoScroll();
            currentLoadToken.incrementAndGet();
            chapterLoading = false;
            if (onToggleSidebar != null) onToggleSidebar.accept(true);
            mainContainer.showCard("DETAILS");
        });

        settingsPopupBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        settingsPopupBtn.setBackground(new Color(92, 107, 192));
        settingsPopupBtn.setForeground(Color.WHITE);
        settingsPopupBtn.setFocusPainted(false);
        settingsPopupBtn.setToolTipText("Настройки чтения: выравнивание по центру, ширина, яркость, автоскролл (S)");
        settingsPopupBtn.addActionListener(e -> showMiniSettingsDialog());

        zenToggleBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        zenToggleBtn.setFocusPainted(false);
        zenToggleBtn.setToolTipText("Дзен-режим: скрыть боковую панель приложения для чтения (F11)");
        zenToggleBtn.addActionListener(e -> toggleZenMode());

        leftNav.add(backBtn);
        leftNav.add(settingsPopupBtn);
        leftNav.add(zenToggleBtn);

        readerTitleLabel.setForeground(Color.WHITE);
        readerTitleLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        readerTitleLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.setOpaque(false);

        btnModeScroll.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnModeScroll.setFocusPainted(false);
        btnModeScroll.setToolTipText("Режим бесшовной вертикальной ленты (Webtoon с автоскроллом)");
        btnModeScroll.addActionListener(e -> setReadingMode(MODE_SCROLL));

        btnModePaged.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnModePaged.setFocusPainted(false);
        btnModePaged.setToolTipText("Классический постраничный режим чтения (листание страниц)");
        btnModePaged.addActionListener(e -> setReadingMode(MODE_PAGED));

        prevPageBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        prevPageBtn.setFocusPainted(false);
        prevPageBtn.setToolTipText("Предыдущая страница (Клавиша: Влево / A)");
        prevPageBtn.addActionListener(e -> navigateReader(-1));

        pageCounterLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        pageCounterLabel.setForeground(new Color(255, 183, 77));

        nextPageBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        nextPageBtn.setFocusPainted(false);
        nextPageBtn.setToolTipText("Следующая страница (Клавиша: Вправо / D / Пробел)");
        nextPageBtn.addActionListener(e -> navigateReader(1));

        autoScrollBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        autoScrollBtn.setFocusPainted(false);
        autoScrollBtn.setToolTipText("Включить/остановить плавный автоскролл вниз (Пробел)");
        autoScrollBtn.addActionListener(e -> toggleAutoScroll());

        scrollProgressLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        scrollProgressLabel.setForeground(new Color(180, 185, 200));

        prevChapterBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        prevChapterBtn.setFocusPainted(false);
        prevChapterBtn.setToolTipText("Предыдущая глава (Клавиша: [)");
        prevChapterBtn.addActionListener(e -> goChapter(-1));

        nextChapterBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        nextChapterBtn.setFocusPainted(false);
        nextChapterBtn.setToolTipText("Следующая глава (Клавиша: ])");
        nextChapterBtn.addActionListener(e -> goChapter(1));

        controls.add(btnModeScroll);
        controls.add(btnModePaged);
        controls.add(Box.createHorizontalStrut(6));

        controls.add(autoScrollBtn);
        controls.add(scrollProgressLabel);

        controls.add(prevPageBtn);
        controls.add(pageCounterLabel);
        controls.add(nextPageBtn);

        controls.add(Box.createHorizontalStrut(6));
        controls.add(prevChapterBtn);
        controls.add(nextChapterBtn);

        toolbar.add(leftNav, BorderLayout.WEST);
        toolbar.add(readerTitleLabel, BorderLayout.CENTER);
        toolbar.add(controls, BorderLayout.EAST);

        readerCanvasPanel.setBackground(readerBgColor);

        readerScrollPane = new JScrollPane(readerCanvasPanel);
        readerScrollPane.setBorder(null);
        readerScrollPane.getViewport().setBackground(readerBgColor);
        SmoothScrollUtil.enhance(readerScrollPane);
        readerScrollPane.getVerticalScrollBar().setUnitIncrement(64);

        readerScrollPane.getVerticalScrollBar().addAdjustmentListener(e -> updateScrollProgress());

        panel.setFocusable(true);
        panel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_RIGHT || e.getKeyCode() == KeyEvent.VK_D) {
                    if (readingMode == MODE_PAGED) navigateReader(1);
                    else scrollVertically(450);
                } else if (e.getKeyCode() == KeyEvent.VK_LEFT || e.getKeyCode() == KeyEvent.VK_A) {
                    if (readingMode == MODE_PAGED) navigateReader(-1);
                    else scrollVertically(-450);
                } else if (e.getKeyCode() == KeyEvent.VK_SPACE) {
                    if (readingMode == MODE_SCROLL) toggleAutoScroll();
                    else navigateReader(1);
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    scrollVertically(250);
                } else if (e.getKeyCode() == KeyEvent.VK_UP) {
                    scrollVertically(-250);
                } else if (e.getKeyCode() == KeyEvent.VK_PAGE_DOWN) {
                    if (readingMode == MODE_PAGED) navigateReader(1);
                    else scrollVertically(600);
                } else if (e.getKeyCode() == KeyEvent.VK_PAGE_UP) {
                    if (readingMode == MODE_PAGED) navigateReader(-1);
                    else scrollVertically(-600);
                } else if (e.getKeyCode() == KeyEvent.VK_S) {
                    showMiniSettingsDialog();
                } else if (e.getKeyCode() == KeyEvent.VK_F11) {
                    toggleZenMode();
                } else if (e.getKeyCode() == KeyEvent.VK_OPEN_BRACKET) {
                    goChapter(-1);
                } else if (e.getKeyCode() == KeyEvent.VK_CLOSE_BRACKET) {
                    goChapter(1);
                }
            }
        });

        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(readerScrollPane, BorderLayout.CENTER);

        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 8));
        bottomBar.setBackground(new Color(26, 28, 36));
        JButton prevChBottomBtn = new JButton("← Предыдущая глава");
        JButton nextChBottomBtn = new JButton("Следующая глава →");
        prevChBottomBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        nextChBottomBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        prevChBottomBtn.setFocusPainted(false);
        nextChBottomBtn.setFocusPainted(false);
        prevChBottomBtn.addActionListener(e -> goChapter(-1));
        nextChBottomBtn.addActionListener(e -> goChapter(1));
        bottomBar.add(prevChBottomBtn);
        bottomBar.add(nextChBottomBtn);
        panel.add(bottomBar, BorderLayout.SOUTH);

        updateModeButtonsUi();
        return panel;
    }

    private void setReadingMode(int mode) {
        if (this.readingMode != mode) {
            stopAutoScroll();
            this.readingMode = mode;
            savePreferences();
            updateModeButtonsUi();
            renderReaderPages();
        }
    }

    private void updateModeButtonsUi() {
        if (readingMode == MODE_SCROLL) {
            btnModeScroll.setBackground(new Color(92, 107, 192));
            btnModeScroll.setForeground(Color.WHITE);
            btnModePaged.setBackground(new Color(50, 54, 70));
            btnModePaged.setForeground(new Color(200, 205, 220));

            autoScrollBtn.setVisible(true);
            scrollProgressLabel.setVisible(true);

            prevPageBtn.setVisible(false);
            pageCounterLabel.setVisible(false);
            nextPageBtn.setVisible(false);
        } else {
            btnModePaged.setBackground(new Color(92, 107, 192));
            btnModePaged.setForeground(Color.WHITE);
            btnModeScroll.setBackground(new Color(50, 54, 70));
            btnModeScroll.setForeground(new Color(200, 205, 220));

            autoScrollBtn.setVisible(false);
            scrollProgressLabel.setVisible(false);

            prevPageBtn.setVisible(true);
            pageCounterLabel.setVisible(true);
            nextPageBtn.setVisible(true);
        }
        revalidate();
        repaint();
    }

    private void toggleAutoScroll() {
        if (autoScroll != null && autoScroll.isRunning()) {
            stopAutoScroll();
        } else {
            startAutoScroll();
        }
    }

    private void startAutoScroll() {
        if (readerScrollPane == null) return;
        if (autoScroll == null) {
            autoScroll = new AutoScrollController(readerScrollPane, () -> autoScrollSpeed, 30,
                    false, position -> {}, this::stopAutoScroll);
        }
        autoScrollBtn.setText("⏸ Стоп");
        autoScrollBtn.setBackground(new Color(229, 57, 53));
        autoScrollBtn.setForeground(Color.WHITE);

        autoScroll.start();
    }

    void stopAutoScroll() {
        if (autoScroll != null) autoScroll.stop();
        autoScrollBtn.setText("▶ Автоскролл");
        autoScrollBtn.setBackground(new Color(60, 64, 80));
        autoScrollBtn.setForeground(Color.WHITE);
    }

    private void scrollVertically(int delta) {
        if (readerScrollPane == null) return;
        JScrollBar vbar = readerScrollPane.getVerticalScrollBar();
        vbar.setValue(vbar.getValue() + delta);
    }

    private void updateScrollProgress() {
        if (readerScrollPane == null) return;
        JScrollBar vbar = readerScrollPane.getVerticalScrollBar();
        int max = vbar.getMaximum() - vbar.getVisibleAmount();
        if (max <= 0) {
            scrollProgressLabel.setText("100%");
            return;
        }
        int cur = vbar.getValue();
        int pct = Math.max(0, Math.min(100, (int) Math.round((double) cur * 100.0 / max)));
        scrollProgressLabel.setText(pct + "%");
    }

    private void toggleZenMode() {
        isZenActive = !isZenActive;
        zenToggleBtn.setText(isZenActive ? "◨ Меню" : "⛶ Дзен");
        if (onToggleSidebar != null) {
            onToggleSidebar.accept(!isZenActive);
        }
        SwingUtilities.invokeLater(this::renderReaderPages);
    }

    private void setAlignmentMode(int mode) {
        this.alignmentMode = mode;
        savePreferences();
        renderReaderPages();
    }

    private void adjustZoom(int delta) {
        alignmentMode = ALIGN_CENTER;
        zoomWidth = Math.max(500, Math.min(1800, zoomWidth + delta));
        savePreferences();
        renderReaderPages();
    }

    private void showMiniSettingsDialog() {
        Window parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent instanceof Frame ? (Frame) parent : null, "⚙️ Настройки чтения манги", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout()); dialog.setResizable(false);
        var state = new MangaSettingsView.State(readingMode, alignmentMode, zoomWidth, pageGap, autoScrollSpeed, dimmerOpacity, autoHideSidebar);
        var actions = new MangaSettingsView.Actions(this::setReadingMode,
                value -> { alignmentMode = value; renderReaderPages(); savePreferences(); },
                (value, center) -> { zoomWidth = value; if (center || alignmentMode == ALIGN_FIT_WIDTH) alignmentMode = ALIGN_CENTER; renderReaderPages(); savePreferences(); },
                value -> { pageGap = value; renderReaderPages(); savePreferences(); },
                value -> { autoScrollSpeed = value; savePreferences(); },
                value -> { dimmerOpacity = (100 - value) / 100.0; renderReaderPages(); savePreferences(); },
                value -> { readerBgColor = value; readerCanvasPanel.setBackground(value); readerScrollPane.getViewport().setBackground(value); savePreferences(); readerCanvasPanel.repaint(); },
                value -> { autoHideSidebar = value; if (onToggleSidebar != null) onToggleSidebar.accept(!value); savePreferences(); });
        dialog.add(MangaSettingsView.create(state, actions, dialog::dispose));
        dialog.pack(); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    public void showAuthDialog() {
        AuthDialog.show(this, currentTheme, () -> {
            updateAuthButtonState();
            onCategoryChanged();
        });
    }

    private void navigateReader(int dir) {
        if (readingMode == MODE_PAGED && currentPages != null && !currentPages.isEmpty()) {
            int nextP = currentPageIndex + dir;
            if (nextP >= 0 && nextP < currentPages.size()) {
                currentPageIndex = nextP;
                renderReaderPages();
                return;
            }
            if (dir > 0 && nextP >= currentPages.size()) {

                goChapter(1);
                return;
            }
            if (dir < 0 && nextP < 0) {

                currentPageIndex = -1;
                goChapter(-1);
                return;
            }
        }
        goChapter(dir);
    }

    private void performSearch(String query) {
        if (disposed) return;
        statusLabel.setText("⏳ Поиск манги и манхвы в онлайн-источниках...");
        gridContainer.removeAll();
        gridContainer.revalidate();
        gridContainer.repaint();

        String rawSource = (String) sourceSelector.getSelectedItem();
        final String source = rawSource != null && !"Все источники".equals(rawSource) ? rawSource : "Все источники";

        mangaSearch.start(() -> mangaService.searchMangaDetailed(query, source), result -> {
                    String message = "Найдено манги/манхвы: " + result.items().size();
                    if (!result.failedSources().isEmpty()) {
                        message += " · Не удалось загрузить: " + String.join(", ", result.failedSources());
                    }
                    renderMangaItems(result.items(), message);
                }, error -> {
                    LOG.log(java.util.logging.Level.WARNING, "Ошибка поиска манги", error);
                    statusLabel.setText("Не удалось выполнить поиск манги. Повторите попытку.");
                });
    }

    private void renderMangaItems(List<MangaItem> items, String statusMsg) {
        gridContainer.removeAll();
        for (MangaItem item : items) {
            gridContainer.add(createMangaCard(item));
        }
        statusLabel.setText(statusMsg);
        gridContainer.revalidate();
        gridContainer.repaint();
    }

    private JPanel createMangaCard(MangaItem item) {
        int section = categoryTabs.getSelectedIndex();
        Map<String, MangaItem> savedList = section == 1 ? favoriteManga : section == 2 ? historyManga : null;
        String removeLabel = section == 1 ? "Убрать из избранного" : "Убрать из истории";
        Runnable remove = savedList == null ? null : () -> CatalogListControls.edit(this, savedList,
                () -> savedList.remove(item.id), this::persistListState, this::onCategoryChanged);
        String lastRead = lastReadChapterMap.getOrDefault(item.id, "");
        String badge = item.source + " • " + item.rating + (lastRead.isEmpty() ? "" : " • Читаете: " + lastRead);
        return CatalogCard.create(item.getDisplayTitle(), badge, item.description, 125,
                new Color(255, 167, 38), "Открыть тайтл 📖", new Color(79, 93, 175), cover -> {
                    if (item.coverUrl == null || item.coverUrl.isEmpty()) return;
                    workers.execute(() -> {
                        BufferedImage image = mangaService.loadImageCached(item.coverUrl);
                        if (image != null) SwingUtilities.invokeLater(() -> {
                            if (disposed) return;
                            cover.setText("");
                            cover.setIcon(new ImageIcon(createSmoothThumbnail(image, 92, 132)));
                        });
                    });
                }, () -> showMangaDetails(item), removeLabel, remove);
    }

    private void showMangaDetails(MangaItem item) {
        if (disposed) return;
        cancelDetailRequests();
        currentLoadToken.incrementAndGet();
        chapterLoading = false;
        currentPages = Collections.emptyList();
        this.currentManga = item;
        historyManga.put(item.id, item);
        savePreferences();

        detailTitle.setText((item.rusTitle != null && !item.rusTitle.isEmpty()) ? item.rusTitle : item.title);
        detailBadges.setText("Источник: " + item.source + "   |   Рейтинг: " + item.rating + "   |   Статус: " + item.status);
        detailDesc.setText(item.description);
        favToggleBtn.setText(favoriteManga.containsKey(item.id) ? "★ В избранном манги" : "☆ В избранное манги");

        detailCover.setIcon(null);
        detailCover.setText("Загрузка...");
        chapters.clear();
        chapterListModel.clear();
        chapterStatus.setText("Загрузка глав...");
        updatingLanguage = true;
        try {
            languageCombo.removeAllItems();
            languageCombo.setEnabled(false);
        } finally {
            updatingLanguage = false;
        }
        mainContainer.showCard("DETAILS");

        detailCoverRequest.start(() -> {
            var image = mangaService.loadImageCached(item.coverUrl);
            return image == null ? null : new ImageIcon(createSmoothThumbnail(image, 145, 205));
        }, this::showDetailCover, error -> {
            LOG.log(java.util.logging.Level.WARNING, "Не удалось загрузить обложку", error);
            showDetailCover(null);
        });

        detailChaptersRequest.start(() -> List.copyOf(mangaService.fetchChapters(item)), loaded -> {
            chapters.setChapters(loaded);
            buildLanguageSelector();
            chapterStatus.setText(loaded.isEmpty() ? "Главы не найдены" : " ");
        }, error -> showChapterListError("мангу", error));

        if (item.source != null && (item.source.startsWith("MangaLib") || item.source.startsWith("Remanga"))) {
            detailDescriptionRequest.start(() -> mangaService.fetchDescription(item), full -> {
                if (full != null && !full.isEmpty()) {
                    detailDesc.setText(full);
                    detailDesc.setCaretPosition(0);
                }
            }, error -> LOG.log(java.util.logging.Level.WARNING, "Не удалось загрузить описание манги", error));
        }
    }

    private void cancelDetailRequests() {
        detailCoverRequest.cancel();
        detailChaptersRequest.cancel();
        detailDescriptionRequest.cancel();
    }

    private void showDetailCover(ImageIcon icon) {
        detailCover.setIcon(icon);
        detailCover.setText(icon == null ? "Обложка недоступна" : "");
    }

    private void showChapterListError(String titleType, Throwable error) {
        LOG.log(java.util.logging.Level.WARNING, "Ошибка загрузки списка глав", error);
        chapterStatus.setText("Не удалось загрузить главы. Попробуйте открыть " + titleType + " снова.");
    }

    private void openChapterAtIndex(int idx) {
        if (idx < 0 || idx >= chapters.visible().size()) return;
        currentChapterIndex = idx;
        final int targetInitialPage = currentPageIndex == -1 ? -1 : 0;
        currentPageIndex = 0;
        final int token = currentLoadToken.incrementAndGet();
        chapterLoading = true;
        currentPages = Collections.emptyList();
        pageCounterLabel.setText("");

        MangaChapter ch = chapters.visible().get(idx);
        if (autoHideSidebar && onToggleSidebar != null) {
            onToggleSidebar.accept(false);
            isZenActive = true;
            zenToggleBtn.setText("◨ Меню");
        }

        stopAutoScroll();
        readerTitleLabel.setText(ch.toString() + " — загрузка страниц...");
        readerCanvasPanel.removeAll();

        JLabel loadingIndicator = new JLabel("⏳ Загрузка страниц главы " + ch.number + "...", SwingConstants.CENTER);
        loadingIndicator.setForeground(new Color(180, 185, 200));
        loadingIndicator.setFont(new Font("SansSerif", Font.PLAIN, 15));
        loadingIndicator.setAlignmentX(Component.CENTER_ALIGNMENT);
        readerCanvasPanel.add(Box.createVerticalStrut(80));
        readerCanvasPanel.add(loadingIndicator);
        readerCanvasPanel.revalidate();
        readerCanvasPanel.repaint();

        mainContainer.showCard("READER");

        workers.submit(() -> token == currentLoadToken.get(), () -> mangaService.fetchPageUrls(ch), loaded -> {
            chapterLoading = false;
            currentPages = loaded == null ? Collections.emptyList() : loaded;
            if (currentManga != null && !currentPages.isEmpty()) {
                lastReadChapterMap.put(currentManga.id, "Гл. " + ch.number);
                savePreferences();
            }
            currentPageIndex = targetInitialPage == -1 ? Math.max(0, currentPages.size() - 1) : 0;
            renderReaderPages();
        }, error -> {
            LOG.log(java.util.logging.Level.WARNING, "Ошибка загрузки страниц главы", error);
            chapterLoading = false;
            currentPages = Collections.emptyList();
            renderReaderPages();
        });
    }

    private void goChapter(int dir) {
        if (chapters.visible().isEmpty()) return;
        int nextCh = currentChapterIndex + dir;
        if (nextCh >= 0 && nextCh < chapters.visible().size()) {
            openChapterAtIndex(nextCh);
        }
    }

    private void onLanguageChanged() {
        if (updatingLanguage) return;
        if (languageCombo.getItemCount() == 0) return;
        int idx = languageCombo.getSelectedIndex();
        if (idx >= 0 && idx < chapters.languages().size()) {
            chapters.selectLanguage(chapters.languages().get(idx));
            applyLanguageFilter();
        }
    }

    private void buildLanguageSelector() {
        updatingLanguage = true;
        try {
            languageCombo.removeAllItems();
            for (String language : chapters.languages()) {
                languageCombo.addItem("ru".equals(language) ? "🇷🇺 Русский" : "🇬🇧 English");
            }
            if (languageCombo.getItemCount() > 0) {
                languageCombo.setSelectedIndex(0);
                languageCombo.setEnabled(true);
            } else {
                languageCombo.setEnabled(false);
            }
        } finally {
            updatingLanguage = false;
        }
        applyLanguageFilter();
    }

    private void applyLanguageFilter() {
        chapterListModel.clear();
        for (MangaChapter ch : chapters.visible()) {
            chapterListModel.addElement(ch);
        }
        if (!chapters.visible().isEmpty()) {
            chapterJList.setSelectedIndex(0);
        }
    }

    private void renderReaderPages() {
        if (disposed || chapterLoading) return;
        readerCanvasPanel.removeAll();
        readerCanvasPanel.setBackground(readerBgColor);
        readerScrollPane.getViewport().setBackground(readerBgColor);
        final int token = currentLoadToken.get();

        if (currentPages == null || currentPages.isEmpty()) {
            JPanel errCard = new JPanel();
            errCard.setLayout(new BoxLayout(errCard, BoxLayout.Y_AXIS));
            errCard.setOpaque(false);
            errCard.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel msg1 = new JLabel("⚠️ Не удалось загрузить страницы для этой главы.", SwingConstants.CENTER);
            msg1.setFont(new Font("SansSerif", Font.BOLD, 15));
            msg1.setForeground(new Color(255, 138, 101));
            msg1.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel msg2 = new JLabel("Сервер источника занят, глава платная или доступна только на сайте.", SwingConstants.CENTER);
            msg2.setFont(new Font("SansSerif", Font.PLAIN, 13));
            msg2.setForeground(new Color(180, 185, 200));
            msg2.setAlignmentX(Component.CENTER_ALIGNMENT);

            JButton retryBtn = new JButton("🔄 Повторить попытку загрузки главы");
            retryBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
            retryBtn.setBackground(new Color(92, 107, 192));
            retryBtn.setForeground(Color.WHITE);
            retryBtn.setFocusPainted(false);
            retryBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
            retryBtn.addActionListener(e -> openChapterAtIndex(currentChapterIndex));

            JButton backToChBtn = new JButton("← Выбрать другую главу");
            backToChBtn.setFont(new Font("SansSerif", Font.PLAIN, 13));
            backToChBtn.setFocusPainted(false);
            backToChBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
            backToChBtn.addActionListener(e -> {
                if (onToggleSidebar != null) onToggleSidebar.accept(true);
                mainContainer.showCard("DETAILS");
            });

            errCard.add(Box.createVerticalStrut(60));
            errCard.add(msg1);
            errCard.add(Box.createVerticalStrut(8));
            errCard.add(msg2);
            errCard.add(Box.createVerticalStrut(20));
            errCard.add(retryBtn);
            errCard.add(Box.createVerticalStrut(10));
            errCard.add(backToChBtn);

            readerCanvasPanel.add(errCard);
            readerCanvasPanel.revalidate();
            readerCanvasPanel.repaint();
            return;
        }

        int targetW = (alignmentMode == ALIGN_FIT_WIDTH) ? Math.max(700, readerScrollPane.getViewport().getWidth() - 20) : zoomWidth;

        if (readingMode == MODE_PAGED) {

            if (currentPageIndex < 0) currentPageIndex = 0;
            if (currentPageIndex >= currentPages.size()) currentPageIndex = currentPages.size() - 1;

            String url = currentPages.get(currentPageIndex);
            readerTitleLabel.setText("Страница " + (currentPageIndex + 1) + " / " + currentPages.size());
            pageCounterLabel.setText("Стр. " + (currentPageIndex + 1) + " / " + currentPages.size());
            addFastPageCanvas(url, currentPageIndex + 1, targetW, true, token);
        } else {

            readerTitleLabel.setText("Всего страниц: " + currentPages.size() + " (Webtoon лента)");
            for (int i = 0; i < currentPages.size(); i++) {
                addFastPageCanvas(currentPages.get(i), i + 1, targetW, false, token);
            }
        }

        SwingUtilities.invokeLater(() -> {
            if (readerScrollPane != null) {
                readerScrollPane.getVerticalScrollBar().setValue(0);
                updateScrollProgress();
            }
        });
        readerCanvasPanel.revalidate();
        readerCanvasPanel.repaint();
    }

    private void addFastPageCanvas(String url, int pageNum, int targetWidth, boolean clickToTurn, int token) {
        MangaPageView pagePanel = new MangaPageView(
                pageNum,
                targetWidth,
                alignmentMode,
                dimmerOpacity,
                pageGap,
                clickToTurn,
                () -> navigateReader(-1),
                () -> navigateReader(1),
                page -> reloadSinglePage(page, url, pageNum, targetWidth, token)
        );
        readerCanvasPanel.add(pagePanel);

        loadPage(pagePanel, url, pageNum, targetWidth, token);
    }

    private void reloadSinglePage(MangaPageView page, String url, int pageNum, int targetWidth, int token) {
        loadPage(page, url, pageNum, targetWidth, token);
    }

    private void loadPage(MangaPageView page, String url, int pageNum, int targetWidth, int token) {
        pageLoader.load(url, token, image -> {
            if (image != null) {
                page.setImage(image, targetWidth, alignmentMode, dimmerOpacity, pageGap);
                readerCanvasPanel.revalidate();
            } else {
                page.setError("Не удалось загрузить страницу " + pageNum);
            }
        });
    }

    private static Image createSmoothThumbnail(BufferedImage src, int targetW, int targetH) {
        BufferedImage thumb = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = thumb.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g2.drawImage(src, 0, 0, targetW, targetH, null);
        g2.dispose();
        return thumb;
    }

    public void applyTheme(ThemePalette theme) {
        if (theme == null) return;
        this.currentTheme = theme;
        setBackground(theme.getBackground());
        mainContainer.setBackground(theme.getBackground());
        gridContainer.setBackground(new Color(20, 22, 29));
        statusLabel.setForeground(theme.getTextSecondary());
        revalidate();
        repaint();
    }
}
