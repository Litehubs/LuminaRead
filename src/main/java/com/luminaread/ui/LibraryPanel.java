package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.service.LibraryStorageService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class LibraryPanel extends JPanel {
    private final LibraryStorageService storageService;
    private final Consumer<Book> onOpenBook;
    private final Runnable onNavigateToOnlineCatalog;

    private String activeShelfFilter = "ALL";
    private String activeSortMode = "RECENT";

    private final JPanel topToolbar;
    private final JPanel filterBar;
    private final JTextField searchField;
    private final JComboBox<String> sortCombo;
    private static final int PAGE_SIZE = 12;
    private final JPanel pageBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
    private final JButton previousPage = new JButton("Назад");
    private final JButton nextPage = new JButton("Дальше");
    private final JLabel pageLabel = new JLabel();
    private List<Book> results = List.of();
    private int page;
    private final JPanel cardsGridPanel;
    private final JScrollPane gridScrollPane;
    private ThemePalette currentTheme;

    public LibraryPanel(LibraryStorageService storageService,
                        Consumer<Book> onOpenBook,
                        Runnable onNavigateToOnlineCatalog) {
        this.storageService = storageService;
        this.onOpenBook = onOpenBook;
        this.onNavigateToOnlineCatalog = onNavigateToOnlineCatalog;
        this.currentTheme = ThemePalette.byId(storageService.getSettings().getThemeId());

        setLayout(new BorderLayout(0, 0));

        topToolbar = new JPanel(new BorderLayout(12, 6));
        topToolbar.setBorder(BorderFactory.createEmptyBorder(14, 22, 8, 22));

        JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftActions.setOpaque(false);

        JButton addFileBtn = ModernUiFactory.createPrimaryButton("+ Открыть книгу / Файл", currentTheme);
        addFileBtn.addActionListener(e -> promptImportFiles());

        JButton importFolderBtn = ModernUiFactory.createFlatButton("Импорт папки", currentTheme);
        importFolderBtn.addActionListener(e -> promptImportFolder());

        JButton onlineBtn = ModernUiFactory.createFlatButton("Скачать книги онлайн", currentTheme);
        onlineBtn.addActionListener(e -> onNavigateToOnlineCatalog.run());

        leftActions.add(addFileBtn);
        leftActions.add(importFolderBtn);
        leftActions.add(onlineBtn);

        JPanel rightSearch = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rightSearch.setOpaque(false);

        searchField = new JTextField(15);
        searchField.setToolTipText("Поиск по названию, автору, серии или формату...");
        ModernUiFactory.styleTextField(searchField, currentTheme);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshResults(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshResults(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshResults(); }
        });

        sortCombo = new JComboBox<>(new String[]{
                "Недавние", "По названию", "По автору", "По прогрессу"
        });
        sortCombo.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        sortCombo.addActionListener(e -> {
            int idx = sortCombo.getSelectedIndex();
            activeSortMode = switch (idx) {
                case 1 -> "TITLE";
                case 2 -> "AUTHOR";
                case 3 -> "PROGRESS";
                default -> "RECENT";
            };
            refreshResults();
        });

        JLabel searchLbl = new JLabel("Поиск:");
        searchLbl.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        rightSearch.add(searchLbl);
        rightSearch.add(searchField);
        rightSearch.add(sortCombo);

        topToolbar.add(leftActions, BorderLayout.WEST);
        topToolbar.add(rightSearch, BorderLayout.EAST);

        filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        filterBar.setBorder(BorderFactory.createEmptyBorder(0, 22, 10, 22));
        rebuildFilterButtons();

        JPanel headerContainer = new JPanel(new BorderLayout());
        headerContainer.setOpaque(false);
        headerContainer.add(topToolbar, BorderLayout.NORTH);
        headerContainer.add(filterBar, BorderLayout.SOUTH);
        add(headerContainer, BorderLayout.NORTH);

        cardsGridPanel = new JPanel(new GridLayout(0, 1, 14, 14));
        cardsGridPanel.setBorder(BorderFactory.createEmptyBorder(14, 22, 24, 22));

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(cardsGridPanel, BorderLayout.NORTH);

        gridScrollPane = SmoothScrollUtil.enhanceAndReturn(new JScrollPane(wrapper));
        gridScrollPane.setBorder(BorderFactory.createEmptyBorder());
        gridScrollPane.getVerticalScrollBar().setUnitIncrement(42);
        add(gridScrollPane, BorderLayout.CENTER);

        previousPage.addActionListener(e -> turnPage(page - 1));
        nextPage.addActionListener(e -> turnPage(page + 1));
        pageBar.setOpaque(false);
        pageBar.add(previousPage);
        pageBar.add(pageLabel);
        pageBar.add(nextPage);
        add(pageBar, BorderLayout.SOUTH);
        applyTheme(currentTheme);
    }

    private final java.util.Map<String, JButton> readButtons = new java.util.HashMap<>();
    private final javax.swing.Timer arrivalTimer = new javax.swing.Timer(16, event -> updateArrival());
    private boolean animateResults;
    private long arrivalStarted;

    void setAnimationsEnabled(boolean enabled) {
        animateResults = enabled;
        if (!enabled) endArrival();
    }

    void focusBook(String id) {
        if (id != null && !readButtons.containsKey(id)) {
            for (int i = 0; i < results.size(); i++) {
                if (id.equals(results.get(i).getId())) {
                    turnPage(i / PAGE_SIZE);
                    break;
                }
            }
        }
        JButton button = readButtons.get(id);
        if (button != null) {
            gridScrollPane.validate();
            button.scrollRectToVisible(new java.awt.Rectangle(0, 0, button.getWidth(), button.getHeight()));
            button.requestFocusInWindow();
        } else {
            searchField.requestFocusInWindow();
        }
    }

    private void refreshResults() {
        page = 0;
        refreshBooksGrid();
        gridScrollPane.getVerticalScrollBar().setValue(0);
        if (animateResults && isShowing()) {
            arrivalStarted = System.nanoTime();
            updateArrival();
            arrivalTimer.restart();
        }
    }

    private void updateArrival() {
        if (!isShowing()) {
            endArrival();
            return;
        }
        float strength = Math.max(0, 1 - (System.nanoTime() - arrivalStarted) / 170_000_000f);
        setArrival(strength);
        if (strength == 0) arrivalTimer.stop();
    }

    private void endArrival() {
        arrivalTimer.stop();
        setArrival(0);
    }

    private void setArrival(float strength) {
        java.awt.Rectangle visible = cardsGridPanel.getVisibleRect();
        for (Component component : cardsGridPanel.getComponents()) {
            if (component instanceof BookCard card && (strength == 0 || card.getBounds().intersects(visible))) {
                card.setArrival(strength);
            }
        }
    }

    @Override public void removeNotify() {
        endArrival();
        super.removeNotify();
    }

    private void rebuildFilterButtons() {
        filterBar.removeAll();
        addShelfFilterButton("Все книги", "ALL");
        addShelfFilterButton("Читаю сейчас", "READING");
        addShelfFilterButton("Избранное", "FAVORITES");
        addShelfFilterButton("Прочитано", "FINISHED");
        filterBar.revalidate();
        filterBar.repaint();
    }

    private void addShelfFilterButton(String label, String code) {
        boolean active = code.equals(activeShelfFilter);
        JButton btn = active
                ? ModernUiFactory.createPrimaryButton(label, currentTheme)
                : ModernUiFactory.createFlatButton(label, currentTheme);
        btn.addActionListener(e -> {
            activeShelfFilter = code;
            int index = filterBar.getComponentZOrder(btn);
            rebuildFilterButtons();
            filterBar.getComponent(index).requestFocusInWindow();
            refreshResults();
        });
        filterBar.add(btn);
    }

    public void refreshBooksGrid() {
        results = storageService.getFilteredBooks(activeShelfFilter, searchField.getText(), activeSortMode);
        page = Math.min(page, lastPage());
        renderPage();
    }

    private int lastPage() {
        return Math.max(0, results.size() - 1) / PAGE_SIZE;
    }

    private void turnPage(int target) {
        page = Math.max(0, Math.min(target, lastPage()));
        renderPage();
        gridScrollPane.getVerticalScrollBar().setValue(0);
    }

    private void renderPage() {
        endArrival();
        readButtons.clear();
        cardsGridPanel.removeAll();
        int start = page * PAGE_SIZE;
        List<Book> books = results.subList(start, Math.min(start + PAGE_SIZE, results.size()));
        previousPage.setEnabled(page > 0);
        nextPage.setEnabled(page < lastPage());
        pageLabel.setText((page + 1) + " / " + (lastPage() + 1));
        pageBar.setVisible(results.size() > PAGE_SIZE);

        if (books.isEmpty()) {
            cardsGridPanel.setLayout(new BorderLayout());
            JLabel emptyLbl = new JLabel(
                    "<html><div style='text-align:center; padding:40px;'>"
                            + "<h2>На этой полке пока нет книг</h2>"
                            + "<p>Нажмите <b>«+ Открыть книгу / Файл»</b> для добавления FB2, EPUB, PDF, TXT, MD, HTML или CBZ,<br/>"
                            + "либо перейдите в раздел <b>«Скачать книги онлайн»</b>.</p></div></html>",
                    SwingConstants.CENTER
            );
            emptyLbl.setFont(ModernUiFactory.uiFont(Font.PLAIN, 14));
            emptyLbl.setForeground(currentTheme.getTextSecondary());
            cardsGridPanel.add(emptyLbl, BorderLayout.CENTER);
        } else {
            int cols = books.size() == 1 ? 1 : 2;
            cardsGridPanel.setLayout(new GridLayout(0, cols, 16, 16));
            for (Book book : books) {
                cardsGridPanel.add(createBookCard(book));
            }
        }
        cardsGridPanel.revalidate();
        cardsGridPanel.repaint();
    }

    private JPanel createBookCard(Book book) {
        BookCard card = new BookCard(16, currentTheme.getTextPrimary(), currentTheme.getTextSecondary());
        card.setBackground(currentTheme.getCardBackground());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(currentTheme.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)
        ));

        JLabel coverLabel = new JLabel(ModernUiFactory.getOrCreateBookCoverIcon(book, 92, 134));
        coverLabel.setVerticalAlignment(SwingConstants.TOP);
        card.add(coverLabel, BorderLayout.WEST);

        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setOpaque(false);

        JLabel titleLbl = ModernUiFactory.plainTextLabel(book.getTitle());
        titleLbl.setFont(ModernUiFactory.uiFont(Font.BOLD, 15));
        titleLbl.setForeground(currentTheme.getTextPrimary());
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel authorLbl = ModernUiFactory.plainTextLabel(book.getAuthor()
                + (book.getSeries().isEmpty() ? "" : " • " + book.getSeries()));
        authorLbl.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        authorLbl.setForeground(currentTheme.getTextSecondary());
        authorLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        String metaStr = String.format("Формат: %s  |  Слов: %,d  |  Прочитано: %.0f%%",
                book.getFormat(), book.getTotalWords(), book.getProgressPercent());
        JLabel metaLbl = ModernUiFactory.plainTextLabel(metaStr);
        metaLbl.setFont(ModernUiFactory.uiFont(Font.PLAIN, 12));
        metaLbl.setForeground(currentTheme.getTextSecondary());
        metaLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JProgressBar progressBar = new JProgressBar(0, 100);
        progressBar.setValue((int) Math.round(book.getProgressPercent()));
        progressBar.setStringPainted(true);
        progressBar.setFont(ModernUiFactory.uiFont(Font.PLAIN, 11));
        progressBar.setPreferredSize(new Dimension(240, 18));
        progressBar.setMaximumSize(new Dimension(320, 18));
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel actionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actionsRow.setOpaque(false);
        actionsRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton readBtn = ModernUiFactory.createPrimaryButton(
                book.getProgressPercent() > 0.5 ? "Продолжить чтение" : "Читать", currentTheme);
        readButtons.put(book.getId(), readBtn);
        readBtn.addActionListener(e -> onOpenBook.accept(book));

        JButton favBtn = ModernUiFactory.createFlatButton(
                book.isFavorite() ? "В избранном" : "В избранное", currentTheme);
        favBtn.addActionListener(e -> {
            UiPersistenceErrors.defer(this, storageService, "Не удалось сохранить изменение избранного.",
                    () -> storageService.toggleFavorite(book.getId()), this::refreshBooksGrid);
        });

        JButton infoBtn = ModernUiFactory.createFlatButton("О книге", currentTheme);
        infoBtn.addActionListener(e -> showBookDetailsDialog(book));

        JButton delBtn = ModernUiFactory.createFlatButton("Удалить", currentTheme);
        delBtn.addActionListener(e -> {
            int ans = JOptionPane.showConfirmDialog(this,
                    "Убрать книгу «" + book.getTitle() + "» из библиотеки?",
                    "Удаление книги", JOptionPane.YES_NO_OPTION);
            if (ans == JOptionPane.YES_OPTION) {
                UiPersistenceErrors.defer(this, storageService, "Не удалось удалить книгу из библиотеки.",
                        () -> storageService.removeBook(book, false), this::refreshBooksGrid);
            }
        });

        actionsRow.add(readBtn);
        actionsRow.add(favBtn);
        actionsRow.add(infoBtn);
        actionsRow.add(delBtn);

        infoPanel.add(titleLbl);
        infoPanel.add(Box.createVerticalStrut(4));
        infoPanel.add(authorLbl);
        infoPanel.add(Box.createVerticalStrut(6));
        infoPanel.add(metaLbl);
        infoPanel.add(Box.createVerticalStrut(8));
        infoPanel.add(progressBar);
        infoPanel.add(Box.createVerticalStrut(10));
        infoPanel.add(actionsRow);

        card.add(infoPanel, BorderLayout.CENTER);
        card.trackInteraction();
        return card;
    }

    private void showBookDetailsDialog(Book book) {
        String desc = book.getDescription().isBlank() ? "Аннотация отсутствует." : book.getDescription();
        long mins = book.getTotalReadingSeconds() / 60;
        String message = String.format(
                "Название: %s\nАвтор: %s\nСерия: %s\nФормат: %s\nЯзык: %s\nОбъем: %,d слов\nВремя чтения: %d мин.\nПуть к файлу: %s\n\nАннотация:\n%s",
                book.getTitle(),
                book.getAuthor(),
                book.getSeries().isEmpty() ? "—" : book.getSeries(),
                book.getFormat(),
                book.getLanguage(),
                book.getTotalWords(),
                mins,
                book.getFilePath(),
                desc
        );
        JOptionPane.showMessageDialog(this, message, "Информация о книге", JOptionPane.INFORMATION_MESSAGE);
    }

    public void promptImportFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Выберите книги или документы для добавления в LuminaRead");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Все форматы книг (FB2, FB2.ZIP, EPUB, PDF, TXT, MD, HTML, RTF, CBZ)",
                "fb2", "zip", "epub", "pdf", "txt", "md", "markdown", "html", "htm", "rtf", "cbz"
        ));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            importFiles(chooser.getSelectedFiles(), true);
        }
    }

    public void importFiles(File[] files) {
        importFiles(files, true);
    }

    private void importFiles(File[] files, boolean copyToLibrary) {
        if (files == null || files.length == 0) return;
        var selected = List.of(files);
        runImport(() -> selected, copyToLibrary, true);
    }

    private JDialog importDialog;

    boolean focusImport() {
        if (importDialog == null) return false;
        importDialog.toFront();
        return true;
    }

    private void runImport(Callable<List<File>> findFiles, boolean copyToLibrary, boolean openSingle) {
        if (focusImport()) return;

        JDialog dialog = new JDialog((Window) SwingUtilities.getWindowAncestor(this),
                "Импорт книг", Dialog.ModalityType.MODELESS);
        importDialog = dialog;
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        JLabel status = ModernUiFactory.plainTextLabel("Подготовка импорта…");
        status.setPreferredSize(new Dimension(360, status.getPreferredSize().height));
        JProgressBar bar = new JProgressBar();
        bar.setStringPainted(true);
        bar.setIndeterminate(true);
        JButton stop = new JButton("Остановить импорт");
        AtomicBoolean stopRequested = new AtomicBoolean();
        Runnable requestStop = () -> {
            stopRequested.set(true);
            stop.setEnabled(false);
            status.setText("Остановка после текущего файла…");
        };
        stop.addActionListener(event -> requestStop.run());
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { requestStop.run(); }
        });
        panel.add(status, BorderLayout.NORTH);
        panel.add(bar, BorderLayout.CENTER);
        panel.add(stop, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        new SwingWorker<ImportResult, ImportProgress>() {
            @Override protected ImportResult doInBackground() throws Exception {
                List<File> files = findFiles.call();
                List<Book> imported = new ArrayList<>();
                List<String> errors = new ArrayList<>();
                int processed = 0;
                for (File file : files) {
                    if (stopRequested.get()) break;
                    publish(new ImportProgress(processed, files.size(), file.getName()));
                    try {
                        imported.add(storageService.importBookFile(file, copyToLibrary));
                    } catch (Exception ex) {
                        errors.add(file.getName() + ": " + ex.getMessage());
                    }
                    processed++;
                }
                return new ImportResult(imported, errors, processed < files.size(), files.size());
            }

            @Override protected void process(List<ImportProgress> updates) {
                if (stopRequested.get()) return;
                ImportProgress latest = updates.get(updates.size() - 1);
                status.setText("Файл " + (latest.index() + 1) + " из " + latest.total() + ": " + latest.name());
                bar.setIndeterminate(false);
                bar.setMaximum(latest.total());
                bar.setValue(latest.index());
                bar.setString(latest.index() + " / " + latest.total());
            }

            @Override protected void done() {
                importDialog = null;
                dialog.dispose();
                try {
                    ImportResult result = get();
                    refreshBooksGrid();
                    if (openSingle && result.total() == 1 && !result.imported().isEmpty()) {
                        onOpenBook.accept(result.imported().get(0));
                    }
                    if (!result.errors().isEmpty()) {
                        String errors = String.join("\n", result.errors().stream().limit(10).toList());
                        int extra = result.errors().size() - 10;
                        if (extra > 0) errors += "\nЕщё ошибок: " + extra;
                        JOptionPane.showMessageDialog(LibraryPanel.this,
                                "Не удалось импортировать некоторые файлы:\n\n" + errors,
                                "Результат импорта", JOptionPane.WARNING_MESSAGE);
                    } else if (result.stopped() || !copyToLibrary) {
                        String title = result.stopped() ? "Импорт остановлен" : "Импорт завершён";
                        JOptionPane.showMessageDialog(LibraryPanel.this,
                                "Импортировано книг: " + result.imported().size(), title, JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    UiPersistenceErrors.show(LibraryPanel.this, "Не удалось импортировать книги.", ex.getCause());
                }
            }
        }.execute();
        dialog.setVisible(true);
    }

    private record ImportProgress(int index, int total, String name) {}
    private record ImportResult(List<Book> imported, List<String> errors, boolean stopped, int total) {}

    private void promptImportFolder() {
        if (focusImport()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Выберите папку с книгами для автоматического импорта");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path folder = chooser.getSelectedFile().toPath();
            runImport(() -> booksIn(folder), false, false);
        }
    }

    private static List<File> booksIn(Path folder) throws java.io.IOException {
        try (Stream<Path> paths = Files.walk(folder, 3)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".fb2") || n.endsWith(".fb2.zip") || n.endsWith(".epub")
                                || n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".pdf")
                                || n.endsWith(".html") || n.endsWith(".cbz");
                    })
                    .map(Path::toFile)
                    .toList();
        }
    }

    public void applyTheme(ThemePalette theme) {
        this.currentTheme = theme;
        setBackground(theme.getBackground());
        topToolbar.setBackground(theme.getSurface());
        filterBar.setBackground(theme.getSurface());
        pageLabel.setForeground(theme.getTextPrimary());
        cardsGridPanel.setBackground(theme.getBackground());
        gridScrollPane.getViewport().setBackground(theme.getBackground());
        ModernUiFactory.styleTextField(searchField, theme);
        rebuildFilterButtons();
        refreshBooksGrid();
    }
}
