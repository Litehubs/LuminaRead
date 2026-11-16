package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.BookContent;
import com.luminaread.model.Bookmark;
import com.luminaread.model.Chapter;
import com.luminaread.model.Highlight;
import com.luminaread.model.ReaderSettings;
import com.luminaread.service.DictionaryService;
import com.luminaread.service.IDictionaryService;
import com.luminaread.service.ITtsService;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.TtsService;
import com.luminaread.util.HtmlSanitizer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultHighlighter;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class ReaderPanel extends JPanel {
    private static final DateTimeFormatter CLOCK_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final LibraryStorageService storageService;
    private final ITtsService ttsService;
    private final ReaderDictionaryLookup dictionaryLookup;
    private final Runnable onBackToLibrary;
    private final ConsumerThemeCallback onThemeChangedGlobal;

    public interface ConsumerThemeCallback {
        void onThemeChanged(ThemePalette newPalette);
    }

    private Book currentBook;
    private BookContent currentContent;
    private final ReaderBookLoader bookLoader;
    private List<String> currentChapterPages = Collections.emptyList();
    private ReaderTextSearch.Layout chapterLayout;
    private static final int SCROLL_TO_END = -1;
    private Integer pendingScrollOffset;
    private boolean disposed;
    private final Timer restoreScrollTimer;
    private final Timer saveScrollTimer;
    private int currentChapterIndex = 0;
    private int currentPageIndex = 0;
    private final LatestSwingRequest<ReaderTextSearch.Match> searchRequest = new LatestSwingRequest<>();
    private ReaderTextSearch.Match searchMatch;
    private Pattern searchPattern;
    private Object searchHighlight;

    private boolean zenDistractionFree = false;
    private static final long WHEEL_CHAPTER_COOLDOWN_NANOS = 250_000_000L;
    private static final long AUTO_SCROLL_SHORT_PAGE_NANOS = 1_500_000_000L;
    private long lastWheelChapterChange;
    private int lastWheelChapterDirection;
    private boolean autoScrollActive = false;
    private double autoScrollSubPixel = 0.0;
    private long pageShownAt;
    private final Timer autoScrollTimer;
    private final Timer readingTimeTrackerTimer;
    private int dragStartY = -1;
    private double dragStartDimmer = 0.0;

    private final JPanel topControlContainer;
    private final JPanel primaryBar;
    private final JPanel secondaryBar;
    private final JSplitPane mainSplitPane;
    private final DefaultListModel<Chapter> tocListModel;
    private final JList<Chapter> tocList;
    private final JScrollPane tocScrollPane;

    private final JEditorPane bookEditorPane;
    private final JScrollPane editorScrollPane;
    private JPanel readerCanvasWrapper;

    private final JButton prevBtn;
    private final JButton nextBtn;
    private final JButton autoScrollBtn;
    private final JButton ttsBtn;
    private final ReaderSearchBar searchBar;
    private final JSlider brightnessSlider;
    private final JCheckBox eyeCareCheckbox;

    private final JPanel bottomStatusBar;
    private final JLabel statusLeftLabel;
    private final JLabel statusRightLabel;

    private ThemePalette currentTheme;

    public ReaderPanel(LibraryStorageService storageService,
                       TtsService ttsService,
                       DictionaryService dictionaryService,
                       Runnable onBackToLibrary,
                       ConsumerThemeCallback onThemeChangedGlobal) {
        this(storageService, (ITtsService) ttsService, (IDictionaryService) dictionaryService, onBackToLibrary, onThemeChangedGlobal);
    }

    public ReaderPanel(LibraryStorageService storageService,
                       ITtsService ttsService,
                       IDictionaryService dictionaryService,
                       Runnable onBackToLibrary,
                       ConsumerThemeCallback onThemeChangedGlobal) {
        this.storageService = storageService;
        this.ttsService = ttsService;
        this.dictionaryLookup = new ReaderDictionaryLookup(dictionaryService);
        this.onBackToLibrary = onBackToLibrary;
        this.onThemeChangedGlobal = onThemeChangedGlobal;
        this.bookLoader = new ReaderBookLoader(storageService::loadBookContent);
        this.currentTheme = ThemePalette.byId(storageService.getSettings().getThemeId());

        setLayout(new BorderLayout(0, 0));

        topControlContainer = new JPanel(new BorderLayout());
        primaryBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        secondaryBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));

        JButton backBtn = ModernUiFactory.createFlatButton("< Полка", currentTheme);
        backBtn.addActionListener(e -> {
            stopActiveFeatures();
            this.onBackToLibrary.run();
        });

        JButton tocBtn = ModernUiFactory.createFlatButton("Оглавление", currentTheme);
        tocBtn.addActionListener(e -> toggleTocSidebar());

        prevBtn = ModernUiFactory.createFlatButton("< Пред.", currentTheme);
        prevBtn.addActionListener(e -> navigateBackward());

        nextBtn = ModernUiFactory.createFlatButton("След. >", currentTheme);
        nextBtn.addActionListener(e -> navigateForward());

        JButton typographyBtn = ModernUiFactory.createPrimaryButton("Aa Вид и Тема", currentTheme);
        typographyBtn.addActionListener(e -> openTypographyAndThemeDialog());

        autoScrollBtn = ModernUiFactory.createFlatButton("Автопрокрутка", currentTheme);
        autoScrollBtn.addActionListener(e -> toggleAutoScroll());

        ttsBtn = ModernUiFactory.createFlatButton("Озвучка (TTS)", currentTheme);
        ttsBtn.addActionListener(e -> toggleTtsDialogOrStop());

        JButton addBookmarkBtn = ModernUiFactory.createFlatButton("+ Закладка", currentTheme);
        addBookmarkBtn.addActionListener(e -> createBookmarkAtCurrentPosition());

        JButton addQuoteBtn = ModernUiFactory.createFlatButton("+ Цитата", currentTheme);
        addQuoteBtn.addActionListener(e -> createHighlightFromSelection());

        JButton dictBtn = ModernUiFactory.createFlatButton("Словарь", currentTheme);
        dictBtn.addActionListener(e -> translateSelectedText());

        JButton zenBtn = ModernUiFactory.createFlatButton("Дзен (F11)", currentTheme);
        zenBtn.addActionListener(e -> toggleZenMode());

        primaryBar.add(backBtn);
        primaryBar.add(tocBtn);
        primaryBar.add(prevBtn);
        primaryBar.add(nextBtn);
        primaryBar.add(typographyBtn);
        primaryBar.add(autoScrollBtn);
        primaryBar.add(ttsBtn);
        primaryBar.add(addBookmarkBtn);
        primaryBar.add(addQuoteBtn);
        primaryBar.add(dictBtn);
        primaryBar.add(zenBtn);

        JButton fontMinusBtn = ModernUiFactory.createFlatButton("A-", currentTheme);
        fontMinusBtn.addActionListener(e -> adjustFontSize(-2));

        JButton fontPlusBtn = ModernUiFactory.createFlatButton("A+", currentTheme);
        fontPlusBtn.addActionListener(e -> adjustFontSize(+2));

        searchBar = new ReaderSearchBar(currentTheme, this::searchInBook, this::clearSearch);

        ReaderSettings settings = storageService.getSettings();
        int initialBrightness = (int) Math.round((1.0 - settings.getDimmerOpacity()) * 100);
        brightnessSlider = new JSlider(30, 100, Math.max(30, Math.min(100, initialBrightness)));
        brightnessSlider.setPreferredSize(new Dimension(110, 22));
        brightnessSlider.setOpaque(false);
        brightnessSlider.setToolTipText("Яркость страницы (можно менять вертикальным движением мыши по левому краю книги)");
        brightnessSlider.addChangeListener(e -> {
            double dimmer = (100 - brightnessSlider.getValue()) / 100.0;
            storageService.getSettings().setDimmerOpacity(dimmer);
            if (readerCanvasWrapper != null) {
                readerCanvasWrapper.repaint();
            }
        });

        eyeCareCheckbox = new JCheckBox("Защита глаз", settings.isWarmEyeCareFilter());
        eyeCareCheckbox.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        eyeCareCheckbox.setOpaque(false);
        eyeCareCheckbox.addActionListener(e -> {
            storageService.getSettings().setWarmEyeCareFilter(eyeCareCheckbox.isSelected());
            UiPersistenceErrors.save(this, "Не удалось сохранить настройку чтения.", storageService::saveState);
            if (readerCanvasWrapper != null) {
                readerCanvasWrapper.repaint();
            }
        });

        secondaryBar.add(new JLabel("Шрифт:"));
        secondaryBar.add(fontMinusBtn);
        secondaryBar.add(fontPlusBtn);
        secondaryBar.add(searchBar);
        secondaryBar.add(new JLabel("  Яркость:"));
        secondaryBar.add(brightnessSlider);
        secondaryBar.add(eyeCareCheckbox);

        topControlContainer.add(primaryBar, BorderLayout.NORTH);
        topControlContainer.add(secondaryBar, BorderLayout.SOUTH);
        add(topControlContainer, BorderLayout.NORTH);

        tocListModel = new DefaultListModel<>();
        tocList = new JList<>(tocListModel);
        tocList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tocList.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        tocList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && tocList.getSelectedIndex() >= 0) {
                int selectedIdx = tocList.getSelectedIndex();
                if (selectedIdx != currentChapterIndex) {
                    goToChapterAndPage(selectedIdx, 0);
                }
            }
        });
        tocScrollPane = SmoothScrollUtil.enhanceAndReturn(new JScrollPane(tocList));
        tocScrollPane.setPreferredSize(new Dimension(250, 400));
        tocScrollPane.setBorder(BorderFactory.createTitledBorder("Оглавление книги"));

        bookEditorPane = new JEditorPane() {
            @Override
            protected void paintComponent(Graphics g) {
                if (g instanceof Graphics2D g2) {
                    ModernUiFactory.enableHighQualityRendering(g2);
                }
                super.paintComponent(g);
            }
        };
        bookEditorPane.setEditable(false);
        bookEditorPane.setEditorKit(new ReaderHtmlKit());
        bookEditorPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        if (bookEditorPane.getCaret() instanceof DefaultCaret) {
            ((DefaultCaret) bookEditorPane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }

        setupReaderMouseZonesAndGestures();

        editorScrollPane = SmoothScrollUtil.enhanceAndReturn(new JScrollPane(bookEditorPane));
        editorScrollPane.setBorder(BorderFactory.createEmptyBorder());
        SmoothScrollUtil.onWheelBoundary(editorScrollPane, this::scrollToAdjacentChapter);

        editorScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        editorScrollPane.getVerticalScrollBar().setUnitIncrement(42);
        restoreScrollTimer = new Timer(400, e -> restoreScrollPosition(true));
        restoreScrollTimer.setRepeats(false);
        saveScrollTimer = new Timer(700, e -> saveReadingProgress());
        saveScrollTimer.setRepeats(false);
        editorScrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (pendingScrollOffset != null) {
                if (editorScrollPane.getViewport().getHeight() > 0 && !restoreScrollTimer.isRunning()) {
                    restoreScrollTimer.start();
                }
            } else {
                recordScrollPosition();
            }
            updateStatusBar();
        });

        readerCanvasWrapper = new JPanel(new BorderLayout()) {
            @Override
            public void paint(Graphics g) {
                super.paint(g);
                ReaderSettings s = storageService.getSettings();
                if (s.isWarmEyeCareFilter() || s.getDimmerOpacity() > 0.01) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    if (s.isWarmEyeCareFilter()) {
                        g2.setColor(new Color(255, 190, 95, 34));
                        g2.fillRect(0, 0, getWidth(), getHeight());
                    }
                    if (s.getDimmerOpacity() > 0.01) {
                        int alpha = (int) Math.round(Math.min(0.75, s.getDimmerOpacity()) * 255);
                        g2.setColor(new Color(0, 0, 0, alpha));
                        g2.fillRect(0, 0, getWidth(), getHeight());
                    }
                    g2.dispose();
                }
            }
        };
        readerCanvasWrapper.add(editorScrollPane, BorderLayout.CENTER);

        mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tocScrollPane, readerCanvasWrapper);
        mainSplitPane.setDividerLocation(250);
        mainSplitPane.setBorder(BorderFactory.createEmptyBorder());
        add(mainSplitPane, BorderLayout.CENTER);

        bottomStatusBar = new JPanel(new BorderLayout(12, 0));
        bottomStatusBar.setBorder(BorderFactory.createEmptyBorder(6, 18, 6, 18));

        statusLeftLabel = new JLabel("Книга не открыта");
        statusLeftLabel.setFont(ModernUiFactory.uiFont(Font.PLAIN, 12));

        statusRightLabel = new JLabel("0%");
        statusRightLabel.setFont(ModernUiFactory.uiFont(Font.BOLD, 12));

        bottomStatusBar.add(statusLeftLabel, BorderLayout.WEST);
        bottomStatusBar.add(statusRightLabel, BorderLayout.EAST);
        add(bottomStatusBar, BorderLayout.SOUTH);

        autoScrollTimer = new Timer(16, e -> performAutoScrollStep());

        readingTimeTrackerTimer = new Timer(5000, e -> {
            if (currentBook != null && isShowing()) {
                currentBook.addReadingSeconds(5);
                updateStatusBar();
            }
        });
        readingTimeTrackerTimer.start();

        setupKeyboardShortcuts();
        applyTheme(currentTheme);
    }

    private void setupReaderMouseZonesAndGestures() {
        bookEditorPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getX() <= 45) {
                    dragStartY = e.getY();
                    dragStartDimmer = storageService.getSettings().getDimmerOpacity();
                } else {
                    dragStartY = -1;
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                String sel = bookEditorPane.getSelectedText();
                if (sel != null && !sel.isBlank()) return;
                if (!storageService.getSettings().isTapZonesEnabled()) return;

                int w = editorScrollPane.getViewport().getWidth();
                int h = editorScrollPane.getViewport().getHeight();
                if (w <= 0 || h <= 0) {
                    w = bookEditorPane.getWidth();
                    h = bookEditorPane.getHeight();
                }
                if (w <= 0 || h <= 0) return;

                double rx = (double) e.getX() / w;
                double ry = (double) e.getY() / h;

                if (rx < 0.25) {
                    navigateBackward();
                } else if (rx > 0.75) {
                    navigateForward();
                } else if (ry > 0.35 && ry < 0.65) {
                    toggleZenMode();
                }
            }
        });

        bookEditorPane.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStartY >= 0 && e.getX() <= 75) {
                    int deltaY = e.getY() - dragStartY;
                    double newDimmer = Math.max(0.0, Math.min(0.70, dragStartDimmer + (deltaY / 400.0)));
                    storageService.getSettings().setDimmerOpacity(newDimmer);
                    int sliderVal = (int) Math.round((1.0 - newDimmer) * 100);
                    brightnessSlider.setValue(Math.max(30, Math.min(100, sliderVal)));
                    if (readerCanvasWrapper != null) {
                        readerCanvasWrapper.repaint();
                    }
                }
            }
        });
    }

    private void setupKeyboardShortcuts() {
        var im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var am = getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "nextPage");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "nextPage");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), "nextPage");
        am.put("nextPage", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!searchBar.hasFieldFocus()) navigateForward();
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "prevPage");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), "prevPage");
        am.put("prevPage", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!searchBar.hasFieldFocus()) navigateBackward();
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.META_DOWN_MASK), "findInBook");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "findInBook");
        am.put("findInBook", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (zenDistractionFree) toggleZenMode();
                searchBar.focusField();
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), "zenToggle");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "exitZen");
        am.put("zenToggle", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                toggleZenMode();
            }
        });
        am.put("exitZen", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (zenDistractionFree) toggleZenMode();
            }
        });
    }

    public void openBook(Book book) {
        openBook(book, null);
    }

    void openBook(Book book, ReadingPosition requestedPosition) {
        if (book == null || disposed) return;
        stopActiveFeatures();
        restoreScrollTimer.stop();
        pendingScrollOffset = null;
        currentBook = book;
        lastWheelChapterDirection = 0;
        currentContent = null;
        chapterLayout = null;
        currentChapterPages = Collections.emptyList();
        tocListModel.clear();
        bookEditorPane.setText("<html><body><p style='text-align:center;'>Загружаю книгу…</p></body></html>");
        statusLeftLabel.setText("Загружаю: " + book.getTitle());
        statusRightLabel.setText("Подождите");

        bookLoader.open(book, content -> {
            currentContent = content;
            book.setLastOpenedTimestamp(System.currentTimeMillis());
            for (Chapter chapter : currentContent.getChapters()) tocListModel.addElement(chapter);
            ReadingPosition position = requestedPosition != null ? requestedPosition
                    : new ReadingPosition(book.getCurrentChapterIndex(), book.getCurrentPageInChapter(), book.getScrollOffset());
            goToChapterAndPage(position.chapterIndex(), position.pageIndex(), Math.max(0, position.scrollOffset()));
        }, ex -> {
            currentContent = null;
            statusLeftLabel.setText("Не удалось открыть книгу");
            statusRightLabel.setText("");
            JOptionPane.showMessageDialog(ReaderPanel.this,
                    "Не удалось загрузить содержимое книги:\n" + ex.getMessage(),
                    "Ошибка чтения", JOptionPane.ERROR_MESSAGE);
        });
    }

    public void goToChapterAndPage(int chapterIdx, int pageIdx) {
        goToChapterAndPage(chapterIdx, pageIdx, 0);
    }

    private void goToChapterAndPage(int chapterIdx, int pageIdx, int scrollOffset) {
        if (currentContent == null || currentContent.getChapters().isEmpty()) return;
        int totalChapters = currentContent.getChapters().size();
        this.currentChapterIndex = Math.max(0, Math.min(totalChapters - 1, chapterIdx));

        Chapter chapter = currentContent.getChapters().get(currentChapterIndex);
        chapterLayout = ReaderTextSearch.Layout.from(storageService.getSettings());
        currentChapterPages = chapterLayout.pages(chapter);
        currentPageIndex = Math.max(0, Math.min(currentChapterPages.size() - 1, pageIdx));

        if (tocList.getSelectedIndex() != currentChapterIndex) {
            tocList.setSelectedIndex(currentChapterIndex);
        }

        showPageAt(scrollOffset);
    }

    private boolean scrollToAdjacentChapter(int direction) {
        if (currentContent == null || storageService.getSettings().getReadingMode() != ReaderSettings.ReadingMode.SCROLL) {
            return false;
        }

        int adjacentChapter = currentChapterIndex + direction;
        if (adjacentChapter < 0 || adjacentChapter >= currentContent.getChapters().size()) {
            return false;
        }

        long now = System.nanoTime();
        if (direction == lastWheelChapterDirection && now - lastWheelChapterChange < WHEEL_CHAPTER_COOLDOWN_NANOS) {
            return true;
        }
        lastWheelChapterChange = now;
        lastWheelChapterDirection = direction;
        goToChapterAndPage(adjacentChapter, 0, direction < 0 ? SCROLL_TO_END : 0);
        return true;
    }

    private void showPageAt(int scrollOffset) {
        clearSearch();
        currentBook.setScrollOffset(Math.max(0, scrollOffset));
        renderCurrentViewport(scrollOffset);
        saveReadingProgress();
    }

    private void renderCurrentViewport() {
        if (currentBook != null) renderCurrentViewport(currentBook.getScrollOffset());
    }

    private void renderCurrentViewport(int scrollOffset) {
        if (disposed || currentContent == null || currentContent.getChapters().isEmpty()) return;
        restoreScrollTimer.stop();
        pendingScrollOffset = scrollOffset;
        ReaderSettings settings = storageService.getSettings();

        HTMLEditorKit kit = new ReaderHtmlKit();
        StyleSheet styleSheet = kit.getStyleSheet();

        String bgHex = ThemePalette.toHex(currentTheme.getCardBackground());
        String fgHex = ThemePalette.toHex(currentTheme.getTextPrimary());
        String secHex = ThemePalette.toHex(currentTheme.getTextSecondary());
        String accHex = ThemePalette.toHex(currentTheme.getAccent());

        styleSheet.addRule("body { "
                + "background-color: " + bgHex + "; "
                + "color: " + fgHex + "; "
                + "font-family: '" + settings.getFontFamily() + "', Georgia, serif; "
                + "font-size: " + settings.getFontSize() + "pt; "
                + "line-height: " + settings.getLineHeight() + "; "
                + "text-align: " + settings.getTextAlignment().getCssValue() + "; "
                + "margin-top: " + settings.getVerticalMarginPx() + "px; "
                + "margin-bottom: " + settings.getVerticalMarginPx() + "px; "
                + "margin-left: " + settings.getHorizontalMarginPx() + "px; "
                + "margin-right: " + settings.getHorizontalMarginPx() + "px; "
                + "}");
        styleSheet.addRule("p { margin-top: 0px; margin-bottom: " + settings.getParagraphSpacingPx()
                + "px; text-indent: 22px; text-align: " + settings.getTextAlignment().getCssValue() + "; }");
        styleSheet.addRule("h1, h2, h3, h4 { color: " + accHex + "; text-align: left; margin-top: 18px; margin-bottom: 12px; }");
        styleSheet.addRule("blockquote { color: " + secHex + "; margin-left: 28px; margin-right: 20px; font-style: italic; text-align: left; }");
        styleSheet.addRule("code { font-family: monospace; background-color: " + ThemePalette.toHex(currentTheme.getSurface()) + "; }");
        styleSheet.addRule("img { max-width: 100%; height: auto; }");

        removeSearchHighlight();
        bookEditorPane.setEditorKit(kit);
        bookEditorPane.setBackground(currentTheme.getCardBackground());

        String htmlFragment = currentChapterPages.isEmpty() ? "<p></p>" : currentChapterPages.get(currentPageIndex);
        htmlFragment = injectSavedHighlights(ReaderHtmlKit.preparePage(htmlFragment));

        int viewportWidth = editorScrollPane.getViewport().getWidth();
        if (viewportWidth > 0) {
            int imgWidth = Math.max(260, viewportWidth - 70);
            htmlFragment = htmlFragment.replaceAll("(?i)(<img[^>]*?)\\s+width\\s*=\\s*(['\"])[^'\"]*\\2", "$1");
            htmlFragment = htmlFragment.replace("<img ", "<img width='" + imgWidth + "' ");
        }

        bookEditorPane.setText(ReaderHtmlKit.pageHtml(htmlFragment));
        showSearchHighlight(false);
        pageShownAt = System.nanoTime();
        var document = bookEditorPane.getDocument();
        bookEditorPane.setCaretPosition(0);
        SwingUtilities.invokeLater(() -> {
            if (bookEditorPane.getDocument() == document) restoreScrollPosition(false);
        });
        updateStatusBar();
    }

    private void restoreScrollPosition(boolean layoutSettled) {
        if (pendingScrollOffset == null) return;
        JScrollBar bar = editorScrollPane.getVerticalScrollBar();
        if (bar.getVisibleAmount() <= 0 || editorScrollPane.getViewport().getHeight() <= 0) return;

        int bottom = Math.max(0, bar.getMaximum() - bar.getVisibleAmount());
        int requested = pendingScrollOffset;
        boolean waitForLayout = requested == SCROLL_TO_END ? bottom == 0 : requested > bottom;
        if (!layoutSettled && waitForLayout) {
            if (!restoreScrollTimer.isRunning()) restoreScrollTimer.start();
            return;
        }

        restoreScrollTimer.stop();
        SmoothScrollUtil.resetScroll(editorScrollPane, requested == SCROLL_TO_END ? bottom : Math.min(bottom, requested));
        pendingScrollOffset = null;
        recordScrollPosition();
    }

    private void recordScrollPosition() {
        if (disposed || currentBook == null || currentContent == null) return;
        JScrollBar bar = editorScrollPane.getVerticalScrollBar();
        if (bar.getVisibleAmount() <= 0 || editorScrollPane.getViewport().getHeight() <= 0) return;
        int offset = bar.getValue();
        if (currentBook.getScrollOffset() != offset) {
            currentBook.setScrollOffset(offset);
            saveScrollTimer.restart();
        }
    }

    private String injectSavedHighlights(String html) {
        List<Highlight> highlights = currentBook != null ? storageService.getHighlights(currentBook.getId()) : List.of();
        return ReaderMarkupDecorator.decorate(html, highlights, currentChapterIndex);
    }

    public void navigateForward() {
        if (currentContent == null || currentContent.getChapters().isEmpty()) return;
        ReaderSettings settings = storageService.getSettings();

        if (settings.getReadingMode() == ReaderSettings.ReadingMode.PAGED) {
            if (currentPageIndex + 1 < currentChapterPages.size()) {
                currentPageIndex++;
                showPageAt(0);
            } else if (currentChapterIndex + 1 < currentContent.getChapters().size()) {
                goToChapterAndPage(currentChapterIndex + 1, 0);
            }
        } else {
            JScrollBar vBar = editorScrollPane.getVerticalScrollBar();
            int bottom = vBar.getMaximum() - vBar.getVisibleAmount();
            if (vBar.getValue() < bottom) {
                int step = Math.max(1, (int) (editorScrollPane.getViewport().getHeight() * 0.85));
                vBar.setValue(Math.min(bottom, vBar.getValue() + step));
                saveReadingProgress();
            } else if (currentChapterIndex + 1 < currentContent.getChapters().size()) {
                goToChapterAndPage(currentChapterIndex + 1, 0);
            }
        }
    }

    public void navigateBackward() {
        if (currentContent == null || currentContent.getChapters().isEmpty()) return;
        ReaderSettings settings = storageService.getSettings();

        if (settings.getReadingMode() == ReaderSettings.ReadingMode.PAGED) {
            if (currentPageIndex > 0) {
                currentPageIndex--;
                showPageAt(0);
            } else if (currentChapterIndex > 0) {
                int prevChap = currentChapterIndex - 1;
                List<String> prevPages = chapterLayout.pages(currentContent.getChapters().get(prevChap));
                goToChapterAndPage(prevChap, Math.max(0, prevPages.size() - 1));
            }
        } else {
            JScrollBar vBar = editorScrollPane.getVerticalScrollBar();
            int prevVal = vBar.getValue() - (int) (editorScrollPane.getViewport().getHeight() * 0.85);
            if (vBar.getValue() > vBar.getMinimum()) {
                vBar.setValue(Math.max(0, prevVal));
                saveReadingProgress();
            } else if (currentChapterIndex > 0) {
                goToChapterAndPage(currentChapterIndex - 1, 0, SCROLL_TO_END);
            }
        }
    }

    private void saveReadingProgress() {
        saveScrollTimer.stop();
        if (currentBook == null || currentContent == null || currentContent.getChapters().isEmpty()) return;
        int totalChapters = currentContent.getChapters().size();
        double chapterWeight = 100.0 / totalChapters;
        double withinChapterRatio = currentChapterPages.size() <= 1
                ? 1.0
                : (double) (currentPageIndex + 1) / currentChapterPages.size();
        double progress = (currentChapterIndex * chapterWeight) + (withinChapterRatio * chapterWeight);

        currentBook.setCurrentChapterIndex(currentChapterIndex);
        currentBook.setCurrentPageInChapter(currentPageIndex);
        currentBook.setProgressPercent(progress);
        updateStatusBar();
        try {
            storageService.saveState();
        } catch (java.io.UncheckedIOException | SecurityException ex) {
            java.util.logging.Logger.getLogger(ReaderPanel.class.getName())
                    .log(java.util.logging.Level.WARNING, "Не удалось сохранить прогресс чтения", ex);
            statusRightLabel.setText("Прогресс не сохранён");
        }
    }

    private void updateStatusBar() {
        if (currentBook == null || currentContent == null || currentContent.getChapters().isEmpty()) {
            statusLeftLabel.setText("Откройте книгу из библиотеки или скачайте из онлайн-каталога");
            statusRightLabel.setText(CLOCK_FMT.format(LocalTime.now()));
            return;
        }
        Chapter ch = currentContent.getChapters().get(currentChapterIndex);
        String pageInfo = storageService.getSettings().getReadingMode() == ReaderSettings.ReadingMode.PAGED
                ? String.format("Стр. %d из %d", currentPageIndex + 1, Math.max(1, currentChapterPages.size()))
                : "Режим свитка";

        int totalWords = Math.max(1, currentBook.getTotalWords());
        int wordsLeft = (int) Math.round(totalWords * (1.0 - currentBook.getProgressPercent() / 100.0));
        int estMinutesLeft = Math.max(1, wordsLeft / 215);

        statusLeftLabel.setText(String.format("%s  •  Глава %d/%d: %s  •  %s",
                currentBook.getTitle(),
                currentChapterIndex + 1,
                currentContent.getChapters().size(),
                ch.getTitle(),
                pageInfo));

        statusRightLabel.setText(String.format("Прогресс: %.1f%%  •  Осталось ~%d мин  •  %s",
                currentBook.getProgressPercent(),
                estMinutesLeft,
                CLOCK_FMT.format(LocalTime.now())));
    }

    private void toggleTocSidebar() {
        boolean visible = tocScrollPane.isVisible();
        tocScrollPane.setVisible(!visible);
        if (!visible) {
            mainSplitPane.setDividerLocation(250);
        }
        mainSplitPane.revalidate();
    }

    public void toggleZenMode() {
        zenDistractionFree = !zenDistractionFree;
        topControlContainer.setVisible(!zenDistractionFree);
        if (zenDistractionFree) {
            tocScrollPane.setVisible(false);
        }
        bottomStatusBar.setVisible(!zenDistractionFree || storageService.getSettings().isShowStatusBarInReader());
        revalidate();
        repaint();
    }

    private void adjustFontSize(int delta) {
        ReaderSettings s = storageService.getSettings();
        s.setFontSize(s.getFontSize() + delta);
        int scaledChars = Math.max(900, Math.min(4500, (int) (2400.0 * 19.0 / s.getFontSize())));
        s.setCharsPerPage(scaledChars);
        UiPersistenceErrors.save(this, "Не удалось сохранить размер шрифта.", storageService::saveState);
        refreshReadingLayout();
    }

    private void toggleAutoScroll() {
        autoScrollActive = !autoScrollActive;
        autoScrollSubPixel = 0.0;
        if (autoScrollActive) {
            pageShownAt = System.nanoTime();
            autoScrollBtn.setText("Стоп автоскролл");
            autoScrollTimer.start();
        } else {
            autoScrollBtn.setText("Автопрокрутка");
            autoScrollTimer.stop();
        }
    }

    private void performAutoScrollStep() {
        if (!autoScrollActive || currentContent == null) return;
        JScrollBar vBar = editorScrollPane.getVerticalScrollBar();
        int max = vBar.getMaximum() - vBar.getVisibleAmount();
        if (max <= 0 && System.nanoTime() - pageShownAt < AUTO_SCROLL_SHORT_PAGE_NANOS) return;

        if (vBar.getValue() < max) {
            autoScrollSubPixel += (double) storageService.getSettings().getAutoScrollSpeed() / 60.0;
            if (autoScrollSubPixel >= 1.0) {
                int step = (int) autoScrollSubPixel;
                autoScrollSubPixel -= step;
                int nextVal = Math.min(max, vBar.getValue() + step);
                vBar.setValue(nextVal);
                SmoothScrollUtil.syncTarget(editorScrollPane, nextVal);
            }
        } else {
            if (currentPageIndex + 1 < currentChapterPages.size()) {
                currentPageIndex++;
                showPageAt(0);
            } else if (currentChapterIndex + 1 < currentContent.getChapters().size()) {
                goToChapterAndPage(currentChapterIndex + 1, 0);
            } else {
                toggleAutoScroll();
            }
        }
    }

    private void toggleTtsDialogOrStop() {
        if (ttsService.isSpeaking()) {
            ttsService.stop();
            ttsBtn.setText("Озвучка (TTS)");
            return;
        }
        if (currentContent == null || currentContent.getChapters().isEmpty()) return;

        String[] options = {
                "Без таймера сна", "Таймер сна: 15 мин", "Таймер сна: 30 мин", "Таймер сна: 60 мин"
        };
        int choice = JOptionPane.showOptionDialog(
                this,
                "Запустить голосовое чтение (TTS) текущей главы?\nСкорость речи: "
                        + storageService.getSettings().getTtsRateWpm() + " слов/мин.",
                "Голосовое чтение (TTS)",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );
        if (choice < 0) return;

        int sleepMins = switch (choice) {
            case 1 -> 15;
            case 2 -> 30;
            case 3 -> 60;
            default -> 0;
        };

        String textToRead = bookEditorPane.getSelectedText();
        if (textToRead == null || textToRead.isBlank()) {
            String currentHtml = currentChapterPages.isEmpty() ? "" : currentChapterPages.get(currentPageIndex);
            textToRead = HtmlSanitizer.htmlToPlainText(currentHtml);
        }

        ttsBtn.setText("Стоп озвучка");
        ttsService.speakText(textToRead, storageService.getSettings().getTtsRateWpm(), sleepMins,
                () -> SwingUtilities.invokeLater(() -> ttsBtn.setText("Озвучка (TTS)")));
    }

    private void createBookmarkAtCurrentPosition() {
        if (currentBook == null || currentContent == null || currentContent.getChapters().isEmpty()) return;
        Chapter ch = currentContent.getChapters().get(currentChapterIndex);
        String defaultNote = ch.getTitle() + " (стр. " + (currentPageIndex + 1) + ")";
        String userLabel = JOptionPane.showInputDialog(this, "Название закладки:", defaultNote);
        if (userLabel == null) return;

        String pagePlain = HtmlSanitizer.htmlToPlainText(
                currentChapterPages.isEmpty() ? "" : currentChapterPages.get(currentPageIndex));
        String snippet = pagePlain.length() > 120 ? pagePlain.substring(0, 120) + "..." : pagePlain;

        Bookmark bm = new Bookmark(
                currentBook.getId(),
                currentBook.getTitle(),
                currentChapterIndex,
                ch.getTitle(),
                currentPageIndex,
                editorScrollPane.getVerticalScrollBar().getValue(),
                userLabel,
                snippet
        );
        if (UiPersistenceErrors.save(this, "Не удалось сохранить закладку.", () -> storageService.addBookmark(bm))) {
            JOptionPane.showMessageDialog(this, "Закладка сохранена!", "Закладка", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void createHighlightFromSelection() {
        if (currentBook == null || currentContent == null || currentContent.getChapters().isEmpty()) return;
        String selected = bookEditorPane.getSelectedText();
        if (selected == null || selected.isBlank()) {
            selected = JOptionPane.showInputDialog(this,
                    "Выделите текст мышью на странице или введите цитату вручную:");
        }
        if (selected == null || selected.isBlank()) return;

        JPanel panel = new JPanel(new GridLayout(0, 1, 6, 6));
        JComboBox<String> colorCombo = new JComboBox<>(new String[]{
                "Желтый маркер (#FDE047)",
                "Мятно-зеленый (#86EFAC)",
                "Небесно-голубой (#7DD3FC)",
                "Розовый коралл (#F9A8D4)"
        });
        JTextField noteField = new JTextField();
        panel.add(new JLabel("Цвет выделения:"));
        panel.add(colorCombo);
        panel.add(new JLabel("Ваша заметка / комментарий (необязательно):"));
        panel.add(noteField);

        int res = JOptionPane.showConfirmDialog(this, panel,
                "Сохранение цитаты и заметки", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            String hex = switch (colorCombo.getSelectedIndex()) {
                case 1 -> "#86EFAC";
                case 2 -> "#7DD3FC";
                case 3 -> "#F9A8D4";
                default -> "#FDE047";
            };
            Chapter ch = currentContent.getChapters().get(currentChapterIndex);
            Highlight hl = new Highlight(
                    currentBook.getId(),
                    currentBook.getTitle(),
                    currentChapterIndex,
                    ch.getTitle(),
                    selected,
                    noteField.getText(),
                    hex
            );
            UiPersistenceErrors.save(this, "Не удалось сохранить цитату.", () -> storageService.addHighlight(hl));
            renderCurrentViewport();
        }
    }

    private void translateSelectedText() {
        String selected = bookEditorPane.getSelectedText();
        if (selected == null || selected.isBlank()) {
            selected = JOptionPane.showInputDialog(this, "Введите слово или выделите фразу в книге для перевода:");
        }
        if (selected == null || selected.isBlank()) return;

        dictionaryLookup.lookup(selected.trim(), translation -> JOptionPane.showMessageDialog(
                    ReaderPanel.this,
                    translation,
                    "Словарь и Переводчик LuminaRead",
                    JOptionPane.INFORMATION_MESSAGE
            ), error -> {
                java.util.logging.Logger.getLogger(ReaderPanel.class.getName())
                        .log(java.util.logging.Level.WARNING, "Не удалось получить перевод", error);
                JOptionPane.showMessageDialog(ReaderPanel.this, "Не удалось получить перевод.",
                        "Ошибка словаря", JOptionPane.ERROR_MESSAGE);
            });
    }

    private void searchInBook(ReaderTextSearch.Direction direction) {
        if (currentChapterPages.isEmpty() || disposed) return;
        Pattern query = ReaderTextSearch.query(searchBar.query());
        if (query.pattern().isEmpty()) {
            clearSearch();
            return;
        }

        if (autoScrollActive) toggleAutoScroll();
        var start = new ReaderTextSearch.Page(currentChapterIndex, currentPageIndex);
        int previous = searchMatch == null ? -1 : searchMatch.occurrence();
        List<Chapter> chapters = currentContent.getChapters();
        var layout = chapterLayout;
        searchBar.setStatus("Поиск…");
        searchRequest.start(() -> ReaderTextSearch.find(chapters, layout, query, start, previous, direction), match -> {
            if (match == null) {
                searchBar.setStatus("Совпадений нет");
                return;
            }
            if (!match.page().equals(start)) goToChapterAndPage(match.page().chapter(), match.page().index());
            searchMatch = match;
            searchPattern = query;
            searchBar.setStatus(match.wrapped() ? "Поиск по кругу" : "");
            var document = bookEditorPane.getDocument();
            SwingUtilities.invokeLater(() -> {
                if (searchMatch == match && bookEditorPane.getDocument() == document) showSearchHighlight(true);
            });
        }, error -> {
            Logger.getLogger(ReaderPanel.class.getName()).log(Level.WARNING, "Ошибка поиска в книге", error);
            searchBar.setStatus("Ошибка поиска");
        });
    }

    private void clearSearch() {
        searchRequest.cancel();
        searchMatch = null;
        searchPattern = null;
        searchBar.setStatus("");
        removeSearchHighlight();
    }

    private void removeSearchHighlight() {
        if (searchHighlight != null) {
            bookEditorPane.getHighlighter().removeHighlight(searchHighlight);
            searchHighlight = null;
        }
    }

    private void showSearchHighlight(boolean reveal) {
        removeSearchHighlight();
        if (searchMatch == null) return;
        try {
            var range = ReaderTextSearch.locate(bookEditorPane.getDocument(), searchPattern, searchMatch.occurrence());
            if (range == null) {
                searchBar.setStatus("Не удалось выделить текст");
                return;
            }
            Color color = currentTheme.isDark() ? new Color(37, 74, 133) : new Color(250, 204, 21);
            searchHighlight = bookEditorPane.getHighlighter().addHighlight(range.start(), range.end(),
                    new DefaultHighlighter.DefaultHighlightPainter(color));
            if (reveal) {
                var view = bookEditorPane.modelToView2D(range.start());
                if (view != null && !bookEditorPane.getVisibleRect().contains(view.getBounds())) {
                    restoreScrollTimer.stop();
                    pendingScrollOffset = null;
                    int center = view.getBounds().y - editorScrollPane.getViewport().getHeight() / 3;
                    SmoothScrollUtil.resetScroll(editorScrollPane, Math.max(0, center));
                }
            }
        } catch (BadLocationException error) {
            Logger.getLogger(ReaderPanel.class.getName()).log(Level.WARNING, "Ошибка выделения в книге", error);
            searchBar.setStatus("Не удалось выделить текст");
        }
    }

    public void openTypographyAndThemeDialog() {
        ReaderTypographyDialog.show(this, storageService.getSettings(), currentTheme, theme -> {
            currentTheme = theme;
            onThemeChangedGlobal.onThemeChanged(theme);
        }, storageService::saveState, this::refreshReadingLayout);
    }

    private void refreshReadingLayout() {
        if (currentBook == null || chapterLayout == null) return;
        boolean sameMode = chapterLayout.mode() == storageService.getSettings().getReadingMode();
        goToChapterAndPage(currentChapterIndex, currentPageIndex, sameMode ? currentBook.getScrollOffset() : 0);
    }

    public void stopActiveFeatures() {
        if (saveScrollTimer.isRunning()) saveReadingProgress();
        bookLoader.cancel();
        dictionaryLookup.cancel();
        clearSearch();
        if (autoScrollActive) {
            toggleAutoScroll();
        }
        if (ttsService.isSpeaking()) {
            ttsService.stop();
        }
    }

    public void disposeResources() {
        if (disposed) return;
        stopActiveFeatures();
        disposed = true;
        bookLoader.close();
        dictionaryLookup.close();
        readingTimeTrackerTimer.stop();
        restoreScrollTimer.stop();
        pendingScrollOffset = null;
    }

    public void applyTheme(ThemePalette theme) {
        this.currentTheme = theme;
        setBackground(theme.getBackground());
        primaryBar.setBackground(theme.getSurface());
        secondaryBar.setBackground(theme.getSurface());
        tocList.setBackground(theme.getSurface());
        tocList.setForeground(theme.getTextPrimary());
        bottomStatusBar.setBackground(theme.getSurface());
        statusLeftLabel.setForeground(theme.getTextSecondary());
        statusRightLabel.setForeground(theme.getTextPrimary());
        searchBar.applyTheme(theme);
        if (currentContent != null && chapterLayout != null && !chapterLayout.equals(ReaderTextSearch.Layout.from(storageService.getSettings()))) {
            goToChapterAndPage(currentChapterIndex, currentPageIndex);
        } else {
            renderCurrentViewport();
        }
    }
}
