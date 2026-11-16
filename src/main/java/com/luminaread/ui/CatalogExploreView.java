package com.luminaread.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

final class CatalogExploreView extends JPanel {
    private static final Color BACKGROUND = new Color(20, 22, 29);

    CatalogExploreView(String title, Color titleColor, JButton authButton, JPanel filters,
                       JTextField searchField, JButton searchButton, JPanel results, JLabel status) {
        super(new BorderLayout(0, 14));
        setBackground(BACKGROUND);
        setBorder(new EmptyBorder(18, 22, 18, 22));

        JLabel heading = new JLabel(title);
        heading.setFont(new Font("SansSerif", Font.BOLD, 20));
        heading.setForeground(titleColor);
        authButton.setFont(new Font("SansSerif", Font.BOLD, 12));
        authButton.setFocusPainted(false);
        JPanel headingRow = row();
        headingRow.add(heading, BorderLayout.WEST);
        headingRow.add(authButton, BorderLayout.EAST);

        searchField.setFont(new Font("SansSerif", Font.PLAIN, 15));
        searchButton.setFont(new Font("SansSerif", Font.BOLD, 13));
        searchButton.setForeground(Color.WHITE);
        searchButton.setFocusPainted(false);
        JPanel searchRow = row();
        searchRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        searchRow.add(searchField, BorderLayout.CENTER);
        searchRow.add(searchButton, BorderLayout.EAST);

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);
        header.add(headingRow);
        header.add(Box.createVerticalStrut(8));
        header.add(filters);
        header.add(Box.createVerticalStrut(8));
        header.add(searchRow);

        results.setBackground(BACKGROUND);
        JScrollPane scroll = new JScrollPane(results);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BACKGROUND);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        SmoothScrollUtil.enhance(scroll);

        status.setForeground(new Color(160, 168, 185));
        status.setFont(new Font("SansSerif", Font.PLAIN, 13));
        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    static JPanel filters(JComboBox<String> category, JComboBox<String> source, JButton clearHistory) {
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        filters.setOpaque(false);
        filters.setAlignmentX(Component.LEFT_ALIGNMENT);
        filters.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        addFilter(filters, "Раздел:", category);
        if (source != null) {
            addFilter(filters, "Источник:", source);
        }
        filters.add(clearHistory);
        return filters;
    }

    private static void addFilter(JPanel filters, String caption, JComboBox<String> combo) {
        CatalogListControls.applyReadableColors(combo);
        JLabel label = new JLabel(caption);
        label.setForeground(new Color(180, 185, 200));
        label.setLabelFor(combo);
        filters.add(label);
        filters.add(combo);
    }

    private static JPanel row() {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }
}
