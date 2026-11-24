package com.luminaread.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import java.util.function.Function;

final class NotesPages<T> extends JPanel {
    private static final int PAGE_SIZE = 20;

    private final JPanel rows = new JPanel();
    private final JScrollPane scroll = SmoothScrollUtil.enhanceAndReturn(new JScrollPane(rows));
    private final JPanel navigation = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
    private final JButton first = new JButton("Первая");
    private final JButton previous = new JButton("Назад");
    private final JButton next = new JButton("Дальше");
    private final JButton last = new JButton("Последняя");
    private final JLabel position = new JLabel();
    private final String emptyMessage;
    private final Function<T, JPanel> row;
    private List<T> items = List.of();
    private int page;

    NotesPages(String title, String emptyMessage, Function<T, JPanel> row) {
        super(new BorderLayout());
        this.emptyMessage = emptyMessage;
        this.row = row;

        setBorder(BorderFactory.createTitledBorder(title));
        setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);

        navigation.setOpaque(false);
        navigation.add(first);
        navigation.add(previous);
        navigation.add(position);
        navigation.add(next);
        navigation.add(last);
        add(navigation, BorderLayout.SOUTH);

        first.addActionListener(event -> showPage(0));
        previous.addActionListener(event -> showPage(page - 1));
        next.addActionListener(event -> showPage(page + 1));
        last.addActionListener(event -> showPage(lastPage()));
    }

    void applyTheme(ThemePalette theme) {
        rows.setBackground(theme.getBackground());
        scroll.getViewport().setBackground(theme.getBackground());
        position.setForeground(theme.getTextSecondary());
    }

    void refresh(List<T> items) {
        this.items = items;
        page = Math.min(page, lastPage());
        render();
    }

    private int lastPage() {
        return Math.max(0, items.size() - 1) / PAGE_SIZE;
    }

    private void showPage(int target) {
        page = target;
        render();
        scroll.getVerticalScrollBar().setValue(0);
    }

    private void render() {
        rows.removeAll();
        if (items.isEmpty()) {
            var empty = new JLabel(emptyMessage);
            empty.setForeground(position.getForeground());
            rows.add(empty);
        }
        int start = page * PAGE_SIZE;
        for (T item : items.subList(start, Math.min(start + PAGE_SIZE, items.size()))) {
            rows.add(row.apply(item));
            rows.add(Box.createVerticalStrut(8));
        }

        position.setText((page + 1) + " / " + (lastPage() + 1));
        first.setEnabled(page > 0);
        previous.setEnabled(page > 0);
        next.setEnabled(page < lastPage());
        last.setEnabled(page < lastPage());
        navigation.setVisible(lastPage() > 0);
        revalidate();
        repaint();
    }
}
