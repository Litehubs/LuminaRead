package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.service.BookImport;
import com.luminaread.service.LibraryStorageService;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileFilter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class BookImportDialog {
    private static final Logger LOG = Logger.getLogger(BookImportDialog.class.getName());

    private final Component owner;
    private final BookImport books;
    private final Runnable refresh;
    private final Consumer<Book> open;
    private JDialog active;

    BookImportDialog(Component owner, LibraryStorageService library, Runnable refresh, Consumer<Book> open) {
        this.owner = owner;
        this.books = new BookImport(library);
        this.refresh = refresh;
        this.open = open;
    }

    boolean focus() {
        if (active == null) return false;
        active.toFront();
        return true;
    }

    void chooseFiles() {
        if (focus()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Выберите книги или документы для добавления в LuminaRead");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileFilter() {
            @Override public boolean accept(File file) { return file.isDirectory() || BookImport.supports(file); }
            @Override public String getDescription() { return "Книги и документы"; }
        });
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) importFiles(chooser.getSelectedFiles());
    }

    void chooseFolder() {
        if (focus()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Выберите папку с книгами для автоматического импорта");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
            Path folder = chooser.getSelectedFile().toPath();
            run((stop, progress) -> books.fromFolder(folder, stop, progress), false);
        }
    }

    void importFiles(File[] files) {
        if (files == null || files.length == 0) return;
        List<File> selected = List.of(files);
        if (SwingUtilities.isEventDispatchThread()) importFiles(selected);
        else SwingUtilities.invokeLater(() -> importFiles(selected));
    }

    private void importFiles(List<File> files) {
        run((stop, progress) -> books.fromFiles(files, stop, progress), true);
    }

    @FunctionalInterface
    private interface Job {
        BookImport.Result run(BooleanSupplier stop, Consumer<BookImport.Progress> progress) throws Exception;
    }

    private void run(Job job, boolean openSingle) {
        if (focus()) return;
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), "Импорт книг", Dialog.ModalityType.MODELESS);
        active = dialog;
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        var status = ModernUiFactory.plainTextLabel("Подготовка импорта…");
        status.setPreferredSize(new Dimension(360, status.getPreferredSize().height));
        JProgressBar bar = new JProgressBar();
        bar.setStringPainted(true);
        bar.setIndeterminate(true);
        JButton stop = new JButton("Остановить импорт");
        AtomicBoolean stopRequested = new AtomicBoolean();
        Runnable requestStop = () -> {
            stopRequested.set(true);
            stop.setEnabled(false);
            status.setText("Остановка импорта…");
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
        dialog.setLocationRelativeTo(owner);

        new SwingWorker<BookImport.Result, BookImport.Progress>() {
            @Override protected BookImport.Result doInBackground() throws Exception {
                return job.run(stopRequested::get, this::publish);
            }

            @Override protected void process(List<BookImport.Progress> updates) {
                if (stopRequested.get()) return;
                var latest = updates.get(updates.size() - 1);
                status.setText("Файл " + (latest.index() + 1) + " из " + latest.total() + ": " + latest.name());
                bar.setIndeterminate(false);
                bar.setMaximum(latest.total());
                bar.setValue(latest.index());
                bar.setString(latest.index() + " / " + latest.total());
            }

            @Override protected void done() {
                active = null;
                dialog.dispose();
                try {
                    showResult(get(), openSingle);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    refresh.run();
                    UiPersistenceErrors.show(owner, "Не удалось импортировать книги.", ex.getCause());
                }
            }
        }.execute();
        dialog.setVisible(true);
    }

    private void showResult(BookImport.Result result, boolean openSingle) {
        refresh.run();
        if (openSingle && result.total() == 1 && result.books().size() == 1) open.accept(result.books().get(0));

        if (!result.failures().isEmpty()) {
            result.failures().forEach(failure -> LOG.log(Level.FINE, "Не удалось импортировать книгу", failure.cause()));
            String errors = String.join("\n", result.failures().stream().limit(10).map(failure -> {
                String detail = failure.cause().getMessage();
                return failure.name() + ": " + (detail == null ? "Ошибка чтения или сохранения" : detail);
            }).toList());
            int extra = result.failures().size() - 10;
            if (extra > 0) errors += "\nЕщё ошибок: " + extra;
            JOptionPane.showMessageDialog(owner, "Не удалось импортировать некоторые файлы:\n\n" + errors,
                    "Результат импорта", JOptionPane.WARNING_MESSAGE);
        } else if (result.stopped() || !openSingle) {
            String title = result.stopped() ? "Импорт остановлен" : "Импорт завершён";
            JOptionPane.showMessageDialog(owner, "Импортировано книг: " + result.books().size(),
                    title, JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
