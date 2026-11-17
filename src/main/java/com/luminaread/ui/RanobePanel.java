package com.luminaread.ui;

import com.luminaread.model.Chapter;
import com.luminaread.model.ranobe.RanobeChapter;
import com.luminaread.model.ranobe.RanobeChapterContent;
import com.luminaread.model.ranobe.RanobeItem;
import com.luminaread.parser.PaginationEngine;
import com.luminaread.service.IRanobeService;
import com.luminaread.service.MediaCacheService;
import com.luminaread.service.YandexAuthService;
import com.luminaread.service.ranobe.RanobeCoverLoader;
import com.luminaread.util.LatestRequest;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class RanobePanel extends JPanel {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(RanobePanel.class.getName());

    public static final int MODE_SCROLL = 0;
    public static final int MODE_PAGED = 1;

    private final IRanobeService ranobeService;
    private final java.util.function.Function<String, Image> coverLoader;
    private final YandexAuthService yandexService = YandexAuthService.getInstance();
    private final AtomicInteger loadToken = new AtomicInteger(0);
    private final PanelWorkers workers = new PanelWorkers(4);
    private volatile boolean disposed;
    private final Runnable authListener = () -> SwingUtilities.invokeLater(() -> {
        if (!disposed) updateAuthButtonState();
    });
    private final LatestRequest searchRequests = new LatestRequest();
    private final LatestRequest detailRequests = new LatestRequest();
    private final Consumer<Boolean> onToggleSidebar;

    private final MotionCardPanel mainContainer = new MotionCardPanel("READER");

    private final Map<String, RanobeItem> favoriteRanobe = new LinkedHashMap<>();
    private final Map<String, RanobeItem> historyRanobe = new LinkedHashMap<>();
    private final Map<String, String> lastReadChapterMap = new LinkedHashMap<>();
    private final Map<String, String> readerPrefs = new LinkedHashMap<>();

    private int readingMode = MODE_SCROLL;
    private String fontFamily = "Georgia";
    private int fontSize = 19;
    private double lineHeight = 1.6;
    private int textBlockWidth = 840;
    private boolean justifyText = false;
    private String themePresetId = "NIGHT_AMOLED";
    private double dimmerOpacity = 0.0;
    private int autoScrollSpeed = 40;
    private boolean isZenActive = false;

    private final JTextField searchField = new JTextField();
    private final JButton clearHistoryButton = new JButton("Очистить историю");
    private final JComboBox<String> categoryTabs = new JComboBox<>(new String[]{
            "🔥 Популярное сейчас", "⭐ Избранное ранобэ", "🕒 История чтения"
    });
    private final SmoothScrollUtil.ResponsiveListPanel gridContainer = new SmoothScrollUtil.ResponsiveListPanel(new GridLayout(0, 2, 14, 14));
    private final JLabel statusLabel = new JLabel("Готово к поиску ранобэ и новелл");
    private final JButton authBtn = new JButton("🟡 Войти через Яндекс ID");

    private RanobeItem currentRanobe;
    private final JLabel detailTitle = new JLabel();
    private final JLabel detailBadges = new JLabel();
    private final JTextArea detailDesc = new JTextArea();
    private final JLabel detailCover = new JLabel();
    private final JButton favToggleBtn = new JButton("☆ В избранное ранобэ");
    private final DefaultListModel<RanobeChapter> chapterListModel = new DefaultListModel<>();
    private final JList<RanobeChapter> chapterJList = new JList<>(chapterListModel);
    private final JLabel chapterStatus = new JLabel(" ");
    private final JTextField chapterSearchField = new JTextField();
    private final JButton sortOrderBtn = new JButton("Порядок: 1 → N");
    private final RanobeChapterList chapters = new RanobeChapterList();

    private RanobeChapter currentChapter;
    private RanobeChapterContent currentContent;
    private int currentChapterIndex = 0;
    private int currentPageIndex = 0;
    private List<String> currentPages = Collections.emptyList();

    private final JEditorPane textEditorPane = new JEditorPane();
    private final JScrollPane readerScrollPane;
    private final JLabel readerTitleLabel = new JLabel("Чтение");
    private final JButton btnModeScroll = new JButton("📜 Лента (Свиток)");
    private final JButton btnModePaged = new JButton("📖 Постранично");
    private final JButton prevPageBtn = new JButton("◀");
    private final JLabel pageCounterLabel = new JLabel("Стр. 1 / 1");
    private final JButton nextPageBtn = new JButton("▶");
    private final JButton autoScrollBtn = new JButton("▶ Автоскролл");
    private final JButton textSettingsBtn = new JButton("⚙️ Текст");
    private final JButton zenBtn = new JButton("⛶ Дзен");
    private final JLabel progressPctLabel = new JLabel("0%");

    private AutoScrollController autoScroll;
    private boolean searchStarted;
    private ThemePalette currentTheme = ThemePalette.byId("NIGHT_AMOLED");

    RanobePanel(Consumer<Boolean> onToggleSidebar, IRanobeService ranobeService, boolean initialSearch) {
        this(onToggleSidebar, ranobeService, initialSearch,
                new RanobeCoverLoader(MediaCacheService.getInstance())::load);
    }

    RanobePanel(Consumer<Boolean> onToggleSidebar, IRanobeService ranobeService, boolean initialSearch,
                java.util.function.Function<String, Image> coverLoader) {
        this.onToggleSidebar = onToggleSidebar;
        this.ranobeService = Objects.requireNonNull(ranobeService);
        this.coverLoader = Objects.requireNonNull(coverLoader);
        setLayout(new BorderLayout());
        setBackground(new Color(20, 22, 29));

        loadPreferences();

        mainContainer.addCard("EXPLORE", buildExploreView());
        mainContainer.addCard("DETAILS", buildDetailsView());

        readerScrollPane = buildReaderScrollPane();
        mainContainer.addCard("READER", buildReaderView());

        add(mainContainer, BorderLayout.CENTER);

        yandexService.addListener(authListener);
        updateAuthButtonState();

        if (initialSearch) openCatalog();
    }

    void openCatalog() {
        if (!searchStarted) performSearch("");
    }

    void useTransitionSurface(MotionSurface surface) {
        mainContainer.useTransitionSurface(surface);
    }

    private void showSidebar() {
        if (onToggleSidebar != null) onToggleSidebar.accept(true);
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
        yandexService.removeListener(authListener);
        searchRequests.invalidate();
        detailRequests.invalidate();
        loadToken.incrementAndGet();
        workers.close();
        if (autoScroll != null) autoScroll.stop();
    }

    private void loadPreferences() {
        ranobeService.loadRanobeState(favoriteRanobe, historyRanobe, lastReadChapterMap, readerPrefs, RanobeItem::new);
        readingMode = ReaderPreferenceValues.integer(readerPrefs, "readingMode", readingMode, MODE_SCROLL, MODE_PAGED);
        fontFamily = readerPrefs.getOrDefault("fontFamily", fontFamily);
        fontSize = ReaderPreferenceValues.integer(readerPrefs, "fontSize", fontSize, 12, 36);
        lineHeight = ReaderPreferenceValues.finiteDouble(readerPrefs, "lineHeight", lineHeight);
        textBlockWidth = ReaderPreferenceValues.integer(readerPrefs, "textBlockWidth", textBlockWidth, 500, 1600);
        justifyText = ReaderPreferenceValues.bool(readerPrefs, "justifyText", justifyText);
        themePresetId = readerPrefs.getOrDefault("themePresetId", themePresetId);
        dimmerOpacity = ReaderPreferenceValues.integer(readerPrefs, "dimmerPercent", (int) (dimmerOpacity * 100), 0, 70) / 100.0;
        autoScrollSpeed = ReaderPreferenceValues.integer(readerPrefs, "autoScrollSpeed", autoScrollSpeed, 10, 180);
    }

    private void savePreferences() {
        readerPrefs.put("readingMode", String.valueOf(readingMode));
        readerPrefs.put("fontFamily", fontFamily);
        readerPrefs.put("fontSize", String.valueOf(fontSize));
        readerPrefs.put("lineHeight", String.valueOf(lineHeight));
        readerPrefs.put("textBlockWidth", String.valueOf(textBlockWidth));
        readerPrefs.put("justifyText", String.valueOf(justifyText));
        readerPrefs.put("themePresetId", themePresetId);
        readerPrefs.put("dimmerPercent", String.valueOf((int) Math.round(dimmerOpacity * 100)));
        readerPrefs.put("autoScrollSpeed", String.valueOf(autoScrollSpeed));
        try {
            persistListState();
        } catch (java.io.UncheckedIOException | SecurityException ex) {
            UiPersistenceErrors.show(this, "Не удалось сохранить настройки и прогресс ранобэ.", ex);
        }
    }

    private void persistListState() {
        ranobeService.saveRanobeState(favoriteRanobe, historyRanobe, lastReadChapterMap, readerPrefs);
    }

    private void clearHistory() {
        if (historyRanobe.isEmpty() || !CatalogListControls.confirmClear(this)) return;
        CatalogListControls.edit(this, historyRanobe, historyRanobe::clear, this::persistListState,
                this::onCategoryChanged);
    }

    private void updateAuthButtonState() {
        var account = yandexService.account();
        if (account.status() == YandexAuthService.Status.VERIFIED) {
            authBtn.setText("🟡 " + account.description());
            authBtn.setBackground(new Color(255, 214, 0));
            authBtn.setForeground(new Color(20, 20, 20));
        } else {
            authBtn.setText(account.status() == YandexAuthService.Status.SAVED
                    ? "🟡 Подтвердить вход" : "🟡 Войти через Яндекс ID");
            authBtn.setBackground(new Color(55, 60, 75));
            authBtn.setForeground(new Color(230, 235, 245));
        }
    }

    private JPanel buildExploreView() {
        authBtn.addActionListener(e -> showAuthDialog());
        updateAuthButtonState();
        categoryTabs.addActionListener(e -> onCategoryChanged());
        clearHistoryButton.setVisible(false);
        clearHistoryButton.addActionListener(e -> clearHistory());

        searchField.setToolTipText("Поиск новелл и ранобэ по названию");
        searchField.addActionListener(e -> startSearchFromField());
        JButton find = new JButton("🔍 Найти ранобэ");
        find.setBackground(new Color(67, 160, 71));
        find.addActionListener(e -> startSearchFromField());

        JPanel filters = CatalogExploreView.filters(categoryTabs, null, clearHistoryButton);
        return new CatalogExploreView("📜 LuminaRead — Ранобэ и новеллы", new Color(129, 199, 132),
                authBtn, filters, searchField, find, gridContainer, statusLabel);
    }

    private void startSearchFromField() {
        if (categoryTabs.getSelectedIndex() == 0) performSearch(searchField.getText());
        else categoryTabs.setSelectedIndex(0);
    }

    private void onCategoryChanged() {
        searchRequests.invalidate();
        int idx = categoryTabs.getSelectedIndex();
        clearHistoryButton.setVisible(idx == 2 && !historyRanobe.isEmpty());
        if (idx == 0) {
            performSearch(searchField.getText());
        } else if (idx == 1) {
            renderRanobeItems(new ArrayList<>(favoriteRanobe.values()), "Избранное ранобэ (" + favoriteRanobe.size() + ")");
        } else if (idx == 2) {
            renderRanobeItems(new ArrayList<>(historyRanobe.values()), "История чтения (" + historyRanobe.size() + ")");
        }
    }

    private void performSearch(String query) {
        if (disposed) return;
        searchStarted = true;
        final long request = searchRequests.next();
        statusLabel.setText("⏳ Поиск ранобэ и новелл в RanobeLib...");
        gridContainer.removeAll();
        gridContainer.revalidate();
        gridContainer.repaint();

        workers.submit(() -> searchRequests.isCurrent(request), () -> List.<RanobeItem>copyOf(ranobeService.searchRanobe(query)),
                items -> renderRanobeItems(items, "Найдено тайтлов ранобэ: " + items.size()), error -> {
                    LOG.log(java.util.logging.Level.WARNING, "Ошибка поиска ранобэ", error);
                    statusLabel.setText("Не удалось выполнить поиск ранобэ. Проверьте соединение и повторите попытку.");
                });
    }

    private void renderRanobeItems(List<RanobeItem> items, String statusMsg) {
        gridContainer.removeAll();
        for (RanobeItem item : items) {
            gridContainer.add(createRanobeCard(item));
        }
        statusLabel.setText(statusMsg);
        gridContainer.revalidate();
        gridContainer.repaint();
    }

    private JPanel createRanobeCard(RanobeItem item) {
        int section = categoryTabs.getSelectedIndex();
        Map<String, RanobeItem> savedList = section == 1 ? favoriteRanobe : section == 2 ? historyRanobe : null;
        String removeLabel = section == 1 ? "Убрать из избранного" : "Убрать из истории";
        Runnable remove = savedList == null ? null : () -> CatalogListControls.edit(this, savedList,
                () -> savedList.remove(item.id), this::persistListState, this::onCategoryChanged);
        String lastRead = lastReadChapterMap.getOrDefault(item.id, "");
        String badge = "RanobeLib • " + item.rating + (lastRead.isEmpty() ? "" : " • Читаете: " + lastRead);
        return CatalogCard.create(item.getDisplayTitle(), badge, item.description, 130,
                new Color(129, 199, 132), "Открыть ранобэ 📜", new Color(56, 142, 60), cover -> {
                    if (item.coverUrl == null || item.coverUrl.isEmpty()) return;
                    workers.execute(() -> {
                        Image image = coverLoader.apply(item.coverUrl);
                        if (image != null) {
                            Image scaled = image.getScaledInstance(92, 132, Image.SCALE_SMOOTH);
                            SwingUtilities.invokeLater(() -> {
                                if (disposed) return;
                                cover.setText("");
                                cover.setIcon(new ImageIcon(scaled));
                            });
                        }
                    });
                }, () -> showRanobeDetails(item), removeLabel, remove);
    }

    private JPanel buildDetailsView() {
        JPanel panel = new JPanel(new BorderLayout(16, 16));
        panel.setBackground(new Color(20, 22, 29));
        panel.setBorder(new EmptyBorder(18, 22, 18, 22));

        JPanel topNav = new JPanel(new BorderLayout());
        topNav.setOpaque(false);
        JButton backBtn = new JButton("← Назад в каталог ранобэ");
        backBtn.setFocusPainted(false);
        backBtn.addActionListener(e -> {
            detailRequests.invalidate();
            loadToken.incrementAndGet();
            mainContainer.showCard("EXPLORE", this::showSidebar);
        });

        favToggleBtn.setFocusPainted(false);
        favToggleBtn.addActionListener(e -> {
            if (currentRanobe == null) return;
            if (favoriteRanobe.containsKey(currentRanobe.id)) {
                favoriteRanobe.remove(currentRanobe.id);
                favToggleBtn.setText("☆ В избранное ранобэ");
            } else {
                favoriteRanobe.put(currentRanobe.id, currentRanobe);
                favToggleBtn.setText("★ В избранном ранобэ");
            }
            savePreferences();
        });

        topNav.add(backBtn, BorderLayout.WEST);
        topNav.add(favToggleBtn, BorderLayout.EAST);

        TitleDetailsCard infoCard = new TitleDetailsCard(detailCover, detailTitle, detailBadges,
                detailDesc, new Color(129, 199, 132));

        infoCard.addCoverControl(Box.createVerticalStrut(10));
        sortOrderBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        sortOrderBtn.setMaximumSize(new Dimension(150, 28));
        sortOrderBtn.setFocusPainted(false);
        sortOrderBtn.addActionListener(e -> toggleChapterSortOrder());
        infoCard.addCoverControl(sortOrderBtn);

        readerTitleLabel.putClientProperty("html.disable", Boolean.TRUE);
        JPanel filter = new JPanel(new BorderLayout(8, 0));
        filter.setOpaque(false);
        chapterSearchField.setFont(new Font("SansSerif", Font.PLAIN, 13));
        chapterSearchField.setToolTipText("Фильтр глав по номеру или названию");
        chapterSearchField.addActionListener(e -> filterChapters(chapterSearchField.getText()));
        JLabel filterLabel = new JLabel("Поиск главы:");
        filterLabel.setForeground(Color.LIGHT_GRAY);
        filterLabel.setLabelFor(chapterSearchField);
        filter.add(filterLabel, BorderLayout.WEST);
        filter.add(chapterSearchField, BorderLayout.CENTER);

        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false);
        body.add(infoCard, BorderLayout.NORTH);
        body.add(new TitleChapterBrowser(chapterJList, chapterStatus, filter,
                new Color(67, 160, 71), this::openChapterAtIndex), BorderLayout.CENTER);

        panel.add(topNav, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private void showRanobeDetails(RanobeItem item) {
        if (disposed) return;
        final long request = detailRequests.next();
        loadToken.incrementAndGet();
        this.currentRanobe = item;
        historyRanobe.put(item.id, item);
        savePreferences();

        detailTitle.setText(item.getDisplayTitle());
        detailBadges.setText("RanobeLib   |   Рейтинг: " + item.rating + "   |   Статус: " + item.status);
        detailDesc.setText(item.description);
        favToggleBtn.setText(favoriteRanobe.containsKey(item.id) ? "★ В избранном ранобэ" : "☆ В избранное ранобэ");

        detailCover.setIcon(null);
        detailCover.setText("Загрузка...");
        chapters.clear();
        chapterListModel.clear();
        chapterStatus.setText("Загрузка глав...");
        mainContainer.showCard("DETAILS");

        workers.execute(() -> {
            ImageIcon icon = null;
            try {
                var image = coverLoader.apply(item.coverUrl);
                if (image != null) icon = new ImageIcon(image.getScaledInstance(145, 205, Image.SCALE_SMOOTH));
            } catch (RuntimeException ex) {
                LOG.log(java.util.logging.Level.WARNING, "Не удалось загрузить обложку", ex);
            }
            ImageIcon loadedIcon = icon;
            SwingUtilities.invokeLater(() -> {
                if (!detailRequests.isCurrent(request)) return;
                detailCover.setText(loadedIcon == null ? "Обложка недоступна" : "");
                detailCover.setIcon(loadedIcon);
            });
        });

        workers.submit(() -> detailRequests.isCurrent(request),
                () -> List.<RanobeChapter>copyOf(ranobeService.fetchChapters(item.id)), loaded -> {
                    chapters.setChapters(loaded);
                    filterChapters("");
                    chapterStatus.setText(loaded.isEmpty() ? "Главы не найдены" : " ");
                }, error -> showChapterListError("ранобэ", error));

        workers.submit(() -> detailRequests.isCurrent(request), () -> ranobeService.fetchRanobeDetails(item), full -> {
            if (full != null && item.id.equals(full.id)) {
                currentRanobe = full;
                detailDesc.setText(full.description + (full.genres.isEmpty() ? "" : "\n\nЖанры: " + full.getGenresString())
                        + (full.authors.isEmpty() ? "" : "\nАвтор: " + full.getAuthorsString()));
                detailDesc.setCaretPosition(0);
            }
        }, error -> LOG.log(java.util.logging.Level.WARNING, "Не удалось загрузить описание ранобэ", error));
    }

    private void toggleChapterSortOrder() {
        chapters.toggleOrder();
        sortOrderBtn.setText(chapters.isAscending() ? "Порядок: 1 → N" : "Порядок: N → 1");
        chapterListModel.clear();
        for (RanobeChapter ch : chapters.visible()) {
            chapterListModel.addElement(ch);
        }
        if (!chapters.visible().isEmpty()) chapterJList.setSelectedIndex(0);
    }

    private void filterChapters(String q) {
        chapters.filter(q);
        chapterListModel.clear();
        for (RanobeChapter ch : chapters.visible()) {
            chapterListModel.addElement(ch);
        }
        if (!chapters.visible().isEmpty()) {

            String last = (currentRanobe != null) ? lastReadChapterMap.getOrDefault(currentRanobe.id, "") : "";
            int selectIdx = chapters.lastReadIndex(last);
            chapterJList.setSelectedIndex(selectIdx);
            chapterJList.ensureIndexIsVisible(selectIdx);
        }
    }

    private JScrollPane buildReaderScrollPane() {
        textEditorPane.setEditable(false);
        textEditorPane.setEditorKit(new ReaderHtmlKit());
        textEditorPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        if (textEditorPane.getCaret() instanceof DefaultCaret) {
            ((DefaultCaret) textEditorPane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }
        textEditorPane.setFocusable(false);

        JScrollPane sp = SmoothScrollUtil.enhanceAndReturn(new JScrollPane(textEditorPane));
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.getVerticalScrollBar().setUnitIncrement(48);

        sp.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            private int lastW = -1;
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                int newW = sp.getViewport().getWidth();

                if (lastW != -1 && Math.abs(newW - lastW) > 30) {
                    lastW = newW;
                    if (currentContent != null) {
                        renderChapterText();
                    }
                } else if (lastW == -1 && newW > 0) {
                    lastW = newW;
                }
            }
        });

        sp.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (readingMode == MODE_SCROLL) {
                int extent = sp.getViewport().getHeight();
                int max = sp.getVerticalScrollBar().getMaximum() - extent;
                if (max > 0) {
                    int pct = Math.min(100, Math.max(0, (int) Math.round((double) e.getValue() * 100 / max)));
                    progressPctLabel.setText(pct + "%");
                }
            }
        });

        return sp;
    }

    private JPanel buildReaderView() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(getThemeBackground());

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
            loadToken.incrementAndGet();
            mainContainer.showCard("DETAILS", this::showSidebar);
        });

        textSettingsBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        textSettingsBtn.setBackground(new Color(67, 160, 71));
        textSettingsBtn.setForeground(Color.WHITE);
        textSettingsBtn.setFocusPainted(false);
        textSettingsBtn.setToolTipText("Настройки шрифта, размера, полей и темы чтения");
        textSettingsBtn.addActionListener(e -> showTextSettingsDialog());

        zenBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        zenBtn.setFocusPainted(false);
        zenBtn.setToolTipText("Дзен-режим: скрыть боковую панель приложения для чтения (F11)");
        zenBtn.addActionListener(e -> toggleZenMode());

        leftNav.add(backBtn);
        leftNav.add(textSettingsBtn);
        leftNav.add(zenBtn);

        readerTitleLabel.setForeground(Color.WHITE);
        readerTitleLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        readerTitleLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.setOpaque(false);

        btnModeScroll.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnModePaged.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnModeScroll.setFocusPainted(false);
        btnModePaged.setFocusPainted(false);
        btnModeScroll.addActionListener(e -> setReadingMode(MODE_SCROLL));
        btnModePaged.addActionListener(e -> setReadingMode(MODE_PAGED));
        updateModeButtonsVisual();

        prevPageBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        nextPageBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        prevPageBtn.setFocusPainted(false);
        nextPageBtn.setFocusPainted(false);
        pageCounterLabel.setForeground(new Color(220, 225, 235));
        pageCounterLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        prevPageBtn.addActionListener(e -> navigatePage(-1));
        nextPageBtn.addActionListener(e -> navigatePage(1));

        autoScrollBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        autoScrollBtn.setFocusPainted(false);
        autoScrollBtn.addActionListener(e -> toggleAutoScroll());

        controls.add(btnModeScroll);
        controls.add(btnModePaged);
        controls.add(Box.createHorizontalStrut(6));
        controls.add(prevPageBtn);
        controls.add(pageCounterLabel);
        controls.add(nextPageBtn);
        controls.add(autoScrollBtn);

        toolbar.add(leftNav, BorderLayout.WEST);
        toolbar.add(readerTitleLabel, BorderLayout.CENTER);
        toolbar.add(controls, BorderLayout.EAST);

        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(readerScrollPane, BorderLayout.CENTER);

        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setBackground(new Color(26, 28, 36));
        bottomBar.setBorder(new EmptyBorder(6, 16, 6, 16));

        JPanel chNav = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
        chNav.setOpaque(false);
        JButton prevChBtn = new JButton("⏮ Предыдущая глава");
        JButton nextChBtn = new JButton("Следующая глава ⏭");
        prevChBtn.setFocusPainted(false);
        nextChBtn.setFocusPainted(false);
        prevChBtn.addActionListener(e -> navigateChapter(-1));
        nextChBtn.addActionListener(e -> navigateChapter(1));
        chNav.add(prevChBtn);
        chNav.add(nextChBtn);

        progressPctLabel.setForeground(new Color(180, 185, 200));
        progressPctLabel.setFont(new Font("SansSerif", Font.BOLD, 12));

        bottomBar.add(progressPctLabel, BorderLayout.WEST);
        bottomBar.add(chNav, BorderLayout.CENTER);

        panel.add(bottomBar, BorderLayout.SOUTH);

        panel.setFocusable(true);
        panel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_RIGHT || code == KeyEvent.VK_D) {
                    if (readingMode == MODE_PAGED) navigatePage(1);
                    else scrollByScreen(1);
                } else if (code == KeyEvent.VK_LEFT || code == KeyEvent.VK_A) {
                    if (readingMode == MODE_PAGED) navigatePage(-1);
                    else scrollByScreen(-1);
                } else if (code == KeyEvent.VK_SPACE) {
                    if (readingMode == MODE_PAGED) navigatePage(1);
                    else scrollByScreen(1);
                } else if (code == KeyEvent.VK_DOWN || code == KeyEvent.VK_S) {
                    if (readingMode == MODE_PAGED) navigatePage(1);
                    else scrollBySmoothDelta(160);
                } else if (code == KeyEvent.VK_UP || code == KeyEvent.VK_W) {
                    if (readingMode == MODE_PAGED) navigatePage(-1);
                    else scrollBySmoothDelta(-160);
                } else if (code == KeyEvent.VK_PAGE_DOWN) {
                    if (readingMode == MODE_PAGED) navigatePage(1);
                    else scrollByScreen(1);
                } else if (code == KeyEvent.VK_PAGE_UP) {
                    if (readingMode == MODE_PAGED) navigatePage(-1);
                    else scrollByScreen(-1);
                } else if (code == KeyEvent.VK_F11) {
                    toggleZenMode();
                } else if (code == KeyEvent.VK_T) {
                    showTextSettingsDialog();
                }
            }
        });

        return panel;
    }

    private void updateModeButtonsVisual() {
        boolean isScroll = (readingMode == MODE_SCROLL);
        btnModeScroll.setBackground(isScroll ? new Color(67, 160, 71) : new Color(45, 50, 65));
        btnModeScroll.setForeground(Color.WHITE);
        btnModePaged.setBackground(!isScroll ? new Color(67, 160, 71) : new Color(45, 50, 65));
        btnModePaged.setForeground(Color.WHITE);

        prevPageBtn.setVisible(!isScroll);
        pageCounterLabel.setVisible(!isScroll);
        nextPageBtn.setVisible(!isScroll);
        autoScrollBtn.setVisible(isScroll);
    }

    private void setReadingMode(int mode) {
        this.readingMode = mode;
        savePreferences();
        updateModeButtonsVisual();
        if (mode != MODE_SCROLL) stopAutoScroll();
        renderChapterText();
    }

    private void toggleZenMode() {
        isZenActive = !isZenActive;
        zenBtn.setText(isZenActive ? "◨ Меню" : "⛶ Дзен");
        if (onToggleSidebar != null) {
            onToggleSidebar.accept(!isZenActive);
        }
    }

    private void scrollByScreen(int dir) {
        int delta = (int) (readerScrollPane.getViewport().getHeight() * 0.75) * dir;
        SmoothScrollUtil.smoothScrollBy(readerScrollPane, delta);
    }

    private void scrollBySmoothDelta(int delta) {
        SmoothScrollUtil.smoothScrollBy(readerScrollPane, delta);
    }

    private void toggleAutoScroll() {
        if (autoScroll != null && autoScroll.isRunning()) {
            stopAutoScroll();
        } else {
            startAutoScroll();
        }
    }

    private void startAutoScroll() {
        if (readingMode != MODE_SCROLL) return;
        stopAutoScroll();
        autoScrollBtn.setText("⏸ Пауза");
        autoScrollBtn.setBackground(new Color(239, 108, 0));
        if (autoScroll == null) {
            autoScroll = new AutoScrollController(readerScrollPane, () -> autoScrollSpeed, 16,
                    true, position -> SmoothScrollUtil.syncTarget(readerScrollPane, position), () -> {
                        stopAutoScroll();
                        navigateChapter(1);
                    });
        }
        autoScroll.start();
    }

    void stopAutoScroll() {
        if (autoScroll != null) autoScroll.stop();
        autoScrollBtn.setText("▶ Автоскролл");
        autoScrollBtn.setBackground(new Color(67, 160, 71));
    }

    private void showChapterListError(String titleType, Throwable error) {
        LOG.log(java.util.logging.Level.WARNING, "Ошибка загрузки списка глав", error);
        chapterStatus.setText("Не удалось загрузить главы. Попробуйте открыть " + titleType + " снова.");
    }

    private void openChapterAtIndex(int idx) {
        if (idx < 0 || idx >= chapters.visible().size()) return;
        currentChapterIndex = idx;
        currentContent = null;
        currentPages = Collections.emptyList();
        currentPageIndex = 0;
        final int token = loadToken.incrementAndGet();

        stopAutoScroll();
        currentChapter = chapters.visible().get(idx);
        final RanobeChapter requestedChapter = currentChapter;
        final String requestedTitleId = currentRanobe != null ? currentRanobe.id : null;

        readerTitleLabel.setText(currentChapter.toString() + " — загрузка...");
        textEditorPane.setText("<html><body style='text-align:center; padding:50px; color:#aaa; font-family:sans-serif;'>"
                + "<h2>⏳ Загрузка главы " + com.luminaread.util.HtmlSanitizer.escapeHtml(requestedChapter.number) + "...</h2></body></html>");
        mainContainer.showCard("READER");

        workers.submit(() -> token == loadToken.get(), () -> {
            var loaded = ranobeService.fetchChapterContent(requestedChapter, requestedTitleId);
            if (loaded == null) throw new IllegalStateException("Пустой ответ источника");
            return loaded;
        }, loaded -> {
            currentContent = loaded;
            if (requestedTitleId != null) {
                lastReadChapterMap.put(requestedTitleId, "Гл. " + requestedChapter.number);
                savePreferences();
            }
            readerTitleLabel.setText(requestedChapter.toString());
            renderChapterText();
        }, error -> {
            LOG.log(java.util.logging.Level.WARNING, "Ошибка загрузки главы ранобэ", error);
            textEditorPane.setText("<html><body style='text-align:center; padding:50px; color:#ff8a65; font-family:sans-serif;'>"
                    + "<h2>⚠️ Не удалось загрузить главу</h2>"
                    + "<p>Возможно, глава платная или доступна только на сайте.</p></body></html>");
        });
    }

    private void navigateChapter(int dir) {
        if (chapters.visible().isEmpty()) return;
        int nextCh = currentChapterIndex + dir;
        if (nextCh >= 0 && nextCh < chapters.visible().size()) {
            openChapterAtIndex(nextCh);
        }
    }

    private void navigatePage(int dir) {
        if (currentPages == null || currentPages.isEmpty()) return;
        int nextP = currentPageIndex + dir;
        if (nextP >= 0 && nextP < currentPages.size()) {
            currentPageIndex = nextP;
            displayPage();
        } else if (nextP >= currentPages.size()) {

            navigateChapter(1);
        } else if (nextP < 0) {

            navigateChapter(-1);
        }
    }

    private void resetScrollPosition() {
        SmoothScrollUtil.resetScroll(readerScrollPane, 0);
        SwingUtilities.invokeLater(() -> {
            if (readerScrollPane != null) {
                SmoothScrollUtil.resetScroll(readerScrollPane, 0);
            }
        });
    }

    private void renderChapterText() {
        if (currentContent == null) return;

        Color bg = getThemeBackground();
        readerScrollPane.getViewport().setBackground(bg);
        textEditorPane.setBackground(bg);

        if (readingMode == MODE_PAGED) {

            int vpH = (readerScrollPane != null && readerScrollPane.getViewport().getHeight() > 0)
                    ? readerScrollPane.getViewport().getHeight() : 800;
            int vpW = (readerScrollPane != null && readerScrollPane.getViewport().getWidth() > 0)
                    ? readerScrollPane.getViewport().getWidth() : 900;
            int effW = Math.min(vpW, textBlockWidth);

            double lineH = fontSize * lineHeight * 1.33;
            int linesFit = Math.max(8, (int) Math.floor((vpH - 70) / lineH));
            int charsPerLine = Math.max(30, (int) Math.floor(effW / (fontSize * 0.62)));
            int charsPerPage = Math.max(500, Math.min(2600, linesFit * charsPerLine));

            Chapter fakeChapter = new Chapter(0, currentContent.title, currentContent.rawHtmlContent, currentContent.plainTextContent);
            currentPages = PaginationEngine.paginateChapter(fakeChapter, charsPerPage, false);
            currentPageIndex = Math.max(0, Math.min(currentPages.size() - 1, currentPageIndex));
            displayPage();
        } else {

            currentPages = Collections.singletonList(currentContent.rawHtmlContent);
            currentPageIndex = 0;
            displayScroll();
        }
    }

    private void displayPage() {
        if (currentPages.isEmpty()) return;
        String htmlPage = currentPages.get(currentPageIndex);
        setEditorHtmlContent(htmlPage);
        pageCounterLabel.setText("Стр. " + (currentPageIndex + 1) + " / " + currentPages.size());
        progressPctLabel.setText(Math.round(((double) (currentPageIndex + 1) * 100 / currentPages.size())) + "%");
        resetScrollPosition();
    }

    private void displayScroll() {
        if (currentContent == null) return;
        setEditorHtmlContent(currentContent.rawHtmlContent);
        pageCounterLabel.setText("Лента");
        progressPctLabel.setText("0%");
        resetScrollPosition();
    }

    private void setEditorHtmlContent(String bodyInnerHtml) {
        Color accent = currentTheme != null ? currentTheme.getAccent() : new Color(129, 199, 132);
        RanobeReaderRenderer.render(textEditorPane, readerScrollPane, bodyInnerHtml, getThemeBackground(),
                getThemeText(), accent, fontFamily, fontSize, lineHeight, textBlockWidth, justifyText);
    }

    private Color getThemeBackground() {
        switch (themePresetId) {
            case "DAY_PAPER": return new Color(248, 246, 240);
            case "SEPIA": return new Color(244, 236, 216);
            case "NORD": return new Color(46, 52, 64);
            case "MINT": return new Color(232, 245, 233);
            default: return new Color(14, 15, 20);
        }
    }

    private Color getThemeText() {
        switch (themePresetId) {
            case "DAY_PAPER": return new Color(38, 42, 53);
            case "SEPIA": return new Color(62, 44, 28);
            case "NORD": return new Color(236, 239, 244);
            case "MINT": return new Color(27, 94, 32);
            default: return new Color(225, 230, 240);
        }
    }

    private void showTextSettingsDialog() {
        Window parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent instanceof Frame ? (Frame) parent : null,
                "⚙️ Настройки текста ранобэ", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout());
        dialog.setResizable(false);
        var state = new RanobeSettingsView.State(readingMode, fontSize, fontFamily, textBlockWidth, autoScrollSpeed);
        var actions = new RanobeSettingsView.Actions(this::setReadingMode,
                value -> { fontSize = value; renderChapterText(); savePreferences(); },
                value -> { fontFamily = value; renderChapterText(); savePreferences(); },
                value -> { textBlockWidth = value; renderChapterText(); savePreferences(); },
                value -> { themePresetId = value; renderChapterText(); savePreferences(); },
                value -> { autoScrollSpeed = value; savePreferences(); });
        dialog.add(RanobeSettingsView.create(state, actions, dialog::dispose));
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    public void showAuthDialog() {
        AuthDialog.show(this, currentTheme);
    }

    public void applyTheme(ThemePalette theme) {
        if (theme == null) return;
        this.currentTheme = theme;
        setBackground(theme.getBackground());
        mainContainer.setBackground(theme.getBackground());
        gridContainer.setBackground(new Color(20, 22, 29));
        statusLabel.setForeground(theme.getTextSecondary());
        if (currentContent != null) {
            renderChapterText();
        }
        revalidate();
        repaint();
    }
}
