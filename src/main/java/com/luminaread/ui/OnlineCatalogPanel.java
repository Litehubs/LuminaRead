package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.DownloadTask;
import com.luminaread.model.OnlineBookItem;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.OnlineCatalogService;
import com.luminaread.util.WrapLayout;
import com.luminaread.service.OnlineCatalogService.SearchResult;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class OnlineCatalogPanel extends JPanel {
    private final LibraryStorageService storageService;
    private final OnlineCatalogService catalogService;
    private final Consumer<Book> onBookDownloadedAndOpen;

    private final JPanel topSearchPanel;
    private final JTextField searchQueryField;
    private final JComboBox<String> sourceCombo;
    private final JComboBox<String> langCombo;
    private final JTextField customUrlField;
    private final JPanel resultsListPanel;
    private final JScrollPane resultsScroll;
    private final JLabel statusLabel;
    private final JProgressBar searchProgressBar;
    private final JProgressBar downloadProgressBar;
    private final JLabel downloadStatusLabel = ModernUiFactory.plainTextLabel("");
    private final DownloadJobs downloadJobs;
    private final Runnable downloadListener = this::refreshDownloadProgress;
    private DownloadTask currentDownload;
    private boolean disposed;
    private final LatestSwingRequest<SearchResult> catalogRequests = new LatestSwingRequest<>();

    private ThemePalette currentTheme;

    public OnlineCatalogPanel(LibraryStorageService storageService,
                              OnlineCatalogService catalogService,
                              Consumer<Book> onBookDownloadedAndOpen) {
        this.storageService = storageService;
        this.catalogService = catalogService;
        this.downloadJobs = new DownloadJobs(catalogService, storageService);
        this.onBookDownloadedAndOpen = onBookDownloadedAndOpen;
        this.currentTheme = ThemePalette.byId(storageService.getSettings().getThemeId());

        setLayout(new BorderLayout(0, 0));

        topSearchPanel = new JPanel();
        topSearchPanel.setLayout(new BoxLayout(topSearchPanel, BoxLayout.Y_AXIS));
        topSearchPanel.setBorder(BorderFactory.createEmptyBorder(14, 22, 12, 22));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        row1.setOpaque(false);

        searchQueryField = new JTextField("Достоевский", 20);
        ModernUiFactory.styleTextField(searchQueryField, currentTheme);
        searchQueryField.addActionListener(e -> executeCatalogSearch());

        sourceCombo = new JComboBox<>(new String[]{
                "Все источники",
                "Флибуста",
                "Либрусек",
                "Project Gutenberg",
                "Open Library",
                "Standard Ebooks"
        });
        sourceCombo.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));

        langCombo = new JComboBox<>(new String[]{
                "Все языки", "Русский (ru)", "English (en)", "Deutsch (de)", "Français (fr)"
        });
        langCombo.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));

        JButton searchBtn = ModernUiFactory.createPrimaryButton("Найти книги", currentTheme);
        searchBtn.addActionListener(e -> executeCatalogSearch());

        row1.add(new JLabel("Книга или автор:"));
        row1.add(searchQueryField);
        row1.add(sourceCombo);
        row1.add(langCombo);
        row1.add(searchBtn);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        row2.setOpaque(false);

        customUrlField = new JTextField("http://flibusta.is/opds", 32);
        ModernUiFactory.styleTextField(customUrlField, currentTheme);

        JButton openOpdsBtn = ModernUiFactory.createFlatButton("Открыть OPDS-каталог", currentTheme);
        openOpdsBtn.addActionListener(e -> executeCustomOpdsBrowse());

        JButton directDownloadBtn = ModernUiFactory.createFlatButton("Скачать по прямой ссылке", currentTheme);
        directDownloadBtn.addActionListener(e -> executeDirectUrlDownload());

        row2.add(new JLabel("OPDS-каталог или прямая ссылка:"));
        row2.add(customUrlField);
        row2.add(openOpdsBtn);
        row2.add(directDownloadBtn);

        topSearchPanel.add(row1);
        topSearchPanel.add(row2);
        add(topSearchPanel, BorderLayout.NORTH);

        resultsListPanel = new JPanel();
        resultsListPanel.setLayout(new BoxLayout(resultsListPanel, BoxLayout.Y_AXIS));
        resultsListPanel.setBorder(BorderFactory.createEmptyBorder(14, 22, 22, 22));

        resultsScroll = SmoothScrollUtil.enhanceAndReturn(new JScrollPane(resultsListPanel));
        resultsScroll.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        resultsScroll.setBorder(BorderFactory.createEmptyBorder());
        resultsScroll.getVerticalScrollBar().setUnitIncrement(42);
        add(resultsScroll, BorderLayout.CENTER);

        JPanel footer = new JPanel();
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(10, 22, 10, 22));

        statusLabel = new JLabel("Введите название книги или имя автора.");
        statusLabel.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        searchProgressBar = progressBar();
        footer.add(statusRow(statusLabel, searchProgressBar));

        downloadProgressBar = progressBar();
        downloadStatusLabel.setFont(ModernUiFactory.uiFont(Font.PLAIN, 12));
        downloadStatusLabel.setVisible(false);
        footer.add(statusRow(downloadStatusLabel, downloadProgressBar));
        add(footer, BorderLayout.SOUTH);

        catalogService.addDownloadListener(downloadListener);
        applyTheme(currentTheme);
    }

    private static JPanel statusRow(JLabel label, JProgressBar progress) {
        JPanel row = new JPanel(new BorderLayout(14, 2));
        row.setOpaque(false);
        row.add(label, BorderLayout.CENTER);
        row.add(progress, BorderLayout.EAST);
        return row;
    }

    private static JProgressBar progressBar() {
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        bar.setFont(ModernUiFactory.uiFont(Font.BOLD, 12));
        bar.setPreferredSize(new Dimension(180, 22));
        bar.setVisible(false);
        return bar;
    }

    private void executeCatalogSearch() {
        String query = searchQueryField.getText().trim();
        String sourceCode = switch (sourceCombo.getSelectedIndex()) {
            case 1 -> "FLIBUSTA";
            case 2 -> "LIBRUSEC";
            case 3 -> "GUTENBERG";
            case 4 -> "OPENLIBRARY";
            case 5 -> "STANDARD_EBOOKS";
            default -> "ALL";
        };
        String langCode = switch (langCombo.getSelectedIndex()) {
            case 1 -> "ru";
            case 2 -> "en";
            case 3 -> "de";
            case 4 -> "fr";
            default -> "ALL";
        };

        statusLabel.setText("Идет параллельный поиск по онлайн-библиотекам...");
        searchProgressBar.setIndeterminate(true);
        searchProgressBar.setString("Поиск...");
        searchProgressBar.setVisible(true);
        resultsListPanel.removeAll();
        resultsListPanel.revalidate();
        resultsListPanel.repaint();

        startCatalogLoad(() -> catalogService.searchBooksDetailed(query, sourceCode, langCode));
    }

    private void executeCustomOpdsBrowse() {
        String opdsUrl = customUrlField.getText().trim();
        if (opdsUrl.isEmpty()) return;
        storageService.getSettings().addCustomOpdsCatalog(opdsUrl);
        UiPersistenceErrors.defer(this, storageService, "Не удалось сохранить каталог в настройках.", storageService::saveState);

        statusLabel.setText("Загрузка OPDS-каталога: " + opdsUrl + " ...");
        searchProgressBar.setIndeterminate(true);
        searchProgressBar.setString("Загрузка OPDS...");
        searchProgressBar.setVisible(true);

        String term = searchQueryField.getText().trim();
        startCatalogLoad(() -> new SearchResult(catalogService.browseCustomOpds(opdsUrl, term), List.of()));
    }

    private void startCatalogLoad(java.util.function.Supplier<SearchResult> request) {
        if (disposed) return;
        catalogRequests.start(request::get, result -> {
            hideCatalogProgress();
            renderSearchResults(result);
        }, error -> {
            java.util.logging.Logger.getLogger(OnlineCatalogPanel.class.getName())
                    .log(java.util.logging.Level.WARNING, "Ошибка загрузки каталога", error);
            hideCatalogProgress();
            resultsListPanel.removeAll();
            resultsListPanel.revalidate();
            resultsListPanel.repaint();
            statusLabel.setText("Не удалось загрузить каталог. Проверьте адрес и подключение.");
        });
    }

    private void hideCatalogProgress() {
        searchProgressBar.setIndeterminate(false);
        searchProgressBar.setVisible(false);
    }

    private void executeDirectUrlDownload() {
        String url = customUrlField.getText().trim();
        if (url.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Вставьте прямую ссылку на файл книги (.fb2, .epub, .pdf, .txt).");
            return;
        }
        downloadAndImportBook(url, "Скачанная книга", null);
    }

    private void renderSearchResults(SearchResult result) {
        List<OnlineBookItem> items = result.books();
        resultsListPanel.removeAll();
        if (items.isEmpty()) {
            JLabel empty = new JLabel(
                    "<html><div style='padding:24px;'>"
                            + "<h3>По вашему запросу ничего не найдено</h3>"
                            + "<p>Попробуйте изменить запрос (например, ввести фамилию автора на русском или английском)<br/>"
                            + "или выберите другой источник в выпадающем списке.</p></div></html>"
            );
            empty.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
            empty.setForeground(currentTheme.getTextSecondary());
            resultsListPanel.add(empty);
        } else {
            for (OnlineBookItem item : items) {
                resultsListPanel.add(createOnlineBookCard(item));
                resultsListPanel.add(Box.createVerticalStrut(12));
            }
        }
        String status = items.isEmpty() ? "Книги не найдены." : "Найдено книг для скачивания: " + items.size();
        if (!result.unavailableSources().isEmpty()) {
            status += " · Не удалось загрузить: " + String.join(", ", result.unavailableSources());
        }
        statusLabel.setText(status);
        resultsListPanel.revalidate();
        resultsListPanel.repaint();
    }

    private JPanel createOnlineBookCard(OnlineBookItem item) {
        JPanel card = new JPanel(new BorderLayout(12, 8));
        card.setBackground(currentTheme.getCardBackground());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(currentTheme.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)
        ));

        JPanel textCol = new JPanel();
        textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
        textCol.setOpaque(false);

        JLabel titleLbl = ModernUiFactory.plainTextLabel(item.getTitle());
        titleLbl.setFont(ModernUiFactory.uiFont(Font.BOLD, 15));
        titleLbl.setForeground(currentTheme.getTextPrimary());
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel metaLbl = ModernUiFactory.plainTextLabel(item.getAuthor() + "  •  Источник: " + item.getSourceName()
                + "  •  Язык: " + item.getLanguage());
        metaLbl.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        metaLbl.setForeground(currentTheme.getTextSecondary());
        metaLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        textCol.add(titleLbl);
        textCol.add(Box.createVerticalStrut(4));
        textCol.add(metaLbl);

        if (!item.getSummary().isBlank()) {
            String shortSum = item.getSummary().length() > 280
                    ? item.getSummary().substring(0, 280) + "..."
                    : item.getSummary();
            JLabel sumLbl = new JLabel("<html><p style='width:620px;'>"
                    + com.luminaread.util.HtmlSanitizer.escapeHtml(shortSum) + "</p></html>");
            sumLbl.setFont(ModernUiFactory.uiFont(Font.PLAIN, 12));
            sumLbl.setForeground(currentTheme.getTextSecondary());
            sumLbl.setAlignmentX(Component.LEFT_ALIGNMENT);
            textCol.add(Box.createVerticalStrut(6));
            textCol.add(sumLbl);
        }

        JPanel downloadButtonsPanel = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        downloadButtonsPanel.setOpaque(false);

        for (Map.Entry<String, String> dl : item.getDownloadLinks().entrySet()) {
            String fmt = dl.getKey();
            String url = dl.getValue();
            JButton dlBtn = ModernUiFactory.createPrimaryButton("Скачать " + fmt, currentTheme);
            dlBtn.addActionListener(e -> downloadAndImportBook(url, item.getTitle(), fmt));
            downloadButtonsPanel.add(dlBtn);
        }

        card.add(textCol, BorderLayout.CENTER);
        card.add(downloadButtonsPanel, BorderLayout.SOUTH);
        return card;
    }

    private void downloadAndImportBook(String url, String title, String formatHint) {
        if (disposed) return;
        DownloadTask task = catalogService.createLibraryDownload(title, formatHint, url);
        currentDownload = task;
        refreshDownloadProgress();
        downloadJobs.start(task, imported -> {
            if (task != currentDownload) return;
            refreshDownloadProgress();
            if (isShowing() && JOptionPane.showConfirmDialog(this,
                    "Открыть скачанную книгу «" + imported.getTitle() + "»?",
                    "Книга добавлена в библиотеку", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                if (!disposed) onBookDownloadedAndOpen.accept(imported);
            }
        }, error -> refreshDownloadProgress());
    }

    private void refreshDownloadProgress() {
        if (disposed || currentDownload == null) return;
        DownloadTask task = currentDownload;
        downloadStatusLabel.setText(task.getTitle() + " · " + task.getFormattedProgressDetails());
        downloadStatusLabel.setVisible(true);
        boolean unknownTotal = task.getStatus() == DownloadTask.Status.DOWNLOADING && task.getTotalBytes() < 0;
        downloadProgressBar.setIndeterminate(unknownTotal);
        downloadProgressBar.setValue(task.getProgressPercent());
        downloadProgressBar.setString(unknownTotal ? "Скачивание…" : task.getProgressPercent() + "%");
        downloadProgressBar.setVisible(task.getStatus() != DownloadTask.Status.FAILED);
    }

    void disposeResources() {
        if (disposed) return;
        disposed = true;
        catalogRequests.cancel();
        catalogService.removeDownloadListener(downloadListener);
        downloadJobs.close();
    }

    public void applyTheme(ThemePalette theme) {
        this.currentTheme = theme;
        setBackground(theme.getBackground());
        topSearchPanel.setBackground(theme.getSurface());
        resultsListPanel.setBackground(theme.getBackground());
        resultsScroll.getViewport().setBackground(theme.getBackground());
        statusLabel.setForeground(theme.getTextPrimary());
        downloadStatusLabel.setForeground(theme.getTextSecondary());
        ModernUiFactory.styleTextField(searchQueryField, theme);
        ModernUiFactory.styleTextField(customUrlField, theme);
    }
}
