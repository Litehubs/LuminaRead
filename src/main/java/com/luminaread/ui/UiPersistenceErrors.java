package com.luminaread.ui;

import javax.swing.JOptionPane;
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

    static void show(Component owner, String message, RuntimeException error) {
        Logger.getLogger(owner.getClass().getName()).log(Level.WARNING, message, error);
        if (owner.isShowing()) {
            JOptionPane.showMessageDialog(owner, message, "Ошибка сохранения", JOptionPane.ERROR_MESSAGE);
        }
    }
}
