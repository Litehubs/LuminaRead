package com.luminaread.ui;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.*;
final class MangaSettingsView {
    record State(int readingMode, int alignmentMode, int zoomWidth, int pageGap, int autoScrollSpeed, double dimmerOpacity, boolean autoHideSidebar) {}
    record Actions(IntConsumer mode, IntConsumer alignment, BiConsumer<Integer, Boolean> width, IntConsumer gap, IntConsumer speed, IntConsumer brightness, Consumer<Color> background, Consumer<Boolean> hideSidebar) {}
    private MangaSettingsView() {}
    static JPanel create(State state, Actions actions, Runnable close) {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(new Color(28, 30, 40));
        content.setBorder(new EmptyBorder(18, 22, 18, 22));

        JLabel modeLbl = new JLabel("Режим чтения манги:");
        modeLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        modeLbl.setForeground(Color.WHITE);
        modeLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel modeBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        modeBar.setOpaque(false);
        modeBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        JRadioButton rbScroll = new JRadioButton("📜 Лента (Webtoon с автоскроллом)", state.readingMode() == MangaPanel.MODE_SCROLL);
        JRadioButton rbPaged = new JRadioButton("📖 Постранично (манга ←/→)", state.readingMode() == MangaPanel.MODE_PAGED);
        for (JRadioButton rb : new JRadioButton[]{rbScroll, rbPaged}) {
            rb.setOpaque(false);
            rb.setForeground(new Color(220, 225, 235));
            rb.setFont(new Font("SansSerif", Font.PLAIN, 13));
        }
        ButtonGroup bgMode = new ButtonGroup();
        bgMode.add(rbScroll); bgMode.add(rbPaged);
        rbScroll.addActionListener(e -> actions.mode().accept(MangaPanel.MODE_SCROLL));
        rbPaged.addActionListener(e -> actions.mode().accept(MangaPanel.MODE_PAGED));
        modeBar.add(rbScroll);
        modeBar.add(rbPaged);

        JLabel alignLbl = new JLabel("Положение страницы на экране:");
        alignLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        alignLbl.setForeground(Color.WHITE);
        alignLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel alignBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        alignBar.setOpaque(false);
        alignBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        JRadioButton rbLeft = new JRadioButton("Слева", state.alignmentMode() == MangaPanel.ALIGN_LEFT);
        JRadioButton rbCenter = new JRadioButton("По центру (Рекомендуется)", state.alignmentMode() == MangaPanel.ALIGN_CENTER);
        JRadioButton rbFit = new JRadioButton("По ширине окна", state.alignmentMode() == MangaPanel.ALIGN_FIT_WIDTH);
        for (JRadioButton rb : new JRadioButton[]{rbLeft, rbCenter, rbFit}) {
            rb.setOpaque(false);
            rb.setForeground(new Color(220, 225, 235));
            rb.setFont(new Font("SansSerif", Font.PLAIN, 13));
        }
        ButtonGroup bgAlign = new ButtonGroup();
        bgAlign.add(rbLeft); bgAlign.add(rbCenter); bgAlign.add(rbFit);
        rbLeft.addActionListener(e -> { actions.alignment().accept(MangaPanel.ALIGN_LEFT); });
        rbCenter.addActionListener(e -> { actions.alignment().accept(MangaPanel.ALIGN_CENTER); });
        rbFit.addActionListener(e -> { actions.alignment().accept(MangaPanel.ALIGN_FIT_WIDTH); });

        alignBar.add(rbLeft);
        alignBar.add(rbCenter);
        alignBar.add(rbFit);

        JLabel widthLbl = new JLabel("Ширина страницы: " + state.zoomWidth() + " px");
        widthLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        widthLbl.setForeground(Color.WHITE);
        widthLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JSlider widthSlider = new JSlider(500, 1800, state.zoomWidth());
        widthSlider.setOpaque(false);
        widthSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        widthSlider.setMaximumSize(new Dimension(460, 30));
        widthSlider.addChangeListener(e -> {
            int value = widthSlider.getValue();
            widthLbl.setText("Ширина страницы: " + value + " px");
            if (rbFit.isSelected()) rbCenter.setSelected(true);
            actions.width().accept(value, false);
        });

        JPanel presetBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        presetBar.setOpaque(false);
        presetBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        int[] presets = {700, 860, 1050, 1300};
        for (int p : presets) {
            JButton pBtn = new JButton(p + " px");
            pBtn.setFont(new Font("SansSerif", Font.PLAIN, 12));
            pBtn.setFocusPainted(false);
            pBtn.addActionListener(ev -> {
                widthSlider.setValue(p); rbCenter.setSelected(true); actions.width().accept(p, true);
            });
            presetBar.add(pBtn);
        }

        JLabel gapLbl = new JLabel("Отступ между сканами в ленте:");
        gapLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        gapLbl.setForeground(Color.WHITE);
        gapLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel gapBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        gapBar.setOpaque(false);
        gapBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        JRadioButton gap0 = new JRadioButton("0 px (Бесшовный вебтун)", state.pageGap() == 0);
        JRadioButton gap8 = new JRadioButton("8 px", state.pageGap() == 8);
        JRadioButton gap16 = new JRadioButton("16 px", state.pageGap() == 16);
        for (JRadioButton rb : new JRadioButton[]{gap0, gap8, gap16}) {
            rb.setOpaque(false);
            rb.setForeground(new Color(220, 225, 235));
            rb.setFont(new Font("SansSerif", Font.PLAIN, 13));
        }
        ButtonGroup bgGap = new ButtonGroup();
        bgGap.add(gap0); bgGap.add(gap8); bgGap.add(gap16);
        gap0.addActionListener(e -> { actions.gap().accept(0); });
        gap8.addActionListener(e -> { actions.gap().accept(8); });
        gap16.addActionListener(e -> { actions.gap().accept(16); });
        gapBar.add(gap0); gapBar.add(gap8); gapBar.add(gap16);

        JLabel speedLbl = new JLabel("Скорость автопрокрутки: " + state.autoScrollSpeed() + " px/с");
        speedLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        speedLbl.setForeground(Color.WHITE);
        speedLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JSlider speedSlider = new JSlider(10, 180, state.autoScrollSpeed());
        speedSlider.setOpaque(false);
        speedSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        speedSlider.setMaximumSize(new Dimension(460, 30));
        speedSlider.addChangeListener(e -> {
            int value = speedSlider.getValue(); speedLbl.setText("Скорость автопрокрутки: " + value + " px/с"); actions.speed().accept(value);
        });

        int brightPct = (int) Math.round((1.0 - state.dimmerOpacity()) * 100);
        JLabel brightLbl = new JLabel("Яркость страницы: " + brightPct + "%");
        brightLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        brightLbl.setForeground(Color.WHITE);
        brightLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JSlider brightSlider = new JSlider(30, 100, brightPct);
        brightSlider.setOpaque(false);
        brightSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        brightSlider.setMaximumSize(new Dimension(460, 30));
        brightSlider.addChangeListener(e -> {
            int value = brightSlider.getValue(); brightLbl.setText("Яркость страницы: " + value + "%"); actions.brightness().accept(value);
        });

        JLabel bgLbl = new JLabel("Цвет фона читалки:");
        bgLbl.setFont(new Font("SansSerif", Font.BOLD, 13));
        bgLbl.setForeground(Color.WHITE);
        bgLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel colorBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        colorBar.setOpaque(false);
        colorBar.setAlignmentX(Component.LEFT_ALIGNMENT);

        addBgColorOption(colorBar, actions.background(), "⬛ AMOLED", new Color(14, 15, 20));
        addBgColorOption(colorBar, actions.background(), "■ Тёмный", new Color(28, 30, 40));
        addBgColorOption(colorBar, actions.background(), "■ Сепия", new Color(42, 34, 28));
        addBgColorOption(colorBar, actions.background(), "◻ Бумага", new Color(244, 238, 220));

        JCheckBox zenCheck = new JCheckBox("Скрывать боковую панель приложения при чтении (Дзен-режим)", state.autoHideSidebar());
        zenCheck.setFont(new Font("SansSerif", Font.PLAIN, 13));
        zenCheck.setForeground(new Color(220, 225, 235));
        zenCheck.setOpaque(false);
        zenCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        zenCheck.addActionListener(e -> actions.hideSidebar().accept(zenCheck.isSelected()));

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
        content.add(alignLbl);
        content.add(alignBar);
        content.add(Box.createVerticalStrut(10));
        content.add(widthLbl);
        content.add(widthSlider);
        content.add(presetBar);
        content.add(Box.createVerticalStrut(10));
        content.add(gapLbl);
        content.add(gapBar);
        content.add(Box.createVerticalStrut(10));
        content.add(speedLbl);
        content.add(speedSlider);
        content.add(Box.createVerticalStrut(10));
        content.add(brightLbl);
        content.add(brightSlider);
        content.add(Box.createVerticalStrut(10));
        content.add(bgLbl);
        content.add(colorBar);
        content.add(Box.createVerticalStrut(10));
        content.add(zenCheck);
        content.add(Box.createVerticalStrut(16));
        content.add(closeBtn);

        return content;
    }
    private static void addBgColorOption(JPanel panel, Consumer<Color> choose, String label, Color color) {
        JButton button = new JButton(label); button.setFont(new Font("SansSerif", Font.PLAIN, 12)); button.setFocusPainted(false);
        button.addActionListener(e -> choose.accept(color)); panel.add(button);
    }
}
