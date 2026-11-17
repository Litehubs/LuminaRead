package com.luminaread.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

/** A catalog entry; loading its cover and opening it belong to the caller. */
final class CatalogCard {
    private CatalogCard() {}

    static JPanel create(String titleText, String badgeText, String description, int descriptionLimit,
                         Color badgeColor, String actionText, Color actionColor,
                         Consumer<JLabel> loadCover, Runnable open) {
        return create(titleText, badgeText, description, descriptionLimit, badgeColor, actionText,
                actionColor, loadCover, open, null, null);
    }

    static JPanel create(String titleText, String badgeText, String description, int descriptionLimit,
                         Color badgeColor, String actionText, Color actionColor,
                         Consumer<JLabel> loadCover, Runnable open, String removeLabel, Runnable remove) {
        BookCard card = new BookCard(12, Color.WHITE, new Color(175, 182, 198));
        card.setBackground(new Color(31, 34, 45));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(50, 55, 72), 1, true), new EmptyBorder(10, 10, 10, 10)));
        JLabel cover = new JLabel("Обложка", SwingConstants.CENTER);
        cover.setPreferredSize(new Dimension(92, 132));
        cover.setOpaque(true);
        cover.setBackground(new Color(22, 24, 32));
        cover.setForeground(new Color(120, 125, 140));
        loadCover.accept(cover);

        JLabel title = ModernUiFactory.plainTextLabel(titleText);
        title.setFont(new Font("SansSerif", Font.BOLD, 14));
        title.setForeground(Color.WHITE);
        JLabel badge = ModernUiFactory.plainTextLabel(badgeText);
        badge.setFont(new Font("SansSerif", Font.PLAIN, 12));
        badge.setForeground(badgeColor);
        String text = description != null ? description : "";
        JTextArea desc = new JTextArea(text.length() > descriptionLimit ? text.substring(0, descriptionLimit) + "..." : text);
        desc.setLineWrap(true);
        desc.setWrapStyleWord(true);
        desc.setEditable(false);
        desc.setOpaque(false);
        desc.setForeground(new Color(175, 182, 198));
        desc.setFont(new Font("SansSerif", Font.PLAIN, 12));
        JButton button = new JButton(actionText);
        button.setBackground(actionColor);
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.addActionListener(e -> open.run());
        JPanel header = new JPanel(new GridLayout(2, 1));
        header.setOpaque(false);
        header.add(title);
        header.add(badge);
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setOpaque(false);
        right.add(header, BorderLayout.NORTH);
        right.add(desc, BorderLayout.CENTER);
        if (remove == null) {
            right.add(button, BorderLayout.SOUTH);
        } else {
            JPanel actions = new JPanel(new BorderLayout(6, 0));
            actions.setOpaque(false);
            JButton removeButton = new JButton(removeLabel);
            removeButton.addActionListener(e -> remove.run());
            actions.add(button, BorderLayout.CENTER);
            actions.add(removeButton, BorderLayout.EAST);
            right.add(actions, BorderLayout.SOUTH);
        }
        card.add(cover, BorderLayout.WEST);
        card.add(right, BorderLayout.CENTER);
        card.trackInteraction();
        return card;
    }
}
