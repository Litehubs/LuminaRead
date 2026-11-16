package com.luminaread.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;

final class TitleDetailsCard extends JPanel {
    private final JPanel coverColumn = new JPanel();

    TitleDetailsCard(JLabel cover, JLabel title, JLabel badges, JTextArea description, Color accent) {
        super(new BorderLayout(18, 0));
        setBackground(new Color(30, 33, 44));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        cover.setPreferredSize(new Dimension(145, 205));
        cover.setHorizontalAlignment(SwingConstants.CENTER);
        cover.setForeground(Color.GRAY);
        cover.setAlignmentX(Component.LEFT_ALIGNMENT);
        coverColumn.setLayout(new BoxLayout(coverColumn, BoxLayout.Y_AXIS));
        coverColumn.setOpaque(false);
        coverColumn.add(cover);

        title.putClientProperty("html.disable", Boolean.TRUE);
        badges.putClientProperty("html.disable", Boolean.TRUE);
        title.setFont(new Font("SansSerif", Font.BOLD, 19));
        title.setForeground(Color.WHITE);
        badges.setFont(new Font("SansSerif", Font.BOLD, 13));
        badges.setForeground(accent);
        JPanel heading = new JPanel(new GridLayout(2, 1, 0, 4));
        heading.setOpaque(false);
        heading.add(title);
        heading.add(badges);

        description.setEditable(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setOpaque(false);
        description.setForeground(new Color(205, 210, 222));
        description.setFont(new Font("SansSerif", Font.PLAIN, 13));
        JScrollPane scroll = new JScrollPane(description);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        SmoothScrollUtil.enhance(scroll);

        JPanel metadata = new JPanel(new BorderLayout(0, 8));
        metadata.setOpaque(false);
        metadata.add(heading, BorderLayout.NORTH);
        metadata.add(scroll, BorderLayout.CENTER);
        add(coverColumn, BorderLayout.WEST);
        add(metadata, BorderLayout.CENTER);
    }

    void addCoverControl(Component control) {
        coverColumn.add(control);
    }
}
