package com.luminaread.ui;

import com.luminaread.model.ReaderSettings;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.function.Consumer;

final class ReaderTypographyDialog {
    private ReaderTypographyDialog() {}

    static void show(ReaderPanel owner, ReaderSettings s, ThemePalette currentTheme,
                     Consumer<ThemePalette> changeTheme, Runnable save, Runnable refresh) {
        JDialog dialog = new JDialog(
                SwingUtilities.getWindowAncestor(owner),
                "Настройки чтения и оформления",
                Dialog.ModalityType.APPLICATION_MODAL
        );
        dialog.setLayout(new BorderLayout(12, 12));

        JPanel form = new JPanel(new GridLayout(0, 2, 12, 10));
        form.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));

        JComboBox<ThemePalette> themeCombo = new JComboBox<>(
                ThemePalette.PRESETS.values().toArray(new ThemePalette[0]));
        themeCombo.setSelectedItem(ThemePalette.byId(s.getThemeId()));

        JComboBox<String> fontCombo = new JComboBox<>(new String[]{
                "Georgia", "Palatino", "Times New Roman", "Arial", "Helvetica Neue", "Verdana", "Monospaced"
        });
        fontCombo.setSelectedItem(s.getFontFamily());

        JSlider fontSizeSlider = new JSlider(12, 36, s.getFontSize());
        fontSizeSlider.setMajorTickSpacing(4);
        fontSizeSlider.setPaintTicks(true);
        fontSizeSlider.setPaintLabels(true);

        JComboBox<String> lineHeightCombo = new JComboBox<>(new String[]{
                "1.2 (Плотное)", "1.4 (Стандартное)", "1.6 (Комфортное)", "1.8 (Воздушное)", "2.0 (Двойной интервал)"
        });
        int lhIndex = (int) Math.round((s.getLineHeight() - 1.2) / 0.2);
        lineHeightCombo.setSelectedIndex(Math.max(0, Math.min(4, lhIndex)));

        JComboBox<String> alignCombo = new JComboBox<>(new String[]{
                "По левому краю", "По ширине страницы"
        });
        alignCombo.setSelectedIndex(s.getTextAlignment() == ReaderSettings.TextAlignment.LEFT ? 0 : 1);

        JSlider marginSlider = new JSlider(20, 180, s.getHorizontalMarginPx());
        marginSlider.setMajorTickSpacing(40);
        marginSlider.setPaintTicks(true);
        marginSlider.setPaintLabels(true);

        JComboBox<String> modeCombo = new JComboBox<>(new String[]{
                "Листание", "Вертикальная прокрутка"
        });
        modeCombo.setSelectedIndex(s.getReadingMode() == ReaderSettings.ReadingMode.PAGED ? 0 : 1);

        JCheckBox twoColCheck = new JCheckBox("Две страницы рядом", s.isTwoColumnSpread());
        JSlider autoScrollSpeedSlider = new JSlider(10, 150, s.getAutoScrollSpeed());
        JSlider ttsRateSlider = new JSlider(110, 300, s.getTtsRateWpm());

        form.add(new JLabel("Цветовая тема:"));
        ModernUiFactory.applyReadableComboColors(themeCombo);
        form.add(themeCombo);
        form.add(new JLabel("Шрифт:"));
        ModernUiFactory.applyReadableComboColors(fontCombo);
        form.add(fontCombo);
        form.add(new JLabel("Размер шрифта (пт):"));
        form.add(fontSizeSlider);
        form.add(new JLabel("Межстрочный интервал:"));
        ModernUiFactory.applyReadableComboColors(lineHeightCombo);
        form.add(lineHeightCombo);
        form.add(new JLabel("Выравнивание текста:"));
        ModernUiFactory.applyReadableComboColors(alignCombo);
        form.add(alignCombo);
        form.add(new JLabel("Боковые поля страницы (px):"));
        form.add(marginSlider);
        form.add(new JLabel("Режим чтения:"));
        ModernUiFactory.applyReadableComboColors(modeCombo);
        form.add(modeCombo);
        form.add(new JLabel("Книжный разворот:"));
        form.add(twoColCheck);
        form.add(new JLabel("Скорость автопрокрутки:"));
        form.add(autoScrollSpeedSlider);
        form.add(new JLabel("Скорость чтения вслух (TTS слов/мин):"));
        form.add(ttsRateSlider);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        JButton applyBtn = ModernUiFactory.createPrimaryButton("Применить настройки", currentTheme);
        applyBtn.addActionListener(e -> {
            ThemePalette chosenTheme = (ThemePalette) themeCombo.getSelectedItem();
            if (chosenTheme != null) {
                s.setThemeId(chosenTheme.getId());
                changeTheme.accept(chosenTheme);
            }
            s.setFontFamily((String) fontCombo.getSelectedItem());
            s.setFontSize(fontSizeSlider.getValue());
            double[] lhValues = {1.2, 1.4, 1.6, 1.8, 2.0};
            s.setLineHeight(lhValues[Math.max(0, lineHeightCombo.getSelectedIndex())]);
            s.setTextAlignment(alignCombo.getSelectedIndex() == 0
                    ? ReaderSettings.TextAlignment.LEFT
                    : ReaderSettings.TextAlignment.JUSTIFY);
            s.setHorizontalMarginPx(marginSlider.getValue());
            s.setReadingMode(modeCombo.getSelectedIndex() == 0
                    ? ReaderSettings.ReadingMode.PAGED
                    : ReaderSettings.ReadingMode.SCROLL);
            s.setTwoColumnSpread(twoColCheck.isSelected());
            s.setAutoScrollSpeed(autoScrollSpeedSlider.getValue());
            s.setTtsRateWpm(ttsRateSlider.getValue());

            int scaledChars = Math.max(900, Math.min(4800, (int) (2400.0 * 19.0 / s.getFontSize())));
            if (s.isTwoColumnSpread()) scaledChars = (int) (scaledChars * 0.85);
            s.setCharsPerPage(scaledChars);

            try {
                save.run();
            } catch (java.io.UncheckedIOException | SecurityException ex) {
                UiPersistenceErrors.show(dialog, "Не удалось сохранить настройки чтения.", ex);
                return;
            }
            refresh.run();
            dialog.dispose();
        });

        btnRow.add(applyBtn);
        dialog.add(form, BorderLayout.CENTER);
        dialog.add(btnRow, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }
}
