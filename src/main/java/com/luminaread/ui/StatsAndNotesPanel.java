package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.Bookmark;
import com.luminaread.model.Highlight;
import com.luminaread.service.LibraryStorageService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.File;
import java.util.List;
import java.util.function.BiConsumer;

public class StatsAndNotesPanel extends JPanel {
    private final LibraryStorageService storageService;
    private final BiConsumer<String, Integer> onJumpToBookChapter;

    private final JPanel metricsGrid;
    private final JPanel bookmarksListPanel;
    private final JPanel highlightsListPanel;
    private final JScrollPane bmScroll;
    private final JScrollPane hlScroll;
    private ThemePalette currentTheme;

    public StatsAndNotesPanel(LibraryStorageService storageService,
                              BiConsumer<String, Integer> onJumpToBookChapter) {
        this.storageService = storageService;
        this.onJumpToBookChapter = onJumpToBookChapter;
        this.currentTheme = ThemePalette.byId(storageService.getSettings().getThemeId());

        setLayout(new BorderLayout(0, 12));

        JPanel topContainer = new JPanel(new BorderLayout(0, 10));
        topContainer.setBorder(BorderFactory.createEmptyBorder(16, 22, 8, 22));
        topContainer.setOpaque(false);

        JPanel headerBar = new JPanel(new BorderLayout());
        headerBar.setOpaque(false);
        JLabel titleLbl = new JLabel("Статистика чтения, закладки и сохраненные цитаты");
        titleLbl.setFont(ModernUiFactory.uiFont(Font.BOLD, 17));
        headerBar.add(titleLbl, BorderLayout.WEST);

        JButton exportBtn = ModernUiFactory.createPrimaryButton("Экспорт цитат и закладок в Markdown (.md)", currentTheme);
        exportBtn.addActionListener(e -> exportNotesToFile());
        headerBar.add(exportBtn, BorderLayout.EAST);

        metricsGrid = new JPanel(new GridLayout(1, 5, 12, 0));
        metricsGrid.setOpaque(false);

        topContainer.add(headerBar, BorderLayout.NORTH);
        topContainer.add(metricsGrid, BorderLayout.CENTER);
        add(topContainer, BorderLayout.NORTH);

        bookmarksListPanel = new JPanel();
        bookmarksListPanel.setLayout(new BoxLayout(bookmarksListPanel, BoxLayout.Y_AXIS));
        bookmarksListPanel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        bmScroll = SmoothScrollUtil.enhanceAndReturn(new JScrollPane(bookmarksListPanel));
        bmScroll.setBorder(BorderFactory.createTitledBorder("Закладки в книгах"));

        highlightsListPanel = new JPanel();
        highlightsListPanel.setLayout(new BoxLayout(highlightsListPanel, BoxLayout.Y_AXIS));
        highlightsListPanel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        hlScroll = SmoothScrollUtil.enhanceAndReturn(new JScrollPane(highlightsListPanel));
        hlScroll.setBorder(BorderFactory.createTitledBorder("Выделенные цитаты и заметки"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, bmScroll, hlScroll);
        splitPane.setResizeWeight(0.45);
        splitPane.setBorder(BorderFactory.createEmptyBorder(0, 22, 20, 22));
        add(splitPane, BorderLayout.CENTER);

        applyTheme(currentTheme);
        refreshData();
    }

    public void refreshData() {
        metricsGrid.removeAll();
        List<Book> books = storageService.getAllBooks();
        int totalBooks = books.size();
        int finishedBooks = (int) books.stream().filter(Book::isFinished).count();
        long totalSeconds = books.stream().mapToLong(Book::getTotalReadingSeconds).sum();
        long totalWordsInLib = books.stream().mapToLong(Book::getTotalWords).sum();
        long approxWordsRead = books.stream()
                .mapToLong(b -> Math.round(b.getTotalWords() * (b.getProgressPercent() / 100.0)))
                .sum();

        double hours = totalSeconds / 3600.0;
        long wpm = (totalSeconds > 30 && approxWordsRead > 0)
                ? Math.min(650, Math.max(110, (approxWordsRead * 60) / totalSeconds))
                : 215;

        metricsGrid.add(createMetricCard("Книг в библиотеке", String.valueOf(totalBooks), "Завершено: " + finishedBooks));
        metricsGrid.add(createMetricCard("Время за чтением", String.format("%.1f ч", hours), (totalSeconds / 60) + " мин. всего"));
        metricsGrid.add(createMetricCard("Прочитано слов", String.format("%,d", approxWordsRead), String.format("Из %,d в библиотеке", totalWordsInLib)));
        metricsGrid.add(createMetricCard("Средняя скорость", wpm + " сл/мин", "Расчетный темп чтения"));
        metricsGrid.add(createMetricCard("Закладки и цитаты",
                storageService.getBookmarks(null).size() + " / " + storageService.getHighlights(null).size(),
                "Закладок / Цитат"));

        bookmarksListPanel.removeAll();
        List<Bookmark> bookmarks = storageService.getBookmarks(null);
        if (bookmarks.isEmpty()) {
            JLabel empty = new JLabel("Пока нет сохраненных закладок.");
            empty.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
            empty.setForeground(currentTheme.getTextSecondary());
            bookmarksListPanel.add(empty);
        } else {
            for (Bookmark bm : bookmarks) {
                bookmarksListPanel.add(createBookmarkRow(bm));
                bookmarksListPanel.add(Box.createVerticalStrut(8));
            }
        }

        highlightsListPanel.removeAll();
        List<Highlight> highlights = storageService.getHighlights(null);
        if (highlights.isEmpty()) {
            JLabel empty = new JLabel("Пока нет сохраненных цитат и заметок.");
            empty.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
            empty.setForeground(currentTheme.getTextSecondary());
            highlightsListPanel.add(empty);
        } else {
            for (Highlight hl : highlights) {
                highlightsListPanel.add(createHighlightRow(hl));
                highlightsListPanel.add(Box.createVerticalStrut(8));
            }
        }

        revalidate();
        repaint();
    }

    private JPanel createMetricCard(String title, String bigValue, String subtitle) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(currentTheme.getCardBackground());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(currentTheme.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)
        ));

        JLabel tLbl = new JLabel(title);
        tLbl.setFont(ModernUiFactory.uiFont(Font.PLAIN, 12));
        tLbl.setForeground(currentTheme.getTextSecondary());
        tLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel vLbl = new JLabel(bigValue);
        vLbl.setFont(ModernUiFactory.uiFont(Font.BOLD, 20));
        vLbl.setForeground(currentTheme.getAccent());
        vLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel sLbl = new JLabel(subtitle);
        sLbl.setFont(ModernUiFactory.uiFont(Font.PLAIN, 11));
        sLbl.setForeground(currentTheme.getTextSecondary());
        sLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        card.add(tLbl);
        card.add(Box.createVerticalStrut(4));
        card.add(vLbl);
        card.add(Box.createVerticalStrut(4));
        card.add(sLbl);
        return card;
    }

    private JPanel createBookmarkRow(Bookmark bm) {
        JPanel row = new JPanel(new BorderLayout(8, 4));
        row.setBackground(currentTheme.getCardBackground());
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(currentTheme.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));

        JLabel info = new JLabel("<html><b>" + bm.getBookTitle() + "</b> — " + bm.getChapterTitle()
                + " (стр. " + (bm.getPageIndex() + 1) + ")<br/>"
                + "<span style='font-size:11px;'>" + bm.getLabel() + " • " + bm.getFormattedDate() + "</span></html>");
        info.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        info.setForeground(currentTheme.getTextPrimary());

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btns.setOpaque(false);

        JButton goBtn = ModernUiFactory.createFlatButton("Перейти", currentTheme);
        goBtn.addActionListener(e -> onJumpToBookChapter.accept(bm.getBookId(), bm.getChapterIndex()));

        JButton delBtn = ModernUiFactory.createFlatButton("Удалить", currentTheme);
        delBtn.addActionListener(e -> {
            storageService.removeBookmark(bm.getId());
            refreshData();
        });

        btns.add(goBtn);
        btns.add(delBtn);

        row.add(info, BorderLayout.CENTER);
        row.add(btns, BorderLayout.EAST);
        return row;
    }

    private JPanel createHighlightRow(Highlight hl) {
        JPanel row = new JPanel(new BorderLayout(8, 4));
        row.setBackground(currentTheme.getCardBackground());
        Color markerColor = Color.decode(hl.getColorHex());
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 6, 1, 1, markerColor),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));

        String notePart = hl.getNote().isBlank() ? "" : ("<br/><b>Заметка:</b> " + hl.getNote());
        JLabel info = new JLabel("<html><b>" + hl.getBookTitle() + "</b> (" + hl.getChapterTitle() + ")<br/>"
                + "<i>«" + hl.getSelectedText() + "»</i>" + notePart
                + "<br/><span style='font-size:10px;'>" + hl.getFormattedDate() + "</span></html>");
        info.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        info.setForeground(currentTheme.getTextPrimary());

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btns.setOpaque(false);

        JButton goBtn = ModernUiFactory.createFlatButton("Открыть", currentTheme);
        goBtn.addActionListener(e -> onJumpToBookChapter.accept(hl.getBookId(), hl.getChapterIndex()));

        JButton delBtn = ModernUiFactory.createFlatButton("Удалить", currentTheme);
        delBtn.addActionListener(e -> {
            storageService.removeHighlight(hl.getId());
            refreshData();
        });

        btns.add(goBtn);
        btns.add(delBtn);

        row.add(info, BorderLayout.CENTER);
        row.add(btns, BorderLayout.EAST);
        return row;
    }

    private void exportNotesToFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Экспорт цитат и закладок в файл Markdown");
        chooser.setSelectedFile(new File("LuminaRead_Quotes_and_Notes.md"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                File out = chooser.getSelectedFile();
                storageService.exportNotesAndHighlightsToMarkdown(out);
                JOptionPane.showMessageDialog(this,
                        "Цитаты и закладки успешно экспортированы в:\n" + out.getAbsolutePath(),
                        "Экспорт завершен", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "Ошибка экспорта: " + ex.getMessage(),
                        "Ошибка", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    public void applyTheme(ThemePalette theme) {
        this.currentTheme = theme;
        setBackground(theme.getBackground());
        bookmarksListPanel.setBackground(theme.getBackground());
        highlightsListPanel.setBackground(theme.getBackground());
        bmScroll.getViewport().setBackground(theme.getBackground());
        hlScroll.getViewport().setBackground(theme.getBackground());
        refreshData();
    }
}
