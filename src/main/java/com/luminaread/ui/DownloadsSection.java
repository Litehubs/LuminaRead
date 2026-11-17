package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.DownloadTask;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.OnlineCatalogService;
import com.luminaread.util.WrapLayout;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

final class DownloadsSection extends JPanel {
    private final OnlineCatalogService catalog;
    private final DownloadJobs jobs;
    private final Consumer<Book> openBook;
    private final Runnable downloadListener = this::refresh;
    private final JPanel list = new JPanel();
    private final JTextField title = new JTextField("Новая книга", 14);
    private final JTextField url = new JTextField("https://www.gutenberg.org/ebooks/1342.epub3.images", 26);
    private ThemePalette theme;

    DownloadsSection(OnlineCatalogService catalog, LibraryStorageService library, Consumer<Book> openBook) {
        this.catalog = catalog;
        this.jobs = new DownloadJobs(catalog, library);
        this.openBook = openBook;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        list.setAlignmentX(Component.LEFT_ALIGNMENT);
        catalog.addDownloadListener(downloadListener);
    }

    void applyTheme(ThemePalette theme) {
        this.theme = theme;
        removeAll();
        setBackground(theme.getSurface());
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(16, 18, 16, 18)));

        JLabel heading = new JLabel("Загрузки книг");
        heading.setFont(ModernUiFactory.uiFont(Font.BOLD, 16));
        heading.setForeground(theme.getTextPrimary());
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(heading);
        add(Box.createVerticalStrut(8));

        JPanel form = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        form.setOpaque(false);
        form.setAlignmentX(Component.LEFT_ALIGNMENT);
        addField(form, "Название:", title);
        addField(form, "Ссылка:", url);

        JButton download = ModernUiFactory.createPrimaryButton("Скачать по ссылке", theme);
        download.addActionListener(event -> startDownload());
        form.add(download);

        JButton clear = ModernUiFactory.createFlatButton("Очистить завершённые", theme);
        clear.addActionListener(event -> jobs.clear(
                error -> UiPersistenceErrors.show(this, "Не удалось очистить список загрузок.", error)));
        form.add(clear);

        add(form);
        add(Box.createVerticalStrut(10));
        add(list);
        refresh();
    }

    private void addField(JPanel form, String caption, JTextField field) {
        ModernUiFactory.styleTextField(field, theme);
        JLabel label = new JLabel(caption);
        label.setForeground(theme.getTextPrimary());
        label.setLabelFor(field);
        form.add(label);
        form.add(field);
    }

    void refresh() {
        if (theme == null) return;
        list.removeAll();
        List<DownloadTask> tasks = catalog.getDownloadTasks();
        if (tasks.isEmpty()) {
            JLabel empty = new JLabel("Загрузок пока нет.");
            empty.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
            empty.setForeground(theme.getTextSecondary());
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            list.add(empty);
        } else {
            for (DownloadTask task : tasks) {
                list.add(createDownloadTaskRow(task));
                list.add(Box.createVerticalStrut(8));
            }
        }
        list.revalidate();
        list.repaint();
    }

    private void startDownload() {
        String link = url.getText().trim();
        if (link.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Введите прямую ссылку на файл книги.");
            return;
        }
        String name = title.getText().trim();
        DownloadTask task = catalog.createLibraryDownload(name.isEmpty() ? "Скачанная книга" : name, "АВТО", link);
        jobs.start(task, book -> refresh(), error -> refresh());
    }

    void disposeResources() {
        catalog.removeDownloadListener(downloadListener);
        jobs.close();
    }

    private JPanel createDownloadTaskRow(DownloadTask task) {
        JPanel row = new JPanel(new BorderLayout(14, 6));
        row.setBackground(theme.getSurface());
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)
        ));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(920, 86));

        JPanel leftCol = new JPanel();
        leftCol.setLayout(new BoxLayout(leftCol, BoxLayout.Y_AXIS));
        leftCol.setOpaque(false);

        JLabel titleLbl = ModernUiFactory.plainTextLabel(task.getTitle() + "  [" + task.getFormat() + "]");
        titleLbl.setFont(ModernUiFactory.uiFont(Font.BOLD, 14));
        titleLbl.setForeground(theme.getTextPrimary());
        titleLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel detailLbl = ModernUiFactory.plainTextLabel(task.getFormattedProgressDetails());
        detailLbl.setFont(ModernUiFactory.uiFont(Font.PLAIN, 12));
        detailLbl.setForeground(theme.getTextSecondary());
        detailLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        leftCol.add(titleLbl);
        leftCol.add(Box.createVerticalStrut(4));
        leftCol.add(detailLbl);

        JPanel rightCol = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        rightCol.setOpaque(false);

        JProgressBar bar = new JProgressBar(0, 100);
        bar.setValue(task.getProgressPercent());
        bar.setString(task.getProgressPercent() + "%");
        bar.setStringPainted(true);
        bar.setFont(ModernUiFactory.uiFont(Font.BOLD, 12));
        bar.setPreferredSize(new Dimension(210, 22));
        rightCol.add(bar);

        if (task.getStatus() == DownloadTask.Status.COMPLETED && task.getImportedBook() != null) {
            JButton openBtn = ModernUiFactory.createPrimaryButton("Читать книгу", theme);
            openBtn.addActionListener(e -> openBook.accept(task.getImportedBook()));
            rightCol.add(openBtn);
        } else if (task.getStatus() == DownloadTask.Status.FAILED && task.getSavedFile() != null) {
            JButton retry = ModernUiFactory.createFlatButton("Повторить импорт", theme);
            retry.addActionListener(event -> {
                retry.setEnabled(false);
                jobs.start(task, book -> refresh(), error -> refresh());
            });
            rightCol.add(retry);
        }

        row.add(leftCol, BorderLayout.CENTER);
        row.add(rightCol, BorderLayout.EAST);
        return row;
    }

}
