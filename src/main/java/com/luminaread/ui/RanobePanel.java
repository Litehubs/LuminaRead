package com.luminaread.ui;

import com.luminaread.parser.PaginationEngine;
import com.luminaread.model.Chapter;
import com.luminaread.service.YandexAuthService;
import com.luminaread.service.YandexAuthService.YandexUser;
import com.luminaread.service.RanobeService;
import com.luminaread.service.RanobeService.RanobeChapter;
import com.luminaread.service.RanobeService.RanobeChapterContent;
import com.luminaread.service.RanobeService.RanobeItem;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class RanobePanel extends JPanel {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(RanobePanel.class.getName());
    private static final com.luminaread.service.ranobe.RanobeCoverLoader COVER_LOADER =
            new com.luminaread.service.ranobe.RanobeCoverLoader(com.luminaread.service.MediaCacheService.getInstance());

    public static final int MODE_SCROLL = 0;
    public static final int MODE_PAGED = 1;

    private final RanobeService ranobeService = new RanobeService();
    private final YandexAuthService yandexService = YandexAuthService.getInstance();
    private final PanelWorkers workers = new PanelWorkers(4);
    private volatile boolean disposed;
    private final Consumer<YandexUser> yandexListener = user -> SwingUtilities.invokeLater(() -> {
        if (!disposed) updateAuthButtonState();
    });
    private final AtomicInteger loadToken = new AtomicInteger(0);
    private final Consumer<Boolean> onToggleSidebar;

    private final CardLayout viewLayout = new CardLayout();
    private final JPanel mainContainer = new JPanel(viewLayout);

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
    private double autoScrollSubPixel = 0.0;
    private boolean isZenActive = false;

    private final JTextField searchField = new JTextField();
    private final JComboBox<String> categoryTabs = new JComboBox<>(new String[]{
            "🔥 Популярное сейчас", "⭐ Избранное ранобэ", "🕒 История чтения"
    });
    private final SmoothScrollUtil.ResponsiveListPanel gridContainer = new SmoothScrollUtil.ResponsiveListPanel(new GridLayout(0, 2, 14, 14));
    private final JLabel statusLabel = new JLabel("Готово к поиску ранобэ и новелл");
    private final JButton authBtn = new JButton("Войти через Яндекс ID");

    private RanobeItem currentRanobe;
    private final JLabel detailTitle = new JLabel();
    private final JLabel detailBadges = new JLabel();
    private final JTextArea detailDesc = new JTextArea();
    private final JLabel detailCover = new JLabel();
    private final JLabel chapterStatus = new JLabel();
    private final JButton favToggleBtn = new JButton("☆ В избранное ранобэ");
    private final DefaultListModel<RanobeChapter> chapterListModel = new DefaultListModel<>();
    private final JList<RanobeChapter> chapterJList = new JList<>(chapterListModel);
    private final JTextField chapterSearchField = new JTextField();
    private final JButton sortOrderBtn = new JButton("Порядок: 1 → N");
    private List<RanobeChapter> allChapters = new ArrayList<>();
    private List<RanobeChapter> filteredChapters = new ArrayList<>();
    private boolean chaptersAscending = true;

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

    private javax.swing.Timer autoScrollTimer;
    private ThemePalette currentTheme = ThemePalette.byId("NIGHT_AMOLED");

    public RanobePanel(Consumer<Boolean> onToggleSidebar) {
        this.onToggleSidebar = onToggleSidebar;
        setLayout(new BorderLayout());
        setBackground(new Color(20, 22, 29));

        loadPreferences();

        mainContainer.add(buildExploreView(), "EXPLORE");
        mainContainer.add(buildDetailsView(), "DETAILS");

        readerScrollPane = buildReaderScrollPane();
        mainContainer.add(buildReaderView(), "READER");

        add(mainContainer, BorderLayout.CENTER);

        yandexService.addAuthListener(yandexListener);
        updateAuthButtonState();

        performSearch("");
    }

    public void disposeResources() {
        if (disposed) return;
        disposed = true;
        yandexService.removeAuthListener(yandexListener);
        loadToken.incrementAndGet();
        workers.close();
        stopAutoScroll();
    }

    private void loadPreferences() {
        ranobeService.loadRanobeState(favoriteRanobe, historyRanobe, lastReadChapterMap, readerPrefs);
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
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(new Color(20, 22, 29));
        root.setBorder(new EmptyBorder(18, 22, 18, 22));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);

        JPanel topTitleRow = new JPanel(new BorderLayout(10, 0));
        topTitleRow.setOpaque(false);
        topTitleRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel logo = new JLabel("📜 LuminaRead — Ранобэ и Новеллы (RanobeLib)");
        logo.setFont(new Font("SansSerif", Font.BOLD, 20));
        logo.setForeground(new Color(129, 199, 132));

        authBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        authBtn.setFocusPainted(false);
        authBtn.addActionListener(e -> showAuthDialog());
        updateAuthButtonState();

        topTitleRow.add(logo, BorderLayout.WEST);
        topTitleRow.add(authBtn, BorderLayout.EAST);

        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filterBar.setOpaque(false);
        filterBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        categoryTabs.addActionListener(e -> onCategoryChanged());
        filterBar.add(new JLabel("Раздел:") {{ setForeground(new Color(180, 185, 200)); }});
        filterBar.add(categoryTabs);

        JPanel searchRow = new JPanel(new BorderLayout(10, 0));
        searchRow.setOpaque(false);
        searchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        searchField.setFont(new Font("SansSerif", Font.PLAIN, 15));
        searchField.setToolTipText("Поиск новелл и ранобэ по названию...");
        searchField.addActionListener(e -> {
            categoryTabs.setSelectedIndex(0);
            performSearch(searchField.getText());
        });

        JButton findBtn = new JButton("🔍 Найти ранобэ");
        findBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        findBtn.setBackground(new Color(67, 160, 71));
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
            renderRanobeItems(new ArrayList<>(favoriteRanobe.values()), "Избранное ранобэ (" + favoriteRanobe.size() + ")");
        } else if (idx == 2) {
            renderRanobeItems(new ArrayList<>(historyRanobe.values()), "История чтения (" + historyRanobe.size() + ")");
        }
    }

    private void performSearch(String query) {
        if (disposed) return;
        statusLabel.setText("⏳ Поиск ранобэ и новелл в RanobeLib...");
        gridContainer.removeAll();
        gridContainer.revalidate();
        gridContainer.repaint();

        workers.start(new SwingWorker<List<RanobeItem>, Void>() {
            @Override
            protected List<RanobeItem> doInBackground() {
                return ranobeService.searchRanobe(query);
            }

            @Override
            protected void done() {
                if (disposed) return;
                try {
                    List<RanobeItem> items = get();
                    renderRanobeItems(items, "Найдено тайтлов ранобэ: " + items.size());
                } catch (Exception e) {
                    statusLabel.setText("Ошибка сети: " + e.getMessage());
                }
            }
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
            workers.execute(() -> {
                Image img = loadImageAwt(item.coverUrl);
                if (img != null) {
                    Image scaled = img.getScaledInstance(92, 132, Image.SCALE_SMOOTH);
                    SwingUtilities.invokeLater(() -> {
                        if (disposed) return;
                        coverLbl.setText("");
                        coverLbl.setIcon(new ImageIcon(scaled));
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
        JLabel badge = new JLabel("RanobeLib • " + item.rating + (lastRead.isEmpty() ? "" : " • Читаете: " + lastRead));
        badge.setFont(new Font("SansSerif", Font.PLAIN, 12));
        badge.setForeground(new Color(129, 199, 132));

        String cleanD = item.description != null ? item.description : "";
        JTextArea desc = new JTextArea(cleanD.length() > 130 ? cleanD.substring(0, 130) + "..." : cleanD);
        desc.setLineWrap(true);
        desc.setWrapStyleWord(true);
        desc.setEditable(false);
        desc.setOpaque(false);
        desc.setForeground(new Color(175, 182, 198));
        desc.setFont(new Font("SansSerif", Font.PLAIN, 12));

        JButton readBtn = new JButton("Открыть ранобэ 📜");
        readBtn.setBackground(new Color(56, 142, 60));
        readBtn.setForeground(Color.WHITE);
        readBtn.setFocusPainted(false);
        readBtn.addActionListener(e -> showRanobeDetails(item));

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

    private JPanel buildDetailsView() {
        detailTitle.putClientProperty("html.disable", Boolean.TRUE);
        detailBadges.putClientProperty("html.disable", Boolean.TRUE);
        chapterStatus.setForeground(new Color(225, 170, 130));
        JPanel panel = new JPanel(new BorderLayout(16, 16));
        panel.setBackground(new Color(20, 22, 29));
        panel.setBorder(new EmptyBorder(18, 22, 18, 22));

        JPanel topNav = new JPanel(new BorderLayout());
        topNav.setOpaque(false);
        JButton backBtn = new JButton("← Назад в каталог ранобэ");
        backBtn.setFocusPainted(false);
        backBtn.addActionListener(e -> {
            if (onToggleSidebar != null) onToggleSidebar.accept(true);
            viewLayout.show(mainContainer, "EXPLORE");
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
        detailBadges.setForeground(new Color(129, 199, 132));

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

        coverBox.add(Box.createVerticalStrut(10));
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
        chapterJList.setSelectionBackground(new Color(67, 160, 71));

        chapterJList.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int idx = chapterJList.locationToIndex(e.getPoint());
                    if (idx >= 0) openChapterAtIndex(idx);
                }
            }
        });

        JScrollPane chScroll = new JScrollPane(chapterJList);
        SmoothScrollUtil.enhance(chScroll);

        JPanel chSearchRow = new JPanel(new BorderLayout(8, 0));
        chSearchRow.setOpaque(false);
        chapterSearchField.setFont(new Font("SansSerif", Font.PLAIN, 13));
        chapterSearchField.setToolTipText("Фильтр глав по номеру или названию...");
        chapterSearchField.addActionListener(e -> filterChapters(chapterSearchField.getText()));
        chSearchRow.add(new JLabel("Поиск главы:") {{ setForeground(Color.LIGHT_GRAY); }}, BorderLayout.WEST);
        chSearchRow.add(chapterSearchField, BorderLayout.CENTER);

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

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(infoCard, BorderLayout.NORTH);

        JPanel centerListPanel = new JPanel(new BorderLayout(0, 6));
        centerListPanel.setOpaque(false);
        JPanel chapterHeader = new JPanel(new BorderLayout(0, 6));
        chapterHeader.setOpaque(false);
        chapterHeader.add(chSearchRow, BorderLayout.NORTH);
        chapterHeader.add(chapterStatus, BorderLayout.SOUTH);
        centerListPanel.add(chapterHeader, BorderLayout.NORTH);
        centerListPanel.add(chScroll, BorderLayout.CENTER);
        body.add(centerListPanel, BorderLayout.CENTER);
        body.add(startReadBtn, BorderLayout.SOUTH);

        panel.add(topNav, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private void showRanobeDetails(RanobeItem item) {
        if (disposed) return;
        this.currentRanobe = item;
        historyRanobe.put(item.id, item);
        savePreferences();

        detailTitle.setText(item.getDisplayTitle());
        detailBadges.setText("RanobeLib   |   Рейтинг: " + item.rating + "   |   Статус: " + item.status);
        detailDesc.setText(item.description);
        favToggleBtn.setText(favoriteRanobe.containsKey(item.id) ? "★ В избранном ранобэ" : "☆ В избранное ранобэ");

        detailCover.setIcon(null);
        detailCover.setText("Загрузка...");
        chapterListModel.clear();
        allChapters = Collections.emptyList();
        filteredChapters = new ArrayList<>();
        chapterStatus.setText("Загрузка глав...");
        viewLayout.show(mainContainer, "DETAILS");

        workers.execute(() -> {
            Image img = loadImageAwt(item.coverUrl);
            if (img != null) {
                Image scaled = img.getScaledInstance(145, 205, Image.SCALE_SMOOTH);
                SwingUtilities.invokeLater(() -> {
                    if (disposed) return;
                    detailCover.setText("");
                    detailCover.setIcon(new ImageIcon(scaled));
                });
            }
        });

        workers.start(new SwingWorker<List<RanobeChapter>, Void>() {
            @Override
            protected List<RanobeChapter> doInBackground() {
                return ranobeService.fetchChapters(item.id);
            }

            @Override
            protected void done() {
                if (disposed) return;
                try {
                    allChapters = get();
                    filterChapters("");
                    chapterStatus.setText(allChapters.isEmpty() ? "Главы не найдены" : " ");
                } catch (Exception ex) {
                    LOG.log(java.util.logging.Level.WARNING, "Не удалось загрузить главы ранобэ", ex);
                    chapterStatus.setText("Не удалось загрузить главы. Повторите попытку.");
                }
            }
        });

        workers.start(new SwingWorker<RanobeItem, Void>() {
            @Override
            protected RanobeItem doInBackground() {
                return ranobeService.fetchRanobeDetails(item);
            }

            @Override
            protected void done() {
                if (disposed) return;
                try {
                    RanobeItem full = get();
                    if (full != null && currentRanobe != null && currentRanobe.id.equals(full.id)) {
                        currentRanobe = full;
                        detailDesc.setText(full.description + (full.genres.isEmpty() ? "" : "\n\nЖанры: " + full.getGenresString())
                                + (full.authors.isEmpty() ? "" : "\nАвтор: " + full.getAuthorsString()));
                        detailDesc.setCaretPosition(0);
                    }
                } catch (Exception ex) {
                    LOG.log(java.util.logging.Level.WARNING, "Не удалось загрузить описание ранобэ", ex);
                }
            }
        });
    }

    private void toggleChapterSortOrder() {
        chaptersAscending = !chaptersAscending;
        sortOrderBtn.setText(chaptersAscending ? "Порядок: 1 → N" : "Порядок: N → 1");
        Collections.reverse(filteredChapters);
        chapterListModel.clear();
        for (RanobeChapter ch : filteredChapters) {
            chapterListModel.addElement(ch);
        }
        if (!filteredChapters.isEmpty()) chapterJList.setSelectedIndex(0);
    }

    private void filterChapters(String q) {
        String clean = q != null ? q.trim().toLowerCase(Locale.ROOT) : "";
        filteredChapters = new ArrayList<>();
        for (RanobeChapter ch : allChapters) {
            if (clean.isEmpty() || ch.number.contains(clean) || ch.title.toLowerCase(Locale.ROOT).contains(clean)) {
                filteredChapters.add(ch);
            }
        }
        if (!chaptersAscending) {
            Collections.reverse(filteredChapters);
        }
        chapterListModel.clear();
        for (RanobeChapter ch : filteredChapters) {
            chapterListModel.addElement(ch);
        }
        if (!filteredChapters.isEmpty()) {

            String last = (currentRanobe != null) ? lastReadChapterMap.getOrDefault(currentRanobe.id, "") : "";
            int selectIdx = 0;
            if (!last.isEmpty()) {
                for (int i = 0; i < filteredChapters.size(); i++) {
                    if (last.equals("Гл. " + filteredChapters.get(i).number)) {
                        selectIdx = i;
                        break;
                    }
                }
            }
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
            if (onToggleSidebar != null) onToggleSidebar.accept(true);
            viewLayout.show(mainContainer, "DETAILS");
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
        if (autoScrollTimer != null && autoScrollTimer.isRunning()) {
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
        autoScrollSubPixel = 0.0;

        autoScrollTimer = new javax.swing.Timer(16, e -> {
            JScrollBar bar = readerScrollPane.getVerticalScrollBar();
            int extent = readerScrollPane.getViewport().getHeight();
            int max = bar.getMaximum() - extent;
            if (max <= 0) return;

            if (bar.getValue() >= max - 2) {
                stopAutoScroll();

                navigateChapter(1);
            } else {
                autoScrollSubPixel += (double) autoScrollSpeed / 60.0;
                if (autoScrollSubPixel >= 1.0) {
                    int step = (int) autoScrollSubPixel;
                    autoScrollSubPixel -= step;
                    int nextVal = Math.min(max, bar.getValue() + step);
                    bar.setValue(nextVal);
                    SmoothScrollUtil.syncTarget(readerScrollPane, nextVal);
                }
            }
        });
        autoScrollTimer.start();
    }

    void stopAutoScroll() {
        if (autoScrollTimer != null) {
            autoScrollTimer.stop();
            autoScrollTimer = null;
        }
        autoScrollBtn.setText("▶ Автоскролл");
        autoScrollBtn.setBackground(new Color(67, 160, 71));
    }

    private void openChapterAtIndex(int idx) {
        if (idx < 0 || idx >= filteredChapters.size()) return;
        currentChapterIndex = idx;
        currentContent = null;
        currentPages = Collections.emptyList();
        currentPageIndex = 0;
        final int token = loadToken.incrementAndGet();

        stopAutoScroll();
        currentChapter = filteredChapters.get(idx);
        final RanobeChapter requestedChapter = currentChapter;
        final String requestedTitleId = currentRanobe != null ? currentRanobe.id : null;

        readerTitleLabel.setText(currentChapter.toString() + " — загрузка...");
        textEditorPane.setText("<html><body style='text-align:center; padding:50px; color:#aaa; font-family:sans-serif;'>"
                + "<h2>⏳ Загрузка главы " + com.luminaread.util.HtmlSanitizer.escapeHtml(requestedChapter.number) + "...</h2></body></html>");
        viewLayout.show(mainContainer, "READER");

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
        if (filteredChapters == null || filteredChapters.isEmpty()) return;
        int nextCh = currentChapterIndex + dir;
        if (nextCh >= 0 && nextCh < filteredChapters.size()) {
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
            if (disposed) return;
            if (readerScrollPane != null) {
                SmoothScrollUtil.resetScroll(readerScrollPane, 0);
            }
        });
    }

    private void renderChapterText() {
        if (disposed) return;
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

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private void showTextSettingsDialog() {
        Window parentWin = SwingUtilities.getWindowAncestor(this);
        JDialog dlg = new JDialog(parentWin instanceof Frame ? (Frame) parentWin : null, "⚙️ Настройки текста ранобэ", Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setLayout(new BorderLayout());
        dlg.setResizable(false);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(new Color(28, 30, 40));
        content.setBorder(new EmptyBorder(18, 22, 18, 22));

        JLabel modeLbl = new JLabel("1. Режим отображения:");
        modeLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        modeLbl.setForeground(Color.WHITE);

        JPanel modeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        modeBar.setOpaque(false);
        JRadioButton rbScroll = new JRadioButton("📜 Лента (Скролл)", readingMode == MODE_SCROLL);
        JRadioButton rbPaged = new JRadioButton("📖 Постранично", readingMode == MODE_PAGED);
        ButtonGroup bgMode = new ButtonGroup();
        bgMode.add(rbScroll); bgMode.add(rbPaged);
        for (JRadioButton rb : new JRadioButton[]{rbScroll, rbPaged}) {
            rb.setOpaque(false); rb.setForeground(new Color(220, 225, 235));
        }
        rbScroll.addActionListener(e -> { setReadingMode(MODE_SCROLL); });
        rbPaged.addActionListener(e -> { setReadingMode(MODE_PAGED); });
        modeBar.add(rbScroll); modeBar.add(rbPaged);

        JLabel fontLbl = new JLabel("2. Размер шрифта: " + fontSize + " пт");
        fontLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        fontLbl.setForeground(Color.WHITE);

        JSlider fontSlider = new JSlider(14, 34, fontSize);
        fontSlider.setOpaque(false);
        fontSlider.setMaximumSize(new Dimension(460, 30));
        fontSlider.addChangeListener(e -> {
            fontSize = fontSlider.getValue();
            fontLbl.setText("2. Размер шрифта: " + fontSize + " пт");
            renderChapterText();
            savePreferences();
        });

        JComboBox<String> fontCombo = new JComboBox<>(new String[]{
                "Georgia", "Palatino", "Times New Roman", "Verdana", "Trebuchet MS", "Monospaced"
        });
        fontCombo.setSelectedItem(fontFamily);
        fontCombo.setMaximumSize(new Dimension(200, 28));
        fontCombo.addActionListener(e -> {
            fontFamily = (String) fontCombo.getSelectedItem();
            renderChapterText();
            savePreferences();
        });

        JLabel widthLbl = new JLabel("3. Ширина текстовой колонки: " + textBlockWidth + " px");
        widthLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        widthLbl.setForeground(Color.WHITE);

        JSlider widthSlider = new JSlider(550, 1300, textBlockWidth);
        widthSlider.setOpaque(false);
        widthSlider.setMaximumSize(new Dimension(460, 30));
        widthSlider.addChangeListener(e -> {
            textBlockWidth = widthSlider.getValue();
            widthLbl.setText("3. Ширина текстовой колонки: " + textBlockWidth + " px");
            renderChapterText();
            savePreferences();
        });

        JLabel themeLbl = new JLabel("4. Цветовая тема:");
        themeLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        themeLbl.setForeground(Color.WHITE);

        JPanel themeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        themeBar.setOpaque(false);
        addThemeButton(themeBar, "⬛ AMOLED", "NIGHT_AMOLED");
        addThemeButton(themeBar, "■ Nord", "NORD");
        addThemeButton(themeBar, "■ Сепия", "SEPIA");
        addThemeButton(themeBar, "◻ Бумага", "DAY_PAPER");
        addThemeButton(themeBar, "🍃 Мята", "MINT");

        JLabel speedLbl = new JLabel("5. Скорость автопрокрутки: " + autoScrollSpeed + " px/с");
        speedLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        speedLbl.setForeground(Color.WHITE);

        JSlider speedSlider = new JSlider(15, 120, autoScrollSpeed);
        speedSlider.setOpaque(false);
        speedSlider.setMaximumSize(new Dimension(460, 30));
        speedSlider.addChangeListener(e -> {
            autoScrollSpeed = speedSlider.getValue();
            speedLbl.setText("5. Скорость автопрокрутки: " + autoScrollSpeed + " px/с");
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
        content.add(fontLbl);
        content.add(fontSlider);
        content.add(fontCombo);
        content.add(Box.createVerticalStrut(10));
        content.add(widthLbl);
        content.add(widthSlider);
        content.add(Box.createVerticalStrut(10));
        content.add(themeLbl);
        content.add(themeBar);
        content.add(Box.createVerticalStrut(10));
        content.add(speedLbl);
        content.add(speedSlider);
        content.add(Box.createVerticalStrut(16));
        content.add(closeBtn);

        dlg.add(content);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private void addThemeButton(JPanel bar, String name, String presetId) {
        JButton btn = new JButton(name);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 12));
        btn.setFocusPainted(false);
        btn.addActionListener(e -> {
            themePresetId = presetId;
            renderChapterText();
            savePreferences();
        });
        bar.add(btn);
    }

    public void showAuthDialog() {
        AuthDialog.show(this, currentTheme, () -> {
            updateAuthButtonState();
            onCategoryChanged();
        });
    }

    private static Image loadImageAwt(String url) {
        return COVER_LOADER.load(url);
    }

    public void applyTheme(ThemePalette theme) {
        if (theme == null) return;
        this.currentTheme = theme;
        setBackground(theme.getBackground());
        mainContainer.setBackground(theme.getBackground());
        gridContainer.setBackground(theme.getBackground());
        statusLabel.setForeground(theme.getTextSecondary());
        if (currentContent != null) {
            renderChapterText();
        }
        revalidate();
        repaint();
    }
}
