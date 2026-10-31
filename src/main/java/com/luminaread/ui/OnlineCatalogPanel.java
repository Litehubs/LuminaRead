package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.DownloadTask;
import com.luminaread.model.OnlineBookItem;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.OnlineCatalogService;
import com.luminaread.util.WrapLayout;

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
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
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
    private final JProgressBar downloadProgressBar;

    private ThemePalette currentTheme;

    public OnlineCatalogPanel(LibraryStorageService storageService,
                              OnlineCatalogService catalogService,
                              Consumer<Book> onBookDownloadedAndOpen) {
        this.storageService = storageService;
        this.catalogService = catalogService;
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
                "Все источники (Flibusta + Либрусек + Gutenberg + Open Library)",
                "Flibusta OPDS (Русская и зарубежная библиотека)",
                "Либрусек lib.rus.ec (Зеркало русской библиотеки)",
                "Project Gutenberg (70 000+ бесплатных книг)",
                "Open Library / Internet Archive (Миллионы изданий)",
                "Standard Ebooks (Отборная классика)"
        });
        sourceCombo.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));

        langCombo = new JComboBox<>(new String[]{
                "Все языки", "Русский (ru)", "English (en)", "Deutsch (de)", "Français (fr)"
        });
        langCombo.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));

        JButton searchBtn = ModernUiFactory.createPrimaryButton("Найти книги", currentTheme);
        searchBtn.addActionListener(e -> executeCatalogSearch());

        row1.add(new JLabel("Запрос (книга / автор):"));
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

        JPanel bottomBar = new JPanel(new BorderLayout(14, 0));
        bottomBar.setBorder(BorderFactory.createEmptyBorder(10, 22, 10, 22));

        statusLabel = new JLabel("Введите название книги или имя автора и нажмите «Найти книги».");
        statusLabel.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));

        downloadProgressBar = new JProgressBar(0, 100);
        downloadProgressBar.setStringPainted(true);
        downloadProgressBar.setFont(ModernUiFactory.uiFont(Font.BOLD, 12));
        downloadProgressBar.setPreferredSize(new Dimension(260, 22));
        downloadProgressBar.setVisible(false);

        bottomBar.add(statusLabel, BorderLayout.CENTER);
        bottomBar.add(downloadProgressBar, BorderLayout.EAST);
        add(bottomBar, BorderLayout.SOUTH);

        applyTheme(currentTheme);
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
        downloadProgressBar.setIndeterminate(true);
        downloadProgressBar.setString("Поиск...");
        downloadProgressBar.setVisible(true);
        resultsListPanel.removeAll();
        resultsListPanel.revalidate();
        resultsListPanel.repaint();

        Thread searchThread = new Thread(() -> {
            List<OnlineBookItem> items = catalogService.searchBooks(query, sourceCode, langCode);
            SwingUtilities.invokeLater(() -> {
                downloadProgressBar.setIndeterminate(false);
                downloadProgressBar.setVisible(false);
                renderSearchResults(items);
            });
        }, "LuminaRead-OnlineSearch");
        searchThread.setDaemon(true);
        searchThread.start();
    }

    private void executeCustomOpdsBrowse() {
        String opdsUrl = customUrlField.getText().trim();
        if (opdsUrl.isEmpty()) return;
        storageService.getSettings().addCustomOpdsCatalog(opdsUrl);
        storageService.saveState();

        statusLabel.setText("Загрузка OPDS-каталога: " + opdsUrl + " ...");
        downloadProgressBar.setIndeterminate(true);
        downloadProgressBar.setString("Загрузка OPDS...");
        downloadProgressBar.setVisible(true);

        String term = searchQueryField.getText().trim();
        Thread t = new Thread(() -> {
            List<OnlineBookItem> items = catalogService.browseCustomOpds(opdsUrl, term);
            SwingUtilities.invokeLater(() -> {
                downloadProgressBar.setIndeterminate(false);
                downloadProgressBar.setVisible(false);
                renderSearchResults(items);
            });
        }, "LuminaRead-OpdsBrowse");
        t.setDaemon(true);
        t.start();
    }

    private void executeDirectUrlDownload() {
        String url = customUrlField.getText().trim();
        if (url.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Вставьте прямую ссылку на файл книги (.fb2, .epub, .pdf, .txt).");
            return;
        }
        downloadAndImportBook(url, "Скачанная книга", null);
    }

    private void renderSearchResults(List<OnlineBookItem> items) {
        resultsListPanel.removeAll();
        if (items == null || items.isEmpty()) {
            statusLabel.setText("Книги не найдены или выбранный каталог временно недоступен.");
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
            statusLabel.setText("Найдено книг для скачивания: " + items.size());
            for (OnlineBookItem item : items) {
                resultsListPanel.add(createOnlineBookCard(item));
                resultsListPanel.add(Box.createVerticalStrut(12));
            }
        }
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

        JLabel titleLbl = new JLabel(item.getTitle());
        titleLbl.setFont(ModernUiFactory.uiFont(Font.BOLD, 15));
        titleLbl.setForeground(currentTheme.getTextPrimary());
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel metaLbl = new JLabel(item.getAuthor() + "  •  Источник: " + item.getSourceName()
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
        DownloadTask task = catalogService.createTrackedTask(title, formatHint, url);

        statusLabel.setText("Скачивание «" + title + "»: 0%");
        downloadProgressBar.setIndeterminate(false);
        downloadProgressBar.setValue(0);
        downloadProgressBar.setString("0%");
        downloadProgressBar.setVisible(true);

        Thread dlThread = new Thread(() -> {
            try {
                File savedFile = catalogService.downloadBookFileWithTask(
                        task, storageService.getBooksDir(),
                        progress -> SwingUtilities.invokeLater(() -> {
                            int pct = task.getProgressPercent();
                            downloadProgressBar.setIndeterminate(false);
                            downloadProgressBar.setValue(pct);
                            downloadProgressBar.setString(pct + "%");
                            statusLabel.setText("Скачивание «" + title + "»: " + task.getFormattedProgressDetails());
                        })
                );
                Book imported = storageService.importBookFile(savedFile, false);
                task.setImportedBook(imported);
                catalogService.notifyDownloadListeners();

                SwingUtilities.invokeLater(() -> {
                    downloadProgressBar.setValue(100);
                    downloadProgressBar.setString("100%");
                    statusLabel.setText("Книга «" + imported.getTitle() + "» скачана (100%) и сохранена в библиотеку!");
                    int openNow = JOptionPane.showConfirmDialog(
                            OnlineCatalogPanel.this,
                            "Книга «" + imported.getTitle() + "» (" + imported.getFormat() + ") скачана на 100%!\nОткрыть её для чтения прямо сейчас?",
                            "Загрузка завершена (100%)",
                            JOptionPane.YES_NO_OPTION
                    );
                    if (openNow == JOptionPane.YES_OPTION) {
                        onBookDownloadedAndOpen.accept(imported);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    downloadProgressBar.setVisible(false);
                    statusLabel.setText("Ошибка при скачивании: " + ex.getMessage());
                    JOptionPane.showMessageDialog(
                            OnlineCatalogPanel.this,
                            "Не удалось скачать файл по ссылке:\n" + url + "\nПричина: " + ex.getMessage(),
                            "Ошибка загрузки",
                            JOptionPane.ERROR_MESSAGE
                    );
                });
            }
        }, "LuminaRead-Downloader");
        dlThread.setDaemon(true);
        dlThread.start();
    }

    public void applyTheme(ThemePalette theme) {
        this.currentTheme = theme;
        setBackground(theme.getBackground());
        topSearchPanel.setBackground(theme.getSurface());
        resultsListPanel.setBackground(theme.getBackground());
        resultsScroll.getViewport().setBackground(theme.getBackground());
        statusLabel.setForeground(theme.getTextPrimary());
        ModernUiFactory.styleTextField(searchQueryField, theme);
        ModernUiFactory.styleTextField(customUrlField, theme);
    }
}
