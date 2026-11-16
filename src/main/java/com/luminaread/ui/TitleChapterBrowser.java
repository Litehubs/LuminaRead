package com.luminaread.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.IntConsumer;

final class TitleChapterBrowser extends JPanel {
    TitleChapterBrowser(JList<?> chapters, JLabel status, JComponent filter, Color selectionColor,
                        IntConsumer openChapter) {
        super(new BorderLayout(0, 8));
        setOpaque(false);

        DefaultListCellRenderer renderer = new DefaultListCellRenderer();
        renderer.putClientProperty("html.disable", Boolean.TRUE);
        chapters.setCellRenderer(renderer);
        chapters.setBackground(new Color(28, 30, 40));
        chapters.setForeground(Color.WHITE);
        chapters.setFont(new Font("SansSerif", Font.PLAIN, 14));
        chapters.setFixedCellHeight(38);
        chapters.setSelectionBackground(selectionColor);
        chapters.setSelectionForeground(Color.WHITE);
        chapters.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    int index = chapters.locationToIndex(event.getPoint());
                    Rectangle bounds = index < 0 ? null : chapters.getCellBounds(index, index);
                    if (bounds != null && bounds.contains(event.getPoint())) {
                        openChapter.accept(index);
                    }
                }
            }
        });

        status.setForeground(new Color(225, 170, 130));
        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.setOpaque(false);
        if (filter != null) {
            header.add(filter, BorderLayout.NORTH);
        }
        header.add(status, BorderLayout.CENTER);

        JButton read = new JButton("▶ Читать выбранную главу");
        read.setFont(new Font("SansSerif", Font.BOLD, 14));
        read.setBackground(new Color(67, 160, 71));
        read.setForeground(Color.WHITE);
        read.setFocusPainted(false);
        read.addActionListener(event -> {
            if (chapters.getModel().getSize() > 0) {
                openChapter.accept(Math.max(0, chapters.getSelectedIndex()));
            }
        });

        JScrollPane scroll = new JScrollPane(chapters);
        SmoothScrollUtil.enhance(scroll);
        add(header, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(read, BorderLayout.SOUTH);
    }
}
