package com.luminaread.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Builds the reader settings independently of the panel that owns the state. */
final class RanobeSettingsView {
    record State(int readingMode, int fontSize, String fontFamily, int textBlockWidth, int autoScrollSpeed) {}
    record Actions(IntConsumer mode, IntConsumer fontSize, Consumer<String> fontFamily, IntConsumer width,
                   Consumer<String> theme, IntConsumer speed) {}
    private RanobeSettingsView() {}

    static JPanel create(State state, Actions actions, Runnable close) {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(new Color(28, 30, 40));
        content.setBorder(new EmptyBorder(18, 22, 18, 22));

        JLabel modeLbl = new JLabel("Режим отображения:");
        modeLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        modeLbl.setForeground(Color.WHITE);

        JPanel modeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        modeBar.setOpaque(false);
        JRadioButton rbScroll = new JRadioButton("📜 Лента", state.readingMode() == RanobePanel.MODE_SCROLL);
        JRadioButton rbPaged = new JRadioButton("📖 Постранично", state.readingMode() == RanobePanel.MODE_PAGED);
        ButtonGroup bgMode = new ButtonGroup();
        bgMode.add(rbScroll); bgMode.add(rbPaged);
        for (JRadioButton rb : new JRadioButton[]{rbScroll, rbPaged}) {
            rb.setOpaque(false); rb.setForeground(new Color(220, 225, 235));
        }
        rbScroll.addActionListener(e -> { actions.mode().accept(RanobePanel.MODE_SCROLL); });
        rbPaged.addActionListener(e -> { actions.mode().accept(RanobePanel.MODE_PAGED); });
        modeBar.add(rbScroll); modeBar.add(rbPaged);

        JLabel fontLbl = new JLabel("Размер шрифта: " + state.fontSize() + " пт");
        fontLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        fontLbl.setForeground(Color.WHITE);

        JSlider fontSlider = new JSlider(12, 36, state.fontSize());
        fontSlider.setOpaque(false);
        fontSlider.setMaximumSize(new Dimension(460, 30));
        fontSlider.addChangeListener(e -> {
            int value = fontSlider.getValue();
            fontLbl.setText("Размер шрифта: " + value + " пт");
            actions.fontSize().accept(value);
        });

        JComboBox<String> fontCombo = new JComboBox<>(new String[]{
                "Georgia", "Palatino", "Times New Roman", "Verdana", "Trebuchet MS", "Monospaced"
        });
        fontCombo.setSelectedItem(state.fontFamily());
        fontCombo.setMaximumSize(new Dimension(200, 28));
        fontCombo.addActionListener(e -> {
            actions.fontFamily().accept((String) fontCombo.getSelectedItem());
        });

        JLabel widthLbl = new JLabel("Ширина текстовой колонки: " + state.textBlockWidth() + " px");
        widthLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        widthLbl.setForeground(Color.WHITE);

        JSlider widthSlider = new JSlider(500, 1600, state.textBlockWidth());
        widthSlider.setOpaque(false);
        widthSlider.setMaximumSize(new Dimension(460, 30));
        widthSlider.addChangeListener(e -> {
            int value = widthSlider.getValue();
            widthLbl.setText("Ширина текстовой колонки: " + value + " px");
            actions.width().accept(value);
        });

        JLabel themeLbl = new JLabel("Цветовая тема:");
        themeLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        themeLbl.setForeground(Color.WHITE);

        JPanel themeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        themeBar.setOpaque(false);
        addThemeButton(themeBar, actions.theme(), "⬛ AMOLED", "NIGHT_AMOLED");
        addThemeButton(themeBar, actions.theme(), "■ Nord", "NORD");
        addThemeButton(themeBar, actions.theme(), "■ Сепия", "SEPIA");
        addThemeButton(themeBar, actions.theme(), "◻ Бумага", "DAY_PAPER");
        addThemeButton(themeBar, actions.theme(), "🍃 Мята", "MINT");

        JLabel speedLbl = new JLabel("Скорость автопрокрутки: " + state.autoScrollSpeed() + " px/с");
        speedLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        speedLbl.setForeground(Color.WHITE);

        JSlider speedSlider = new JSlider(10, 180, state.autoScrollSpeed());
        speedSlider.setOpaque(false);
        speedSlider.setMaximumSize(new Dimension(460, 30));
        speedSlider.addChangeListener(e -> {
            int value = speedSlider.getValue();
            speedLbl.setText("Скорость автопрокрутки: " + value + " px/с");
            actions.speed().accept(value);
        });

        JButton closeBtn = new JButton("✓ Готово");
        closeBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
        closeBtn.setBackground(new Color(67, 160, 71));
        closeBtn.setForeground(Color.WHITE);
        closeBtn.setFocusPainted(false);
        closeBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        closeBtn.addActionListener(e -> close.run());

        content.add(modeLbl);
        content.add(modeBar);
        content.add(Box.createVerticalStrut(10));
        content.add(fontLbl);
        content.add(fontSlider);
        content.add(fontCombo);
        content.add(Box.createVerticalStrut(10));
        content.add(widthLbl);
        content.add(widthSlider);
        content.add(Box.createVerticalStrut(10));
        content.add(themeLbl);
        content.add(themeBar);
        content.add(Box.createVerticalStrut(10));
        content.add(speedLbl);
        content.add(speedSlider);
        content.add(Box.createVerticalStrut(16));
        content.add(closeBtn);

        return content;
    }

    private static void addThemeButton(JPanel bar, Consumer<String> choose, String name, String presetId) {
        JButton button = new JButton(name);
        button.setFont(new Font("SansSerif", Font.PLAIN, 12));
        button.setFocusPainted(false);
        button.addActionListener(e -> choose.accept(presetId));
        bar.add(button);
    }
}
