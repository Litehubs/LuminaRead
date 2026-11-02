package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.BookContent;
import com.luminaread.model.Bookmark;
import com.luminaread.model.Chapter;
import com.luminaread.model.Highlight;
import com.luminaread.model.ReaderSettings;
import com.luminaread.parser.PaginationEngine;
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
import javax.swing.JDialog;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.text.DefaultCaret;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class ReaderPanel extends JPanel {
    private static final DateTimeFormatter CLOCK_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final LibraryStorageService storageService;
    private final ITtsService ttsService;
    private final IDictionaryService dictionaryService;
    private final Runnable onBackToLibrary;
    private final ConsumerThemeCallback onThemeChangedGlobal;

    public interface ConsumerThemeCallback {
        void onThemeChanged(ThemePalette newPalette);
    }

    private Book currentBook;
    private BookContent currentContent;
    private long bookOpenSequence = 0;
    private List<String> currentChapterPages = Collections.emptyList();
    private int currentChapterIndex = 0;
    private int currentPageIndex = 0;
    private String activeSearchHighlightTerm = "";

    private boolean zenDistractionFree = false;
    private boolean autoScrollActive = false;
    private double autoScrollSubPixel = 0.0;
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
    private final JTextField inBookSearchField;
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
        this.dictionaryService = dictionaryService;
        this.onBackToLibrary = onBackToLibrary;
        this.onThemeChangedGlobal = onThemeChangedGlobal;
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

        inBookSearchField = new JTextField(16);
        ModernUiFactory.styleTextField(inBookSearchField, currentTheme);
        inBookSearchField.addActionListener(e -> searchInBookNext());

        JButton searchNextBtn = ModernUiFactory.createFlatButton("Найти в книге", currentTheme);
        searchNextBtn.addActionListener(e -> searchInBookNext());

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
            storageService.saveState();
            if (readerCanvasWrapper != null) {
                readerCanvasWrapper.repaint();
            }
        });

        secondaryBar.add(new JLabel("Шрифт:"));
        secondaryBar.add(fontMinusBtn);
        secondaryBar.add(fontPlusBtn);
        secondaryBar.add(new JLabel("  Поиск:"));
        secondaryBar.add(inBookSearchField);
        secondaryBar.add(searchNextBtn);
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
        bookEditorPane.setContentType("text/html");
        bookEditorPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        if (bookEditorPane.getCaret() instanceof DefaultCaret) {
            ((DefaultCaret) bookEditorPane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        }

        setupReaderMouseZonesAndGestures();

        editorScrollPane = SmoothScrollUtil.enhanceAndReturn(new JScrollPane(bookEditorPane));
        editorScrollPane.setBorder(BorderFactory.createEmptyBorder());

        editorScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        editorScrollPane.getVerticalScrollBar().setUnitIncrement(42);
        editorScrollPane.getVerticalScrollBar().addAdjustmentListener(e -> updateStatusBar());

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
                if (!inBookSearchField.hasFocus()) navigateForward();
            }
        });

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "prevPage");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), "prevPage");
        am.put("prevPage", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!inBookSearchField.hasFocus()) navigateBackward();
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
        if (book == null) return;
        stopActiveFeatures();
        long requestId = ++bookOpenSequence;
        currentBook = book;
        currentContent = null;
        currentChapterPages = Collections.emptyList();
        activeSearchHighlightTerm = "";
        tocListModel.clear();
        bookEditorPane.setText("<html><body><p style='text-align:center;'>Загружаю книгу…</p></body></html>");
        statusLeftLabel.setText("Загружаю: " + book.getTitle());
        statusRightLabel.setText("Подождите");

        new SwingWorker<BookContent, Void>() {
            @Override
            protected BookContent doInBackground() throws Exception {
                return storageService.loadBookContent(book);
            }

            @Override
            protected void done() {
                if (requestId != bookOpenSequence) return;
                try {
                    currentContent = get();
                    book.setLastOpenedTimestamp(System.currentTimeMillis());
                    for (Chapter chapter : currentContent.getChapters()) tocListModel.addElement(chapter);
                    int startChapter = Math.min(Math.max(0, book.getCurrentChapterIndex()),
                            Math.max(0, currentContent.getChapters().size() - 1));
                    int startPage = Math.max(0, book.getCurrentPageInChapter());
                    goToChapterAndPage(startChapter, startPage);
                } catch (Exception ex) {
                    currentContent = null;
                    String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    statusLeftLabel.setText("Не удалось открыть книгу");
                    statusRightLabel.setText("");
                    JOptionPane.showMessageDialog(ReaderPanel.this,
                            "Не удалось загрузить содержимое книги:\n" + message,
                            "Ошибка чтения", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    public void goToChapterAndPage(int chapterIdx, int pageIdx) {
        if (currentContent == null || currentContent.getChapters().isEmpty()) return;
        int totalChapters = currentContent.getChapters().size();
        this.currentChapterIndex = Math.max(0, Math.min(totalChapters - 1, chapterIdx));

        Chapter chapter = currentContent.getChapters().get(currentChapterIndex);
        ReaderSettings settings = storageService.getSettings();

        if (settings.getReadingMode() == ReaderSettings.ReadingMode.PAGED) {
            this.currentChapterPages = PaginationEngine.paginateChapter(
                    chapter, settings.getCharsPerPage(), settings.isTwoColumnSpread());
            this.currentPageIndex = Math.max(0, Math.min(currentChapterPages.size() - 1, pageIdx));
        } else {
            this.currentChapterPages = List.of(chapter.getHtmlContent());
            this.currentPageIndex = 0;
        }

        if (tocList.getSelectedIndex() != currentChapterIndex) {
            tocList.setSelectedIndex(currentChapterIndex);
        }

        renderCurrentViewport();
        saveReadingProgress();
    }

    private void renderCurrentViewport() {
        if (currentContent == null || currentContent.getChapters().isEmpty()) return;
        ReaderSettings settings = storageService.getSettings();

        HTMLEditorKit kit = new HTMLEditorKit();
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

        bookEditorPane.setEditorKit(kit);
        bookEditorPane.setBackground(currentTheme.getCardBackground());

        String htmlFragment = currentChapterPages.isEmpty() ? "<p></p>" : currentChapterPages.get(currentPageIndex);
        htmlFragment = htmlFragment
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&shy;", "")
                .replace("\u00A0", " ")
                .replace("\u00AD", "")
                .replaceAll("[ \\t\\x0B\\f\\r\\u2000-\\u200A]{2,}", " ");
        htmlFragment = injectSavedHighlightsAndSearch(htmlFragment);

        int viewportWidth = editorScrollPane.getViewport().getWidth();
        if (viewportWidth > 0) {
            int imgWidth = Math.max(260, viewportWidth - 70);
            htmlFragment = htmlFragment.replaceAll("(?i)(<img[^>]*?)\\s+width\\s*=\\s*(['\"])[^'\"]*\\2", "$1");
            htmlFragment = htmlFragment.replace("<img ", "<img width='" + imgWidth + "' ");
        }

        bookEditorPane.setText("<html><body>" + htmlFragment + "</body></html>");
        bookEditorPane.setCaretPosition(0);
        SwingUtilities.invokeLater(() -> editorScrollPane.getViewport().setViewPosition(new Point(0, 0)));
        updateStatusBar();
    }

    private String injectSavedHighlightsAndSearch(String html) {
        String decorated = html;
        if (currentBook != null) {
            List<Highlight> highlights = storageService.getHighlights(currentBook.getId());
            for (Highlight hl : highlights) {
                if (hl.getChapterIndex() == currentChapterIndex && hl.getSelectedText().length() >= 4) {
                    String snippet = HtmlSanitizer.escapeHtml(hl.getSelectedText());
                    if (decorated.contains(snippet)) {
                        String span = "<span style='background-color:" + hl.getColorHex() + "; color:#111111;'>"
                                + snippet + "</span>";
                        decorated = decorated.replace(snippet, span);
                    }
                }
            }
        }
        if (activeSearchHighlightTerm != null && !activeSearchHighlightTerm.isBlank()) {
            String escapedTerm = Pattern.quote(HtmlSanitizer.escapeHtml(activeSearchHighlightTerm));
            decorated = decorated.replaceAll("(?i)(" + escapedTerm + ")",
                    "<span style='background-color:#FACC15; color:#000000;'><b>$1</b></span>");
        }
        return decorated;
    }

    public void navigateForward() {
        if (currentContent == null || currentContent.getChapters().isEmpty()) return;
        ReaderSettings settings = storageService.getSettings();

        if (settings.getReadingMode() == ReaderSettings.ReadingMode.PAGED) {
            if (currentPageIndex + 1 < currentChapterPages.size()) {
                currentPageIndex++;
                renderCurrentViewport();
                saveReadingProgress();
            } else if (currentChapterIndex + 1 < currentContent.getChapters().size()) {
                goToChapterAndPage(currentChapterIndex + 1, 0);
            }
        } else {
            JScrollBar vBar = editorScrollPane.getVerticalScrollBar();
            int nextVal = vBar.getValue() + (int) (editorScrollPane.getViewport().getHeight() * 0.85);
            if (nextVal + vBar.getVisibleAmount() < vBar.getMaximum()) {
                vBar.setValue(nextVal);
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
                renderCurrentViewport();
                saveReadingProgress();
            } else if (currentChapterIndex > 0) {
                int prevChap = currentChapterIndex - 1;
                List<String> prevPages = PaginationEngine.paginateChapter(
                        currentContent.getChapters().get(prevChap),
                        settings.getCharsPerPage(),
                        settings.isTwoColumnSpread()
                );
                goToChapterAndPage(prevChap, Math.max(0, prevPages.size() - 1));
            }
        } else {
            JScrollBar vBar = editorScrollPane.getVerticalScrollBar();
            int prevVal = vBar.getValue() - (int) (editorScrollPane.getViewport().getHeight() * 0.85);
            if (vBar.getValue() > 10) {
                vBar.setValue(Math.max(0, prevVal));
                saveReadingProgress();
            } else if (currentChapterIndex > 0) {
                goToChapterAndPage(currentChapterIndex - 1, 0);
            }
        }
    }

    private void saveReadingProgress() {
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
        storageService.saveState();
        updateStatusBar();
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
        storageService.saveState();
        goToChapterAndPage(currentChapterIndex, currentPageIndex);
    }

    private void toggleAutoScroll() {
        autoScrollActive = !autoScrollActive;
        autoScrollSubPixel = 0.0;
        if (autoScrollActive) {
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
        int extent = editorScrollPane.getViewport().getHeight();
        int max = vBar.getMaximum() - extent;
        if (max <= 0) return;

        if (vBar.getValue() + extent < vBar.getMaximum() - 2) {
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
                renderCurrentViewport();
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
        storageService.addBookmark(bm);
        JOptionPane.showMessageDialog(this, "Закладка сохранена!", "Закладка", JOptionPane.INFORMATION_MESSAGE);
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
            storageService.addHighlight(hl);
            renderCurrentViewport();
        }
    }

    private void translateSelectedText() {
        String selected = bookEditorPane.getSelectedText();
        if (selected == null || selected.isBlank()) {
            selected = JOptionPane.showInputDialog(this, "Введите слово или выделите фразу в книге для перевода:");
        }
        if (selected == null || selected.isBlank()) return;

        final String query = selected.trim();
        Thread t = new Thread(() -> {
            String translation = dictionaryService.lookupAndTranslate(query);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    ReaderPanel.this,
                    translation,
                    "Словарь и Переводчик LuminaRead",
                    JOptionPane.INFORMATION_MESSAGE
            ));
        }, "LuminaRead-Dict");
        t.setDaemon(true);
        t.start();
    }

    private void searchInBookNext() {
        if (currentContent == null || currentContent.getChapters().isEmpty()) return;
        String query = inBookSearchField.getText().trim();
        if (query.isEmpty()) {
            activeSearchHighlightTerm = "";
            renderCurrentViewport();
            return;
        }
        activeSearchHighlightTerm = query;
        String lowerQ = query.toLowerCase(Locale.ROOT);

        int totalCh = currentContent.getChapters().size();
        for (int offset = 0; offset < totalCh; offset++) {
            int chIdx = (currentChapterIndex + offset) % totalCh;
            Chapter ch = currentContent.getChapters().get(chIdx);
            if (ch.getPlainText().toLowerCase(Locale.ROOT).contains(lowerQ)) {
                List<String> pages = PaginationEngine.paginateChapter(
                        ch,
                        storageService.getSettings().getCharsPerPage(),
                        storageService.getSettings().isTwoColumnSpread()
                );
                int startPage = (offset == 0) ? (currentPageIndex + 1) : 0;
                for (int p = startPage; p < pages.size(); p++) {
                    if (HtmlSanitizer.htmlToPlainText(pages.get(p)).toLowerCase(Locale.ROOT).contains(lowerQ)) {
                        goToChapterAndPage(chIdx, p);
                        return;
                    }
                }
            }
        }
        JOptionPane.showMessageDialog(this, "Совпадений по запросу «" + query + "» больше не найдено.");
    }

    public void openTypographyAndThemeDialog() {
        ReaderSettings s = storageService.getSettings();
        JDialog dialog = new JDialog(
                (Frame) SwingUtilities.getWindowAncestor(this),
                "Настройки чтения, типографики и тем (Moon+ Pro)",
                true
        );
        dialog.setLayout(new BorderLayout(12, 12));

        JPanel form = new JPanel(new GridLayout(0, 2, 12, 10));
        form.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));

        JComboBox<ThemePalette> themeCombo = new JComboBox<>(
                ThemePalette.PRESETS.values().toArray(new ThemePalette[0]));
        themeCombo.setSelectedItem(ThemePalette.byId(s.getThemeId()));

        JComboBox<String> fontCombo = new JComboBox<>(new String[]{
                "Georgia", "Palatino", "Times New Roman", "Arial", "Helvetica Neue", "Verdana", "Monospaced"
        });
        fontCombo.setSelectedItem(s.getFontFamily());

        JSlider fontSizeSlider = new JSlider(12, 36, s.getFontSize());
        fontSizeSlider.setMajorTickSpacing(4);
        fontSizeSlider.setPaintTicks(true);
        fontSizeSlider.setPaintLabels(true);

        JComboBox<String> lineHeightCombo = new JComboBox<>(new String[]{
                "1.2 (Плотное)", "1.4 (Стандартное)", "1.6 (Комфортное)", "1.8 (Воздушное)", "2.0 (Двойной интервал)"
        });
        int lhIndex = (int) Math.round((s.getLineHeight() - 1.2) / 0.2);
        lineHeightCombo.setSelectedIndex(Math.max(0, Math.min(4, lhIndex)));

        JComboBox<String> alignCombo = new JComboBox<>(new String[]{
                "По левому краю (Ровные пробелы)", "По ширине страницы (Justify)"
        });
        alignCombo.setSelectedIndex(s.getTextAlignment() == ReaderSettings.TextAlignment.LEFT ? 0 : 1);

        JSlider marginSlider = new JSlider(20, 180, s.getHorizontalMarginPx());
        marginSlider.setMajorTickSpacing(40);
        marginSlider.setPaintTicks(true);
        marginSlider.setPaintLabels(true);

        JComboBox<String> modeCombo = new JComboBox<>(new String[]{
                "Постраничный режим (Листание)", "Плавный вертикальный свиток (Скролл)"
        });
        modeCombo.setSelectedIndex(s.getReadingMode() == ReaderSettings.ReadingMode.PAGED ? 0 : 1);

        JCheckBox twoColCheck = new JCheckBox("Двухколоночный книжный разворот (2 страницы рядом)", s.isTwoColumnSpread());
        JSlider autoScrollSpeedSlider = new JSlider(10, 150, s.getAutoScrollSpeed());
        JSlider ttsRateSlider = new JSlider(110, 300, s.getTtsRateWpm());

        form.add(new JLabel("Цветовая тема оформления:"));
        form.add(themeCombo);
        form.add(new JLabel("Гарнитура шрифта:"));
        form.add(fontCombo);
        form.add(new JLabel("Размер шрифта (пт):"));
        form.add(fontSizeSlider);
        form.add(new JLabel("Межстрочный интервал:"));
        form.add(lineHeightCombo);
        form.add(new JLabel("Выравнивание текста:"));
        form.add(alignCombo);
        form.add(new JLabel("Боковые поля страницы (px):"));
        form.add(marginSlider);
        form.add(new JLabel("Режим перелистывания:"));
        form.add(modeCombo);
        form.add(new JLabel("Книжный разворот:"));
        form.add(twoColCheck);
        form.add(new JLabel("Скорость автопрокрутки:"));
        form.add(autoScrollSpeedSlider);
        form.add(new JLabel("Скорость чтения вслух (TTS слов/мин):"));
        form.add(ttsRateSlider);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        JButton applyBtn = ModernUiFactory.createPrimaryButton("Применить настройки", currentTheme);
        applyBtn.addActionListener(e -> {
            ThemePalette chosenTheme = (ThemePalette) themeCombo.getSelectedItem();
            if (chosenTheme != null) {
                s.setThemeId(chosenTheme.getId());
                this.currentTheme = chosenTheme;
                onThemeChangedGlobal.onThemeChanged(chosenTheme);
            }
            s.setFontFamily((String) fontCombo.getSelectedItem());
            s.setFontSize(fontSizeSlider.getValue());
            double[] lhValues = {1.2, 1.4, 1.6, 1.8, 2.0};
            s.setLineHeight(lhValues[Math.max(0, lineHeightCombo.getSelectedIndex())]);
            s.setTextAlignment(alignCombo.getSelectedIndex() == 0
                    ? ReaderSettings.TextAlignment.LEFT
                    : ReaderSettings.TextAlignment.JUSTIFY);
            s.setHorizontalMarginPx(marginSlider.getValue());
            s.setReadingMode(modeCombo.getSelectedIndex() == 0
                    ? ReaderSettings.ReadingMode.PAGED
                    : ReaderSettings.ReadingMode.SCROLL);
            s.setTwoColumnSpread(twoColCheck.isSelected());
            s.setAutoScrollSpeed(autoScrollSpeedSlider.getValue());
            s.setTtsRateWpm(ttsRateSlider.getValue());

            int scaledChars = Math.max(900, Math.min(4800, (int) (2400.0 * 19.0 / s.getFontSize())));
            if (s.isTwoColumnSpread()) scaledChars = (int) (scaledChars * 0.85);
            s.setCharsPerPage(scaledChars);

            storageService.saveState();
            goToChapterAndPage(currentChapterIndex, currentPageIndex);
            dialog.dispose();
        });

        btnRow.add(applyBtn);
        dialog.add(form, BorderLayout.CENTER);
        dialog.add(btnRow, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    public void stopActiveFeatures() {
        if (autoScrollActive) {
            toggleAutoScroll();
        }
        if (ttsService.isSpeaking()) {
            ttsService.stop();
        }
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
        ModernUiFactory.styleTextField(inBookSearchField, theme);
        renderCurrentViewport();
    }
}
