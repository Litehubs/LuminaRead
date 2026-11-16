package com.luminaread.ui;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import java.awt.Color;
import java.awt.Component;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class CatalogListControls {
    private CatalogListControls() {}

    static void applyReadableColors(JComboBox<String> combo) {
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean selected, boolean hasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, hasFocus);
                label.setOpaque(true);
                label.setBackground(selected ? new Color(49, 67, 129) : new Color(242, 244, 249));
                label.setForeground(selected ? Color.WHITE : new Color(25, 30, 41));
                return label;
            }
        });
    }

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
