package com.luminaread.ui;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class CatalogListControls {
    private CatalogListControls() {}

    static boolean confirmClear(Component owner) {
        return JOptionPane.showConfirmDialog(owner,
                "Удалить все записи из истории? Прогресс чтения сохранится.",
                "Очистить историю", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    static <T> void edit(Component owner, Map<String, T> list, Runnable change, Runnable save, Runnable refresh) {
        Map<String, T> before = new LinkedHashMap<>(list);
        change.run();
        try {
            save.run();
        } catch (UncheckedIOException | SecurityException ex) {
            list.clear();
            list.putAll(before);
            UiPersistenceErrors.show(owner, "Не удалось сохранить изменение списка.", ex);
            return;
        }
        refresh.run();
    }
}
