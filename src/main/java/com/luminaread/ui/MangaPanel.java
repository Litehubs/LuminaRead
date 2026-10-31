package com.luminaread.ui;

import com.luminaread.service.LibAccountService;
import com.luminaread.service.LibAccountService.LibBookmarkItem;
import com.luminaread.service.LibAccountService.LibUser;
import com.luminaread.service.YandexAuthService;
import com.luminaread.service.YandexAuthService.YandexUser;
import com.luminaread.service.MangaService;
import com.luminaread.service.MangaService.MangaChapter;
import com.luminaread.service.MangaService.MangaItem;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class MangaPanel extends JPanel {

    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_FIT_WIDTH = 2;

    public static final int MODE_SCROLL = 0;
    public static final int MODE_PAGED = 1;

    private final MangaService mangaService = new MangaService();
    private final LibAccountService accountService = LibAccountService.getInstance();
    private final YandexAuthService yandexService = YandexAuthService.getInstance();
    private final ExecutorService imagePool = Executors.newFixedThreadPool(6);
    private final AtomicInteger currentLoadToken = new AtomicInteger(0);
    private final Consumer<Boolean> onToggleSidebar;

    private final CardLayout viewLayout = new CardLayout();
    private final JPanel mainContainer = new JPanel(viewLayout);

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
    private final JComboBox<String> categoryTabs = new JComboBox<>(new String[]{
            "🔥 Популярное сейчас", "⭐ Избранная манга", "🕒 История чтения манги", "🔖 Мои закладки MangaLib"
    });
    private final SmoothScrollUtil.ResponsiveListPanel gridContainer = new SmoothScrollUtil.ResponsiveListPanel(new GridLayout(0, 2, 14, 14));
    private final JLabel statusLabel = new JLabel("Готово к поиску манги и манхвы");
    private final JButton authBtn = new JButton("🔑 Вход в Lib.social");

    private MangaItem currentManga;
    private final JLabel detailTitle = new JLabel();
    private final JLabel detailBadges = new JLabel();
    private final JTextArea detailDesc = new JTextArea();
    private final JLabel detailCover = new JLabel();
    private final JButton favToggleBtn = new JButton("☆ В избранное манги");
    private final DefaultListModel<MangaChapter> chapterListModel = new DefaultListModel<>();
    private final JList<MangaChapter> chapterJList = new JList<>(chapterListModel);
    private final JComboBox<String> languageCombo = new JComboBox<>();
    private final JButton sortOrderBtn = new JButton("Порядок: 1 → N");
    private final List<String> availableLangs = new ArrayList<>();
    private boolean chaptersAscending = true;
    private boolean updatingLanguage = false;

    private final CenteredScrollablePanel readerCanvasPanel = new CenteredScrollablePanel();
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

    private List<String> currentPages = new ArrayList<>();
    private List<MangaChapter> currentChapterList = new ArrayList<>();
    private List<MangaChapter> allChapters = new ArrayList<>();
    private String currentLanguage = "ru";
    private int currentChapterIndex = 0;
    private int currentPageIndex = 0;
    private boolean isZenActive = false;
    private Timer autoScrollTimer;

    private ThemePalette currentTheme = ThemePalette.byId("NIGHT_AMOLED");

    public MangaPanel() {
        this(null);
    }

    public MangaPanel(Consumer<Boolean> onToggleSidebar) {
        this.onToggleSidebar = onToggleSidebar;
        setLayout(new BorderLayout());
        setBackground(new Color(20, 22, 29));

        loadPreferences();

        mainContainer.add(buildExploreView(), "EXPLORE");
        mainContainer.add(buildDetailsView(), "DETAILS");
        mainContainer.add(buildReaderView(), "READER");

        add(mainContainer, BorderLayout.CENTER);

        yandexService.addAuthListener(user -> SwingUtilities.invokeLater(this::updateAuthButtonState));
        accountService.addAuthListener(user -> SwingUtilities.invokeLater(this::updateAuthButtonState));
        updateAuthButtonState();

        performSearch("");
    }

    private void loadPreferences() {
        mangaService.loadMangaState(favoriteManga, historyManga, lastReadChapterMap, readerPrefs);
        try {
            if (readerPrefs.containsKey("readingMode")) readingMode = Integer.parseInt(readerPrefs.get("readingMode"));
            if (readerPrefs.containsKey("alignmentMode")) alignmentMode = Integer.parseInt(readerPrefs.get("alignmentMode"));
            if (readerPrefs.containsKey("zoomWidth")) zoomWidth = Math.max(500, Math.min(1800, Integer.parseInt(readerPrefs.get("zoomWidth"))));
            if (readerPrefs.containsKey("pageGap")) pageGap = Math.max(0, Math.min(40, Integer.parseInt(readerPrefs.get("pageGap"))));
            if (readerPrefs.containsKey("dimmerPercent")) dimmerOpacity = Math.max(0, Math.min(70, Integer.parseInt(readerPrefs.get("dimmerPercent")))) / 100.0;
            if (readerPrefs.containsKey("bgColor")) readerBgColor = new Color(Integer.parseInt(readerPrefs.get("bgColor"), 16));
            if (readerPrefs.containsKey("autoHideSidebar")) autoHideSidebar = Boolean.parseBoolean(readerPrefs.get("autoHideSidebar"));
            if (readerPrefs.containsKey("autoScrollSpeed")) autoScrollSpeed = Math.max(10, Math.min(180, Integer.parseInt(readerPrefs.get("autoScrollSpeed"))));
        } catch (Exception ignored) {}
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
        mangaService.saveMangaState(favoriteManga, historyManga, lastReadChapterMap, readerPrefs);
    }

    private void updateAuthButtonState() {
        YandexUser ya = yandexService.getCurrentUser();
        LibUser lib = accountService.getCurrentUser();
        if (ya != null && ya.isAuthenticated()) {
            authBtn.setText("🟡 " + ya.getDisplayTitle());
            authBtn.setBackground(new Color(255, 214, 0));
            authBtn.setForeground(new Color(20, 20, 20));
        } else if (lib != null && lib.isAuthenticated()) {
            authBtn.setText("👤 " + lib.username);
            authBtn.setBackground(new Color(46, 125, 50));
            authBtn.setForeground(Color.WHITE);
        } else {
            authBtn.setText("🟡 Войти через Яндекс ID");
            authBtn.setBackground(new Color(55, 60, 75));
            authBtn.setForeground(new Color(230, 235, 245));
        }
    }

    private JPanel buildExploreView() {
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(new Color(20, 22, 29));
        root.setBorder(new EmptyBorder(18, 22, 18, 22));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);

        JPanel topTitleRow = new JPanel(new BorderLayout(10, 0));
        topTitleRow.setOpaque(false);
        topTitleRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel logo = new JLabel("📖 LuminaRead — Онлайн Манга и Манхва");
        logo.setFont(new Font("SansSerif", Font.BOLD, 20));
        logo.setForeground(new Color(255, 138, 101));

        authBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        authBtn.setFocusPainted(false);
        authBtn.addActionListener(e -> showAuthDialog());
        updateAuthButtonState();

        topTitleRow.add(logo, BorderLayout.WEST);
        topTitleRow.add(authBtn, BorderLayout.EAST);

        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filterBar.setOpaque(false);
        filterBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        filterBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        categoryTabs.addActionListener(e -> onCategoryChanged());
        sourceSelector.addActionListener(e -> {
            if (categoryTabs.getSelectedIndex() == 0) performSearch(searchField.getText());
        });
        filterBar.add(new JLabel("Раздел:") {{ setForeground(new Color(180, 185, 200)); }});
        filterBar.add(categoryTabs);
        filterBar.add(new JLabel("Источник:") {{ setForeground(new Color(180, 185, 200)); }});
        filterBar.add(sourceSelector);

        JPanel searchRow = new JPanel(new BorderLayout(10, 0));
        searchRow.setOpaque(false);
        searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        searchField.setFont(new Font("SansSerif", Font.PLAIN, 15));
        searchField.setToolTipText("Поиск манги, манхвы, маньхуа (отдельно от книг)...");
        searchField.addActionListener(e -> {
            categoryTabs.setSelectedIndex(0);
            performSearch(searchField.getText());
        });

        JButton findBtn = new JButton("🔍 Найти мангу / манхву");
        findBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        findBtn.setBackground(new Color(92, 107, 192));
        findBtn.setForeground(Color.WHITE);
        findBtn.setFocusPainted(false);
        findBtn.addActionListener(e -> {
            categoryTabs.setSelectedIndex(0);
            performSearch(searchField.getText());
        });

        searchRow.add(searchField, BorderLayout.CENTER);
        searchRow.add(findBtn, BorderLayout.EAST);

        header.add(topTitleRow);
        header.add(Box.createVerticalStrut(8));
        header.add(filterBar);
        header.add(Box.createVerticalStrut(8));
        header.add(searchRow);

        gridContainer.setBackground(new Color(20, 22, 29));
        JScrollPane scroll = new JScrollPane(gridContainer);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(new Color(20, 22, 29));
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        SmoothScrollUtil.enhance(scroll);

        statusLabel.setForeground(new Color(160, 168, 185));
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));

        root.add(header, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(statusLabel, BorderLayout.SOUTH);
        return root;
    }

    private void onCategoryChanged() {
        int idx = categoryTabs.getSelectedIndex();
        if (idx == 0) {
            performSearch(searchField.getText());
        } else if (idx == 1) {
            renderMangaItems(new ArrayList<>(favoriteManga.values()), "Избранная манга (" + favoriteManga.size() + ")");
        } else if (idx == 2) {
            renderMangaItems(new ArrayList<>(historyManga.values()), "История чтения манги (" + historyManga.size() + ")");
        } else if (idx == 3) {
            loadLibBookmarks();
        }
    }

    private void loadLibBookmarks() {
        YandexUser ya = yandexService.getCurrentUser();
        LibUser lib = accountService.getCurrentUser();

        if (!accountService.isLoggedIn() && ya != null && ya.isAuthenticated()) {
            String candidate = !ya.linkedLibUsername.isEmpty() ? ya.linkedLibUsername : ya.login;
            try {
                accountService.loginWithProfile(candidate);
            } catch (Exception ignored) {}
        }

        if (!accountService.isLoggedIn()) {
            gridContainer.removeAll();
            JPanel promptCard = new JPanel();
            promptCard.setLayout(new BoxLayout(promptCard, BoxLayout.Y_AXIS));
            promptCard.setOpaque(false);
            promptCard.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel msg = new JLabel(
                    (ya != null && ya.isAuthenticated())
                            ? "Вы вошли через Яндекс ID (" + ya.getDisplayTitle() + "). Укажите никнейм Lib.social для закладок:"
                            : "Войдите через Яндекс ID или Lib.social для синхронизации закладок MangaLib.",
                    SwingConstants.CENTER
            );
            msg.setForeground(Color.LIGHT_GRAY);
            msg.setFont(new Font("SansSerif", Font.BOLD, 14));
            msg.setAlignmentX(Component.CENTER_ALIGNMENT);

            JButton loginBtn = new JButton("🟡 Войти через Яндекс ID / Lib.social");
            loginBtn.setBackground(new Color(255, 214, 0));
            loginBtn.setForeground(new Color(20, 20, 20));
            loginBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
            loginBtn.setFocusPainted(false);
            loginBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
            loginBtn.addActionListener(e -> showAuthDialog());

            promptCard.add(Box.createVerticalStrut(60));
            promptCard.add(msg);
            promptCard.add(Box.createVerticalStrut(14));
            promptCard.add(loginBtn);

            gridContainer.add(promptCard);
            statusLabel.setText("Требуется авторизация для просмотра закладок");
            gridContainer.revalidate();
            gridContainer.repaint();
            return;
        }

        statusLabel.setText("⏳ Загрузка закладок пользователя из MangaLib...");
        gridContainer.removeAll();
        gridContainer.revalidate();
        gridContainer.repaint();

        new SwingWorker<List<LibBookmarkItem>, Void>() {
            @Override
            protected List<LibBookmarkItem> doInBackground() {

                return accountService.fetchUserBookmarks(1, 0);
            }

            @Override
            protected void done() {
                try {
                    List<LibBookmarkItem> bookmarks = get();
                    List<MangaItem> items = new ArrayList<>();
                    for (LibBookmarkItem b : bookmarks) {
                        String mangaId = b.slugUrl.startsWith("mgl:") ? b.slugUrl : ("mgl:" + b.slugUrl);
                        String desc = "Закладка MangaLib: " + b.getStatusName()
                                + (b.lastReadChapter.isEmpty() ? "" : " (последняя глава: " + b.lastReadChapter + ")");
                        items.add(new MangaItem(mangaId, b.title, b.rusTitle, desc, b.coverUrl, "MangaLib (RU)", b.getStatusName(), "★ Закладка"));
                    }
                    renderMangaItems(items, "Закладок MangaLib загружено: " + items.size());
                } catch (Exception ex) {
                    statusLabel.setText("Ошибка загрузки закладок: " + ex.getMessage());
                }
            }
        }.execute();
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
            if (onToggleSidebar != null) onToggleSidebar.accept(true);
            viewLayout.show(mainContainer, "EXPLORE");
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

        JPanel infoCard = new JPanel(new BorderLayout(18, 0));
        infoCard.setBackground(new Color(30, 33, 44));
        infoCard.setBorder(new EmptyBorder(16, 16, 16, 16));

        detailCover.setPreferredSize(new Dimension(145, 205));
        detailCover.setHorizontalAlignment(SwingConstants.CENTER);
        detailCover.setForeground(Color.GRAY);

        JPanel metaBox = new JPanel(new BorderLayout(0, 8));
        metaBox.setOpaque(false);
        detailTitle.setFont(new Font("SansSerif", Font.BOLD, 19));
        detailTitle.setForeground(Color.WHITE);
        detailBadges.setFont(new Font("SansSerif", Font.BOLD, 13));
        detailBadges.setForeground(new Color(255, 167, 38));

        detailDesc.setEditable(false);
        detailDesc.setLineWrap(true);
        detailDesc.setWrapStyleWord(true);
        detailDesc.setOpaque(false);
        detailDesc.setForeground(new Color(205, 210, 222));
        detailDesc.setFont(new Font("SansSerif", Font.PLAIN, 13));

        JPanel titleHeader = new JPanel(new GridLayout(2, 1, 0, 4));
        titleHeader.setOpaque(false);
        titleHeader.add(detailTitle);
        titleHeader.add(detailBadges);

        metaBox.add(titleHeader, BorderLayout.NORTH);
        metaBox.add(new JScrollPane(detailDesc) {{
            setBorder(null);
            setOpaque(false);
            getViewport().setOpaque(false);
            SmoothScrollUtil.enhance(this);
        }}, BorderLayout.CENTER);

        JPanel coverBox = new JPanel();
        coverBox.setLayout(new BoxLayout(coverBox, BoxLayout.Y_AXIS));
        coverBox.setOpaque(false);
        detailCover.setAlignmentX(Component.LEFT_ALIGNMENT);
        coverBox.add(detailCover);

        JLabel langLabel = new JLabel("Язык глав:");
        langLabel.setForeground(new Color(180, 185, 200));
        langLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        langLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        coverBox.add(Box.createVerticalStrut(10));
        coverBox.add(langLabel);
        coverBox.add(Box.createVerticalStrut(4));

        languageCombo.setMaximumSize(new Dimension(150, 28));
        languageCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        languageCombo.setEnabled(false);
        languageCombo.addActionListener(e -> onLanguageChanged());
        coverBox.add(languageCombo);

        coverBox.add(Box.createVerticalStrut(8));
        sortOrderBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        sortOrderBtn.setMaximumSize(new Dimension(150, 28));
        sortOrderBtn.setFocusPainted(false);
        sortOrderBtn.addActionListener(e -> toggleChapterSortOrder());
        coverBox.add(sortOrderBtn);

        infoCard.add(coverBox, BorderLayout.WEST);
        infoCard.add(metaBox, BorderLayout.CENTER);

        chapterJList.setBackground(new Color(28, 30, 40));
        chapterJList.setForeground(Color.WHITE);
        chapterJList.setFont(new Font("SansSerif", Font.PLAIN, 14));
        chapterJList.setFixedCellHeight(38);
        chapterJList.setSelectionBackground(new Color(92, 107, 192));

        JButton startReadBtn = new JButton("▶ Читать выбранную главу");
        startReadBtn.setFont(new Font("SansSerif", Font.BOLD, 14));
        startReadBtn.setBackground(new Color(67, 160, 71));
        startReadBtn.setForeground(Color.WHITE);
        startReadBtn.setFocusPainted(false);
        startReadBtn.addActionListener(e -> {
            int idx = chapterJList.getSelectedIndex();
            if (idx >= 0) openChapterAtIndex(idx);
            else if (chapterListModel.getSize() > 0) openChapterAtIndex(0);
        });

        chapterJList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int idx = chapterJList.locationToIndex(e.getPoint());
                    if (idx >= 0) openChapterAtIndex(idx);
                }
            }
        });

        JScrollPane chScroll = new JScrollPane(chapterJList);
        SmoothScrollUtil.enhance(chScroll);

        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setOpaque(false);
        body.add(infoCard, BorderLayout.NORTH);
        body.add(chScroll, BorderLayout.CENTER);
        body.add(startReadBtn, BorderLayout.SOUTH);

        panel.add(topNav, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private void toggleChapterSortOrder() {
        chaptersAscending = !chaptersAscending;
        sortOrderBtn.setText(chaptersAscending ? "Порядок: 1 → N" : "Порядок: N → 1");
        Collections.reverse(currentChapterList);
        chapterListModel.clear();
        for (MangaChapter ch : currentChapterList) {
            chapterListModel.addElement(ch);
        }
        if (!currentChapterList.isEmpty()) chapterJList.setSelectedIndex(0);
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
            if (onToggleSidebar != null) onToggleSidebar.accept(true);
            viewLayout.show(mainContainer, "DETAILS");
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
        if (autoScrollTimer != null && autoScrollTimer.isRunning()) {
            stopAutoScroll();
        } else {
            startAutoScroll();
        }
    }

    private void startAutoScroll() {
        if (autoScrollTimer != null) autoScrollTimer.stop();
        autoScrollBtn.setText("⏸ Стоп");
        autoScrollBtn.setBackground(new Color(229, 57, 53));
        autoScrollBtn.setForeground(Color.WHITE);

        autoScrollTimer = new Timer(30, e -> {
            if (readerScrollPane == null) return;
            JScrollBar vbar = readerScrollPane.getVerticalScrollBar();
            int max = vbar.getMaximum() - vbar.getVisibleAmount();
            int cur = vbar.getValue();
            if (cur >= max - 2) {
                stopAutoScroll();
                return;
            }
            int step = Math.max(1, (int) Math.round(autoScrollSpeed * 30.0 / 1000.0));
            vbar.setValue(Math.min(max, cur + step));
        });
        autoScrollTimer.start();
    }

    private void stopAutoScroll() {
        if (autoScrollTimer != null) {
            autoScrollTimer.stop();
            autoScrollTimer = null;
        }
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
        Window parentWin = SwingUtilities.getWindowAncestor(this);
        JDialog dlg = new JDialog(parentWin instanceof Frame ? (Frame) parentWin : null, "⚙️ Настройки чтения манги", Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setLayout(new BorderLayout());
        dlg.setResizable(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(new Color(28, 30, 40));
        content.setBorder(new EmptyBorder(18, 22, 18, 22));

        JLabel modeLbl = new JLabel("1. Режим чтения манги:");
        modeLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        modeLbl.setForeground(Color.WHITE);
        modeLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel modeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        modeBar.setOpaque(false);
        modeBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        JRadioButton rbScroll = new JRadioButton("📜 Лента (Webtoon с автоскроллом)", readingMode == MODE_SCROLL);
        JRadioButton rbPaged = new JRadioButton("📖 Постранично (манга ←/→)", readingMode == MODE_PAGED);
        for (JRadioButton rb : new JRadioButton[]{rbScroll, rbPaged}) {
            rb.setOpaque(false);
            rb.setForeground(new Color(220, 225, 235));
            rb.setFont(new Font("SansSerif", Font.PLAIN, 13));
        }
        ButtonGroup bgMode = new ButtonGroup();
        bgMode.add(rbScroll); bgMode.add(rbPaged);
        rbScroll.addActionListener(e -> setReadingMode(MODE_SCROLL));
        rbPaged.addActionListener(e -> setReadingMode(MODE_PAGED));
        modeBar.add(rbScroll);
        modeBar.add(rbPaged);

        JLabel alignLbl = new JLabel("2. Положение страницы на экране:");
        alignLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        alignLbl.setForeground(Color.WHITE);
        alignLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel alignBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        alignBar.setOpaque(false);
        alignBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        JRadioButton rbLeft = new JRadioButton("Слева", alignmentMode == ALIGN_LEFT);
        JRadioButton rbCenter = new JRadioButton("По центру (Рекомендуется)", alignmentMode == ALIGN_CENTER);
        JRadioButton rbFit = new JRadioButton("По ширине окна", alignmentMode == ALIGN_FIT_WIDTH);
        for (JRadioButton rb : new JRadioButton[]{rbLeft, rbCenter, rbFit}) {
            rb.setOpaque(false);
            rb.setForeground(new Color(220, 225, 235));
            rb.setFont(new Font("SansSerif", Font.PLAIN, 13));
        }
        ButtonGroup bgAlign = new ButtonGroup();
        bgAlign.add(rbLeft); bgAlign.add(rbCenter); bgAlign.add(rbFit);
        rbLeft.addActionListener(e -> { alignmentMode = ALIGN_LEFT; renderReaderPages(); savePreferences(); });
        rbCenter.addActionListener(e -> { alignmentMode = ALIGN_CENTER; renderReaderPages(); savePreferences(); });
        rbFit.addActionListener(e -> { alignmentMode = ALIGN_FIT_WIDTH; renderReaderPages(); savePreferences(); });

        alignBar.add(rbLeft);
        alignBar.add(rbCenter);
        alignBar.add(rbFit);

        JLabel widthLbl = new JLabel("3. Ширина страницы: " + zoomWidth + " px");
        widthLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        widthLbl.setForeground(Color.WHITE);
        widthLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JSlider widthSlider = new JSlider(500, 1600, zoomWidth);
        widthSlider.setOpaque(false);
        widthSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        widthSlider.setMaximumSize(new Dimension(460, 30));
        widthSlider.addChangeListener(e -> {
            zoomWidth = widthSlider.getValue();
            widthLbl.setText("3. Ширина страницы: " + zoomWidth + " px");
            if (alignmentMode == ALIGN_FIT_WIDTH) {
                alignmentMode = ALIGN_CENTER;
                rbCenter.setSelected(true);
            }
            renderReaderPages();
            savePreferences();
        });

        JPanel presetBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        presetBar.setOpaque(false);
        presetBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        int[] presets = {700, 860, 1050, 1300};
        for (int p : presets) {
            JButton pBtn = new JButton(p + " px");
            pBtn.setFont(new Font("SansSerif", Font.PLAIN, 12));
            pBtn.setFocusPainted(false);
            pBtn.addActionListener(ev -> {
                zoomWidth = p;
                widthSlider.setValue(p);
                alignmentMode = ALIGN_CENTER;
                rbCenter.setSelected(true);
                renderReaderPages();
                savePreferences();
            });
            presetBar.add(pBtn);
        }

        JLabel gapLbl = new JLabel("4. Отступ между сканами в ленте:");
        gapLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        gapLbl.setForeground(Color.WHITE);
        gapLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel gapBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        gapBar.setOpaque(false);
        gapBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        JRadioButton gap0 = new JRadioButton("0 px (Бесшовный вебтун)", pageGap == 0);
        JRadioButton gap8 = new JRadioButton("8 px", pageGap == 8);
        JRadioButton gap16 = new JRadioButton("16 px", pageGap == 16);
        for (JRadioButton rb : new JRadioButton[]{gap0, gap8, gap16}) {
            rb.setOpaque(false);
            rb.setForeground(new Color(220, 225, 235));
            rb.setFont(new Font("SansSerif", Font.PLAIN, 13));
        }
        ButtonGroup bgGap = new ButtonGroup();
        bgGap.add(gap0); bgGap.add(gap8); bgGap.add(gap16);
        gap0.addActionListener(e -> { pageGap = 0; renderReaderPages(); savePreferences(); });
        gap8.addActionListener(e -> { pageGap = 8; renderReaderPages(); savePreferences(); });
        gap16.addActionListener(e -> { pageGap = 16; renderReaderPages(); savePreferences(); });
        gapBar.add(gap0); gapBar.add(gap8); gapBar.add(gap16);

        JLabel speedLbl = new JLabel("5. Скорость автоскролла в ленте: " + autoScrollSpeed + " px/с");
        speedLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        speedLbl.setForeground(Color.WHITE);
        speedLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JSlider speedSlider = new JSlider(15, 120, autoScrollSpeed);
        speedSlider.setOpaque(false);
        speedSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        speedSlider.setMaximumSize(new Dimension(460, 30));
        speedSlider.addChangeListener(e -> {
            autoScrollSpeed = speedSlider.getValue();
            speedLbl.setText("5. Скорость автоскролла в ленте: " + autoScrollSpeed + " px/с");
            savePreferences();
        });

        int brightPct = (int) Math.round((1.0 - dimmerOpacity) * 100);
        JLabel brightLbl = new JLabel("6. Яркость страницы: " + brightPct + "%");
        brightLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        brightLbl.setForeground(Color.WHITE);
        brightLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JSlider brightSlider = new JSlider(30, 100, brightPct);
        brightSlider.setOpaque(false);
        brightSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        brightSlider.setMaximumSize(new Dimension(460, 30));
        brightSlider.addChangeListener(e -> {
            int val = brightSlider.getValue();
            brightLbl.setText("6. Яркость страницы: " + val + "%");
            dimmerOpacity = (100 - val) / 100.0;
            renderReaderPages();
            savePreferences();
        });

        JLabel bgLbl = new JLabel("7. Цвет фона читалки:");
        bgLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        bgLbl.setForeground(Color.WHITE);
        bgLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel colorBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        colorBar.setOpaque(false);
        colorBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        addBgColorOption(colorBar, "⬛ AMOLED", new Color(14, 15, 20), dlg);
        addBgColorOption(colorBar, "■ Тёмный", new Color(28, 30, 40), dlg);
        addBgColorOption(colorBar, "■ Сепия", new Color(42, 34, 28), dlg);
        addBgColorOption(colorBar, "◻ Бумага", new Color(244, 238, 220), dlg);

        JCheckBox zenCheck = new JCheckBox("Скрывать боковую панель приложения при чтении (Дзен-режим)", autoHideSidebar);
        zenCheck.setFont(new Font("SansSerif", Font.PLAIN, 13));
        zenCheck.setForeground(new Color(220, 225, 235));
        zenCheck.setOpaque(false);
        zenCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        zenCheck.addActionListener(e -> {
            autoHideSidebar = zenCheck.isSelected();
            if (onToggleSidebar != null) onToggleSidebar.accept(!autoHideSidebar);
            savePreferences();
        });

        JButton closeBtn = new JButton("✓ Готово");
        closeBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        closeBtn.setBackground(new Color(67, 160, 71));
        closeBtn.setForeground(Color.WHITE);
        closeBtn.setFocusPainted(false);
        closeBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        closeBtn.addActionListener(e -> dlg.dispose());

        content.add(modeLbl);
        content.add(modeBar);
        content.add(Box.createVerticalStrut(10));
        content.add(alignLbl);
        content.add(alignBar);
        content.add(Box.createVerticalStrut(10));
        content.add(widthLbl);
        content.add(widthSlider);
        content.add(presetBar);
        content.add(Box.createVerticalStrut(10));
        content.add(gapLbl);
        content.add(gapBar);
        content.add(Box.createVerticalStrut(10));
        content.add(speedLbl);
        content.add(speedSlider);
        content.add(Box.createVerticalStrut(10));
        content.add(brightLbl);
        content.add(brightSlider);
        content.add(Box.createVerticalStrut(10));
        content.add(bgLbl);
        content.add(colorBar);
        content.add(Box.createVerticalStrut(10));
        content.add(zenCheck);
        content.add(Box.createVerticalStrut(16));
        content.add(closeBtn);

        dlg.add(content);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private void addBgColorOption(JPanel panel, String label, Color c, JDialog dlg) {
        JButton btn = new JButton(label);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 12));
        btn.setFocusPainted(false);
        btn.addActionListener(e -> {
            readerBgColor = c;
            readerCanvasPanel.setBackground(c);
            readerScrollPane.getViewport().setBackground(c);
            savePreferences();
            readerCanvasPanel.repaint();
        });
        panel.add(btn);
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
        statusLabel.setText("⏳ Поиск манги и манхвы в онлайн-источниках...");
        gridContainer.removeAll();
        gridContainer.revalidate();
        gridContainer.repaint();

        String rawSource = (String) sourceSelector.getSelectedItem();
        final String source = rawSource != null && !"Все источники".equals(rawSource) ? rawSource : "Все источники";

        new SwingWorker<List<MangaItem>, Void>() {
            @Override
            protected List<MangaItem> doInBackground() {
                return mangaService.searchManga(query, source);
            }

            @Override
            protected void done() {
                try {
                    List<MangaItem> items = get();
                    renderMangaItems(items, "Найдено манги/манхвы: " + items.size());
                } catch (Exception e) {
                    statusLabel.setText("Ошибка сети: " + e.getMessage());
                }
            }
        }.execute();
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
        JPanel card = new JPanel(new BorderLayout(12, 0));
        card.setBackground(new Color(31, 34, 45));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(50, 55, 72), 1, true),
                new EmptyBorder(10, 10, 10, 10)
        ));

        JLabel coverLbl = new JLabel("Обложка", SwingConstants.CENTER);
        coverLbl.setPreferredSize(new Dimension(92, 132));
        coverLbl.setOpaque(true);
        coverLbl.setBackground(new Color(22, 24, 32));
        coverLbl.setForeground(new Color(120, 125, 140));

        if (item.coverUrl != null && !item.coverUrl.isEmpty()) {
            imagePool.submit(() -> {
                BufferedImage img = mangaService.loadImageCached(item.coverUrl);
                if (img != null) {
                    SwingUtilities.invokeLater(() -> {
                        coverLbl.setText("");
                        coverLbl.setIcon(new ImageIcon(createSmoothThumbnail(img, 92, 132)));
                    });
                }
            });
        }

        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setOpaque(false);

        JLabel title = new JLabel(item.getDisplayTitle());
        title.setFont(new Font("SansSerif", Font.BOLD, 14));
        title.setForeground(Color.WHITE);

        String lastRead = lastReadChapterMap.getOrDefault(item.id, "");
        JLabel badge = new JLabel(item.source + " • " + item.rating + (lastRead.isEmpty() ? "" : " • Читаете: " + lastRead));
        badge.setFont(new Font("SansSerif", Font.PLAIN, 12));
        badge.setForeground(new Color(255, 167, 38));

        String cleanD = item.description != null ? item.description : "";
        JTextArea desc = new JTextArea(cleanD.length() > 125 ? cleanD.substring(0, 125) + "..." : cleanD);
        desc.setLineWrap(true);
        desc.setWrapStyleWord(true);
        desc.setEditable(false);
        desc.setOpaque(false);
        desc.setForeground(new Color(175, 182, 198));
        desc.setFont(new Font("SansSerif", Font.PLAIN, 12));

        JButton readBtn = new JButton("Открыть тайтл 📖");
        readBtn.setBackground(new Color(79, 93, 175));
        readBtn.setForeground(Color.WHITE);
        readBtn.setFocusPainted(false);
        readBtn.addActionListener(e -> showMangaDetails(item));

        JPanel topInfo = new JPanel(new GridLayout(2, 1));
        topInfo.setOpaque(false);
        topInfo.add(title);
        topInfo.add(badge);

        right.add(topInfo, BorderLayout.NORTH);
        right.add(desc, BorderLayout.CENTER);
        right.add(readBtn, BorderLayout.SOUTH);

        card.add(coverLbl, BorderLayout.WEST);
        card.add(right, BorderLayout.CENTER);
        return card;
    }

    private void showMangaDetails(MangaItem item) {
        this.currentManga = item;
        historyManga.put(item.id, item);
        savePreferences();

        detailTitle.setText((item.rusTitle != null && !item.rusTitle.isEmpty()) ? item.rusTitle : item.title);
        detailBadges.setText("Источник: " + item.source + "   |   Рейтинг: " + item.rating + "   |   Статус: " + item.status);
        detailDesc.setText(item.description);
        favToggleBtn.setText(favoriteManga.containsKey(item.id) ? "★ В избранном манги" : "☆ В избранное манги");

        detailCover.setIcon(null);
        detailCover.setText("Загрузка...");
        chapterListModel.clear();
        viewLayout.show(mainContainer, "DETAILS");

        imagePool.submit(() -> {
            BufferedImage img = mangaService.loadImageCached(item.coverUrl);
            if (img != null) {
                SwingUtilities.invokeLater(() -> {
                    detailCover.setText("");
                    detailCover.setIcon(new ImageIcon(createSmoothThumbnail(img, 145, 205)));
                });
            }
        });

        new SwingWorker<List<MangaChapter>, Void>() {
            @Override
            protected List<MangaChapter> doInBackground() {
                return mangaService.fetchChapters(item);
            }

            @Override
            protected void done() {
                try {
                    allChapters = get();
                    buildLanguageSelector();
                    applyLanguageFilter();
                } catch (Exception ignored) {}
            }
        }.execute();

        if (item.source != null && (item.source.startsWith("MangaLib") || item.source.startsWith("Remanga"))) {
            final MangaItem target = item;
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() {
                    return mangaService.fetchDescription(target);
                }

                @Override
                protected void done() {
                    try {
                        String full = get();
                        if (full != null && !full.isEmpty() && currentManga != null
                                && currentManga.id.equals(target.id)) {
                            detailDesc.setText(full);
                            detailDesc.setCaretPosition(0);
                        }
                    } catch (Exception ignored) {}
                }
            }.execute();
        }
    }

    private void openChapterAtIndex(int idx) {
        if (currentChapterList == null || idx < 0 || idx >= currentChapterList.size()) return;
        currentChapterIndex = idx;
        final int targetInitialPage = currentPageIndex == -1 ? -1 : 0;
        currentPageIndex = 0;
        final int token = currentLoadToken.incrementAndGet();

        MangaChapter ch = currentChapterList.get(idx);
        if (currentManga != null) {
            lastReadChapterMap.put(currentManga.id, "Гл. " + ch.number);
            savePreferences();
        }

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

        viewLayout.show(mainContainer, "READER");

        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                return mangaService.fetchPageUrls(ch);
            }

            @Override
            protected void done() {
                if (token != currentLoadToken.get()) return;
                try {
                    currentPages = get();
                    if (targetInitialPage == -1 && currentPages != null && !currentPages.isEmpty()) {
                        currentPageIndex = Math.max(0, currentPages.size() - 1);
                    } else {
                        currentPageIndex = 0;
                    }
                    renderReaderPages();
                } catch (Exception ex) {
                    currentPages = Collections.emptyList();
                    currentPageIndex = 0;
                    renderReaderPages();
                }
            }
        }.execute();
    }

    private void goChapter(int dir) {
        if (currentChapterList == null || currentChapterList.isEmpty()) return;
        int nextCh = currentChapterIndex + dir;
        if (nextCh >= 0 && nextCh < currentChapterList.size()) {
            openChapterAtIndex(nextCh);
        }
    }

    private void onLanguageChanged() {
        if (updatingLanguage) return;
        if (languageCombo.getItemCount() == 0) return;
        int idx = languageCombo.getSelectedIndex();
        if (idx >= 0 && idx < availableLangs.size()) {
            currentLanguage = availableLangs.get(idx);
            applyLanguageFilter();
        }
    }

    private void buildLanguageSelector() {
        boolean hasEn = false, hasRu = false;
        for (MangaChapter ch : allChapters) {
            if ("en".equalsIgnoreCase(ch.language)) hasEn = true;
            if ("ru".equalsIgnoreCase(ch.language)) hasRu = true;
        }
        updatingLanguage = true;
        try {
            availableLangs.clear();
            languageCombo.removeAllItems();
            if (hasRu) { languageCombo.addItem("🇷🇺 Русский"); availableLangs.add("ru"); }
            if (hasEn) { languageCombo.addItem("🇬🇧 English"); availableLangs.add("en"); }
            if (hasRu) currentLanguage = "ru";
            else if (hasEn) currentLanguage = "en";
            else currentLanguage = "";
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
        currentChapterList = new ArrayList<>();
        for (MangaChapter ch : allChapters) {
            if (currentLanguage == null || currentLanguage.isEmpty() || ch.language.equalsIgnoreCase(currentLanguage)) {
                currentChapterList.add(ch);
            }
        }
        if (!chaptersAscending) {
            Collections.reverse(currentChapterList);
        }
        chapterListModel.clear();
        for (MangaChapter ch : currentChapterList) {
            chapterListModel.addElement(ch);
        }
        if (!currentChapterList.isEmpty()) {
            chapterJList.setSelectedIndex(0);
        }
    }

    private void renderReaderPages() {
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
                viewLayout.show(mainContainer, "DETAILS");
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
        PageImagePanel pagePanel = new PageImagePanel(
                pageNum,
                targetWidth,
                alignmentMode,
                dimmerOpacity,
                pageGap,
                clickToTurn,
                () -> navigateReader(-1),
                () -> navigateReader(1),
                () -> reloadSinglePage(url, pageNum, targetWidth, token)
        );
        readerCanvasPanel.add(pagePanel);

        imagePool.submit(() -> {
            if (token != currentLoadToken.get()) return;
            BufferedImage img = mangaService.loadImageCached(url);
            if (token != currentLoadToken.get()) return;
            SwingUtilities.invokeLater(() -> {
                if (token != currentLoadToken.get()) return;
                if (img != null) {
                    pagePanel.setImage(img, targetWidth, alignmentMode, dimmerOpacity, pageGap);
                    readerCanvasPanel.revalidate();
                } else {
                    pagePanel.setError("Не удалось загрузить страницу " + pageNum);
                }
            });
        });
    }

    private void reloadSinglePage(String url, int pageNum, int targetWidth, int token) {
        imagePool.submit(() -> {
            BufferedImage img = mangaService.loadImageCached(url);
            if (token != currentLoadToken.get()) return;
            SwingUtilities.invokeLater(() -> {
                if (token != currentLoadToken.get()) return;
                renderReaderPages();
            });
        });
    }

    private static class CenteredScrollablePanel extends JPanel implements Scrollable {
        public CenteredScrollablePanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 64;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 250;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {

            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static class PageImagePanel extends JPanel {
        private BufferedImage image;
        private final int pageNumber;
        private final JLabel statusLabel;
        private int targetWidth;
        private int alignmentMode;
        private double dimmerOpacity;
        private int pageGap;
        private final boolean clickToTurn;
        private final Runnable onClickLeft;
        private final Runnable onClickRight;
        private final Runnable onRetry;

        public PageImagePanel(int pageNumber, int targetWidth, int alignmentMode, double dimmerOpacity,
                              int pageGap, boolean clickToTurn, Runnable onClickLeft, Runnable onClickRight, Runnable onRetry) {
            this.pageNumber = pageNumber;
            this.targetWidth = targetWidth;
            this.alignmentMode = alignmentMode;
            this.dimmerOpacity = dimmerOpacity;
            this.pageGap = pageGap;
            this.clickToTurn = clickToTurn;
            this.onClickLeft = onClickLeft;
            this.onClickRight = onClickRight;
            this.onRetry = onRetry;

            setLayout(new BorderLayout());
            setOpaque(false);
            setAlignmentX(Component.CENTER_ALIGNMENT);

            statusLabel = new JLabel("Загрузка стр. " + pageNumber + "...", SwingConstants.CENTER);
            statusLabel.setForeground(new Color(150, 155, 175));
            statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
            setPreferredSize(new Dimension(targetWidth, 520));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 520));
            add(statusLabel, BorderLayout.CENTER);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (statusLabel.isVisible() && onRetry != null && e.getClickCount() == 1) {
                        statusLabel.setText("Повторная попытка стр. " + pageNumber + "...");
                        onRetry.run();
                        return;
                    }
                    if (clickToTurn) {
                        if (e.getX() < getWidth() / 3) {
                            if (onClickLeft != null) onClickLeft.run();
                        } else {
                            if (onClickRight != null) onClickRight.run();
                        }
                    }
                }
            });
        }

        public void setImage(BufferedImage img, int width, int align, double dimmer, int gap) {
            this.image = img;
            this.targetWidth = width;
            this.alignmentMode = align;
            this.dimmerOpacity = dimmer;
            this.pageGap = gap;
            statusLabel.setVisible(false);
            if (img != null && img.getWidth() > 0) {
                int panelW = getWidth() > 0 ? getWidth() : width;
                int drawW = (alignmentMode == ALIGN_FIT_WIDTH) ? panelW : Math.min(panelW, width);
                int h = (int) Math.round((double) img.getHeight() * drawW / img.getWidth());
                int totalH = h + gap;
                setPreferredSize(new Dimension(drawW, totalH));
                setMaximumSize(new Dimension(Integer.MAX_VALUE, totalH));
            }
            revalidate();
            repaint();
        }

        public void setError(String msg) {
            this.image = null;
            statusLabel.setText("⚠️ " + msg + " (кликните для повтора)");
            statusLabel.setForeground(new Color(255, 138, 101));
            statusLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            statusLabel.setVisible(true);
            revalidate();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image != null && image.getWidth() > 0) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

                int panelW = getWidth();
                int drawW = (alignmentMode == ALIGN_FIT_WIDTH) ? panelW : Math.min(panelW, targetWidth);
                int drawH = (int) Math.round((double) image.getHeight() * drawW / image.getWidth());

                int drawX = (alignmentMode == ALIGN_LEFT) ? 0 : Math.max(0, (panelW - drawW) / 2);
                int drawY = 0;

                g2.drawImage(image, drawX, drawY, drawW, drawH, null);

                if (dimmerOpacity > 0.0) {
                    g2.setColor(new Color(0, 0, 0, (int) Math.min(240, dimmerOpacity * 255)));
                    g2.fillRect(drawX, drawY, drawW, drawH);
                }
                g2.dispose();
            }
        }
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
        gridContainer.setBackground(theme.getBackground());
        statusLabel.setForeground(theme.getTextSecondary());
        revalidate();
        repaint();
    }
}
