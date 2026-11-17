package com.luminaread.ui;

import com.luminaread.service.LibraryStorageService;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.logging.Level;
import java.util.logging.Logger;

final class UiPersistenceErrors {
    private UiPersistenceErrors() {}

    static boolean save(Component owner, String message, Runnable action) {
        try {
            action.run();
            return true;
        } catch (java.io.UncheckedIOException | SecurityException ex) {
            show(owner, message, ex);
            return false;
        }
    }

    static void defer(Component owner, LibraryStorageService storage, String message, Runnable action) {
        defer(owner, storage, message, action, () -> {});
    }

    static void defer(Component owner, LibraryStorageService storage, String message, Runnable action, Runnable done) {
        storage.writeAsync(action).whenComplete((unused, error) -> SwingUtilities.invokeLater(() -> {
            if (error == null) done.run();
            else show(owner, message, error.getCause() == null ? error : error.getCause());
        }));
    }

    static void show(Component owner, String message, Throwable error) {
        Logger.getLogger(owner.getClass().getName()).log(Level.WARNING, message, error);
        if (owner.isShowing()) {
            JOptionPane.showMessageDialog(owner, message, "Ошибка сохранения", JOptionPane.ERROR_MESSAGE);
        }
    }
}
