package com.luminaread.ui;

import com.luminaread.model.Book;
import com.luminaread.model.ReaderSettings;
import com.luminaread.service.ITtsService;
import com.luminaread.service.LibraryStorageService;
import com.luminaread.service.OnlineCatalogService;
import com.luminaread.service.TtsService;
import com.luminaread.service.YandexAuthService;
import com.luminaread.service.YandexAuthService.YandexUser;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class SettingsPanel extends JPanel {
    private final LibraryStorageService storageService;
    private final ITtsService ttsService;
    private final Consumer<ThemePalette> onThemeChangedGlobal;
    private final Runnable onSettingsUpdated;

    private final JPanel mainContainer;
    private final JScrollPane mainScroll;
    private final JPanel themeButtonsGrid;
    private final DownloadsSection downloads;

    private ThemePalette currentTheme;

    public SettingsPanel(LibraryStorageService storageService,
                         OnlineCatalogService catalogService,
                         TtsService ttsService,
                         Consumer<ThemePalette> onThemeChangedGlobal,
                         Runnable onSettingsUpdated,
                         Consumer<Book> onOpenDownloadedBook) {
        this(storageService, catalogService, (ITtsService) ttsService, onThemeChangedGlobal, onSettingsUpdated, onOpenDownloadedBook);
    }

    public SettingsPanel(LibraryStorageService storageService,
                         OnlineCatalogService catalogService,
                         ITtsService ttsService,
                         Consumer<ThemePalette> onThemeChangedGlobal,
                         Runnable onSettingsUpdated,
                         Consumer<Book> onOpenDownloadedBook) {
        this.storageService = storageService;
        this.ttsService = ttsService;
        this.onThemeChangedGlobal = onThemeChangedGlobal;
        this.onSettingsUpdated = onSettingsUpdated;
        this.downloads = new DownloadsSection(catalogService, storageService, onOpenDownloadedBook);
        this.currentTheme = ThemePalette.byId(storageService.getSettings().getThemeId());

        setLayout(new BorderLayout(0, 0));

        mainContainer = new JPanel();
        mainContainer.setLayout(new BoxLayout(mainContainer, BoxLayout.Y_AXIS));
        mainContainer.setBorder(BorderFactory.createEmptyBorder(18, 24, 28, 24));

        themeButtonsGrid = new JPanel(new GridLayout(2, 3, 10, 10));
        themeButtonsGrid.setOpaque(false);

        mainScroll = SmoothScrollUtil.enhanceAndReturn(new JScrollPane(mainContainer));
        mainScroll.setBorder(BorderFactory.createEmptyBorder());
        mainScroll.getVerticalScrollBar().setUnitIncrement(42);
        add(mainScroll, BorderLayout.CENTER);

        applyTheme(currentTheme);
    }

    private void buildFullSettingsSections() {
        mainContainer.removeAll();
        ReaderSettings s = storageService.getSettings();

        JLabel pageTitle = new JLabel("Настройки и загрузки");
        pageTitle.setFont(ModernUiFactory.uiFont(Font.BOLD, 19));
        pageTitle.setForeground(currentTheme.getTextPrimary());
        pageTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainContainer.add(pageTitle);
        mainContainer.add(Box.createVerticalStrut(14));

        downloads.applyTheme(currentTheme);
        mainContainer.add(downloads);
        mainContainer.add(Box.createVerticalStrut(16));

        JPanel themeSection = createSectionCard("Тема оформления и защита зрения");

        JLabel themeHint = new JLabel("Выберите тему оформления:");
        themeHint.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        themeHint.setForeground(currentTheme.getTextSecondary());
        themeHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        themeSection.add(themeHint);
        themeSection.add(Box.createVerticalStrut(8));

        rebuildThemeButtons();
        themeButtonsGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        themeButtonsGrid.setMaximumSize(new Dimension(920, 96));
        themeSection.add(themeButtonsGrid);
        JCheckBox reduceMotion = new JCheckBox("Отключить анимации переходов", s.isReduceMotion());
        reduceMotion.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        reduceMotion.setForeground(currentTheme.getTextPrimary());
        reduceMotion.setOpaque(false);
        reduceMotion.setAlignmentX(Component.LEFT_ALIGNMENT);
        reduceMotion.addActionListener(e -> {
            s.setReduceMotion(reduceMotion.isSelected());
            saveSettings();
            onSettingsUpdated.run();
        });
        themeSection.add(Box.createVerticalStrut(8));
        themeSection.add(reduceMotion);
        themeSection.add(Box.createVerticalStrut(12));

        JPanel brightnessRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        brightnessRow.setOpaque(false);
        brightnessRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        int currentBrightnessPct = (int) Math.round((1.0 - s.getDimmerOpacity()) * 100);
        JLabel brightValLbl = new JLabel("Яркость страницы: " + currentBrightnessPct + "%");
        brightValLbl.setFont(ModernUiFactory.uiFont(Font.BOLD, 13));
        brightValLbl.setForeground(currentTheme.getTextPrimary());

        JSlider brightSlider = new JSlider(30, 100, Math.max(30, Math.min(100, currentBrightnessPct)));
        brightSlider.setPreferredSize(new Dimension(220, 26));
        brightSlider.setOpaque(false);
        brightSlider.addChangeListener(e -> {
            int val = brightSlider.getValue();
            brightValLbl.setText("Яркость страницы: " + val + "%");
            s.setDimmerOpacity((100 - val) / 100.0);
            if (!brightSlider.getValueIsAdjusting()) saveSettings();
            onSettingsUpdated.run();
        });

        JCheckBox warmFilterCheck = new JCheckBox("Тёплый светофильтр", s.isWarmEyeCareFilter());
        warmFilterCheck.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        warmFilterCheck.setForeground(currentTheme.getTextPrimary());
        warmFilterCheck.setOpaque(false);
        warmFilterCheck.addActionListener(e -> {
            s.setWarmEyeCareFilter(warmFilterCheck.isSelected());
            saveSettings();
            onSettingsUpdated.run();
        });

        brightnessRow.add(brightValLbl);
        brightnessRow.add(brightSlider);
        brightnessRow.add(warmFilterCheck);
        themeSection.add(brightnessRow);

        mainContainer.add(themeSection);
        mainContainer.add(Box.createVerticalStrut(16));

        JPanel typoSection = createSectionCard("Шрифт и оформление страницы");

        JPanel typoGrid = new JPanel(new GridLayout(0, 2, 16, 10));
        typoGrid.setOpaque(false);
        typoGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        typoGrid.setMaximumSize(new Dimension(920, 320));

        JComboBox<String> fontCombo = new JComboBox<>(new String[]{
                "Georgia", "Palatino", "Times New Roman", "Verdana", "Trebuchet MS", "Tahoma", "Monospaced"
        });
        fontCombo.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        fontCombo.setSelectedItem(s.getFontFamily());
        fontCombo.addActionListener(e -> {
            s.setFontFamily((String) fontCombo.getSelectedItem());
            saveSettings();
            onSettingsUpdated.run();
        });

        JLabel fontSizeLbl = createSettingLabel("Размер шрифта книги: " + s.getFontSize() + " пт");
        JSlider fontSizeSlider = new JSlider(12, 36, s.getFontSize());
        fontSizeSlider.setOpaque(false);
        fontSizeSlider.addChangeListener(e -> {
            s.setFontSize(fontSizeSlider.getValue());
            fontSizeLbl.setText("Размер шрифта книги: " + s.getFontSize() + " пт");
            int scaledChars = Math.max(900, Math.min(4800, (int) (2400.0 * 19.0 / s.getFontSize())));
            if (s.isTwoColumnSpread()) scaledChars = (int) (scaledChars * 0.85);
            s.setCharsPerPage(scaledChars);
            saveSettings();
            onSettingsUpdated.run();
        });

        JComboBox<String> lineHeightCombo = new JComboBox<>(new String[]{
                "1.2 — плотный", "1.4 — стандартный", "1.6 — комфортный", "1.8 — свободный", "2.0 — двойной"
        });
        lineHeightCombo.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        int lhIndex = (int) Math.round((s.getLineHeight() - 1.2) / 0.2);
        lineHeightCombo.setSelectedIndex(Math.max(0, Math.min(4, lhIndex)));
        lineHeightCombo.addActionListener(e -> {
            double[] lhValues = {1.2, 1.4, 1.6, 1.8, 2.0};
            s.setLineHeight(lhValues[Math.max(0, lineHeightCombo.getSelectedIndex())]);
            saveSettings();
            onSettingsUpdated.run();
        });

        JLabel paraSpacingLbl = createSettingLabel("Отступ между абзацами: " + s.getParagraphSpacingPx() + " px");
        JSlider paraSpacingSlider = new JSlider(0, 32, s.getParagraphSpacingPx());
        paraSpacingSlider.setOpaque(false);
        paraSpacingSlider.addChangeListener(e -> {
            s.setParagraphSpacingPx(paraSpacingSlider.getValue());
            paraSpacingLbl.setText("Отступ между абзацами: " + s.getParagraphSpacingPx() + " px");
            saveSettings();
            onSettingsUpdated.run();
        });

        JLabel marginLbl = createSettingLabel("Боковые поля страницы: " + s.getHorizontalMarginPx() + " px");
        JSlider marginSlider = new JSlider(20, 180, s.getHorizontalMarginPx());
        marginSlider.setOpaque(false);
        marginSlider.addChangeListener(e -> {
            s.setHorizontalMarginPx(marginSlider.getValue());
            marginLbl.setText("Боковые поля страницы: " + s.getHorizontalMarginPx() + " px");
            saveSettings();
            onSettingsUpdated.run();
        });

        JComboBox<String> alignCombo = new JComboBox<>(new String[]{
                "По левому краю", "По ширине"
        });
        alignCombo.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        alignCombo.setSelectedIndex(s.getTextAlignment() == ReaderSettings.TextAlignment.LEFT ? 0 : 1);
        alignCombo.addActionListener(e -> {
            s.setTextAlignment(alignCombo.getSelectedIndex() == 0
                    ? ReaderSettings.TextAlignment.LEFT
                    : ReaderSettings.TextAlignment.JUSTIFY);
            saveSettings();
            onSettingsUpdated.run();
        });

        JComboBox<String> modeCombo = new JComboBox<>(new String[]{
                "Постранично", "Непрерывная прокрутка"
        });
        modeCombo.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        modeCombo.setSelectedIndex(s.getReadingMode() == ReaderSettings.ReadingMode.PAGED ? 0 : 1);
        modeCombo.addActionListener(e -> {
            s.setReadingMode(modeCombo.getSelectedIndex() == 0
                    ? ReaderSettings.ReadingMode.PAGED
                    : ReaderSettings.ReadingMode.SCROLL);
            saveSettings();
            onSettingsUpdated.run();
        });

        JCheckBox twoColCheck = new JCheckBox("Две страницы рядом", s.isTwoColumnSpread());
        twoColCheck.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        twoColCheck.setForeground(currentTheme.getTextPrimary());
        twoColCheck.setOpaque(false);
        twoColCheck.addActionListener(e -> {
            s.setTwoColumnSpread(twoColCheck.isSelected());
            saveSettings();
            onSettingsUpdated.run();
        });

        typoGrid.add(createSettingLabel("Гарнитура шрифта текста книги:"));
        typoGrid.add(fontCombo);
        typoGrid.add(fontSizeLbl);
        typoGrid.add(fontSizeSlider);
        typoGrid.add(createSettingLabel("Межстрочный интервал:"));
        typoGrid.add(lineHeightCombo);
        typoGrid.add(paraSpacingLbl);
        typoGrid.add(paraSpacingSlider);
        typoGrid.add(marginLbl);
        typoGrid.add(marginSlider);
        typoGrid.add(createSettingLabel("Выравнивание абзацев:"));
        typoGrid.add(alignCombo);
        typoGrid.add(createSettingLabel("Режим перелистывания:"));
        typoGrid.add(modeCombo);
        typoGrid.add(createSettingLabel("Книжный разворот:"));
        typoGrid.add(twoColCheck);

        typoSection.add(typoGrid);
        mainContainer.add(typoSection);
        mainContainer.add(Box.createVerticalStrut(16));

        JPanel controlSection = createSectionCard("Управление и чтение вслух");

        JPanel controlGrid = new JPanel(new GridLayout(0, 2, 16, 10));
        controlGrid.setOpaque(false);
        controlGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        controlGrid.setMaximumSize(new Dimension(920, 200));

        JLabel autoScrollLbl = createSettingLabel("Скорость плавной автопрокрутки: " + s.getAutoScrollSpeed() + " px/с");
        JSlider autoScrollSlider = new JSlider(10, 180, s.getAutoScrollSpeed());
        autoScrollSlider.setOpaque(false);
        autoScrollSlider.addChangeListener(e -> {
            s.setAutoScrollSpeed(autoScrollSlider.getValue());
            autoScrollLbl.setText("Скорость плавной автопрокрутки: " + s.getAutoScrollSpeed() + " px/с");
            saveSettings();
        });

        JLabel ttsRateLbl = createSettingLabel("Скорость чтения вслух: " + s.getTtsRateWpm() + " слов/мин");
        JSlider ttsRateSlider = new JSlider(100, 310, s.getTtsRateWpm());
        ttsRateSlider.setOpaque(false);
        ttsRateSlider.addChangeListener(e -> {
            s.setTtsRateWpm(ttsRateSlider.getValue());
            ttsRateLbl.setText("Скорость чтения вслух: " + s.getTtsRateWpm() + " слов/мин");
            saveSettings();
        });

        JCheckBox tapZonesCheck = new JCheckBox("Зоны нажатия для перелистывания и режима чтения", s.isTapZonesEnabled());
        tapZonesCheck.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        tapZonesCheck.setForeground(currentTheme.getTextPrimary());
        tapZonesCheck.setOpaque(false);
        tapZonesCheck.addActionListener(e -> {
            s.setTapZonesEnabled(tapZonesCheck.isSelected());
            saveSettings();
        });

        JCheckBox statusZenCheck = new JCheckBox("Показывать нижнюю строку прогресса и часы в полноэкранном Дзен-режиме", s.isShowStatusBarInReader());
        statusZenCheck.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        statusZenCheck.setForeground(currentTheme.getTextPrimary());
        statusZenCheck.setOpaque(false);
        statusZenCheck.addActionListener(e -> {
            s.setShowStatusBarInReader(statusZenCheck.isSelected());
            saveSettings();
            onSettingsUpdated.run();
        });

        controlGrid.add(autoScrollLbl);
        controlGrid.add(autoScrollSlider);
        controlGrid.add(ttsRateLbl);
        controlGrid.add(ttsRateSlider);
        controlGrid.add(tapZonesCheck);
        controlGrid.add(statusZenCheck);

        controlSection.add(controlGrid);
        controlSection.add(Box.createVerticalStrut(12));

        JPanel sysButtonsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        sysButtonsRow.setOpaque(false);
        sysButtonsRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton testTtsBtn = ModernUiFactory.createFlatButton("Проверить голос", currentTheme);
        testTtsBtn.addActionListener(e -> {
            if (ttsService.isSpeaking()) {
                ttsService.stop();
            } else {
                ttsService.speakText("Проверка голосового чтения LuminaRead. Приятного чтения!",
                        s.getTtsRateWpm(), 0, null);
            }
        });

        JButton clearCacheBtn = ModernUiFactory.createFlatButton("Очистить кэш картинок", currentTheme);
        clearCacheBtn.addActionListener(e -> clearImageCache());

        JButton resetDefaultsBtn = ModernUiFactory.createFlatButton("Сбросить настройки по умолчанию", currentTheme);
        resetDefaultsBtn.addActionListener(e -> resetSettingsToDefaults());

        sysButtonsRow.add(testTtsBtn);
        sysButtonsRow.add(clearCacheBtn);
        sysButtonsRow.add(resetDefaultsBtn);
        controlSection.add(sysButtonsRow);

        mainContainer.add(controlSection);

        mainContainer.add(Box.createVerticalStrut(16));
        JPanel accountsSection = createSectionCard("Аккаунт Яндекс ID");

        YandexAuthService yaService = YandexAuthService.getInstance();

        JPanel accountInfoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 6));
        accountInfoRow.setOpaque(false);
        accountInfoRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        YandexUser ya = yaService.getCurrentUser();
        String yaStatus = (ya != null && ya.isAuthenticated()) ? ("🟡 " + ya.getDisplayTitle()) : "🟡 Не подключён";
        JLabel yaLbl = new JLabel("Яндекс ID: " + yaStatus);
        yaLbl.setFont(ModernUiFactory.uiFont(Font.BOLD, 13));
        yaLbl.setForeground(currentTheme.getTextPrimary());

        JButton manageAuthBtn = ModernUiFactory.createPrimaryButton("Управление аккаунтом", currentTheme);
        manageAuthBtn.addActionListener(e -> AuthDialog.show(this, currentTheme, () -> {
            YandexUser y = yaService.getCurrentUser();
            yaLbl.setText("Яндекс ID: " + ((y != null && y.isAuthenticated()) ? ("🟡 " + y.getDisplayTitle()) : "🟡 Не подключён"));
        }));

        accountInfoRow.add(yaLbl);
        accountInfoRow.add(manageAuthBtn);

        accountsSection.add(accountInfoRow);
        mainContainer.add(accountsSection);

        refreshDownloadsSection();
    }

    private void rebuildThemeButtons() {
        themeButtonsGrid.removeAll();
        String activeId = storageService.getSettings().getThemeId();
        for (ThemePalette palette : ThemePalette.PRESETS.values()) {
            boolean isCurrent = palette.getId().equals(activeId);
            String label = isCurrent ? ("• " + palette.getDisplayName()) : palette.getDisplayName();
            JButton btn = isCurrent
                    ? ModernUiFactory.createPrimaryButton(label, currentTheme)
                    : ModernUiFactory.createFlatButton(label, currentTheme);
            btn.addActionListener(e -> {
                storageService.getSettings().setThemeId(palette.getId());
                saveSettings();
                onThemeChangedGlobal.accept(palette);
            });
            themeButtonsGrid.add(btn);
        }
        themeButtonsGrid.revalidate();
        themeButtonsGrid.repaint();
    }

    public void refreshDownloadsSection() {
        downloads.refresh();
    }

    void disposeResources() {
        downloads.disposeResources();
    }

    private void clearImageCache() {
        try {
            Path cacheDir = Path.of(System.getProperty("user.home"), ".luminaread", "cache");
            if (Files.exists(cacheDir)) {
                try (Stream<Path> walk = Files.walk(cacheDir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); }
                        catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
                    });
                }
            }
            JOptionPane.showMessageDialog(this, "Кэш временных изображений успешно очищен.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Ошибка очистки кэша: " + ex.getMessage());
        }
    }

    private void resetSettingsToDefaults() {
        ReaderSettings s = storageService.getSettings();
        s.setThemeId("DAY_PAPER");
        s.setFontFamily("Georgia");
        s.setFontSize(19);
        s.setLineHeight(1.6);
        s.setParagraphSpacingPx(12);
        s.setHorizontalMarginPx(64);
        s.setVerticalMarginPx(36);
        s.setTextAlignment(ReaderSettings.TextAlignment.LEFT);
        s.setReadingMode(ReaderSettings.ReadingMode.PAGED);
        s.setTwoColumnSpread(false);
        s.setDimmerOpacity(0.0);
        s.setWarmEyeCareFilter(false);
        s.setReduceMotion(false);
        s.setAutoScrollSpeed(35);
        s.setTtsRateWpm(175);
        saveSettings();
        onThemeChangedGlobal.accept(ThemePalette.byId("DAY_PAPER"));
        onSettingsUpdated.run();
    }

    private void saveSettings() {
        UiPersistenceErrors.save(this, "Настройки применены, но сохранить их полностью не удалось.",
                storageService::saveState);
    }

    private JPanel createSectionCard(String sectionTitle) {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(currentTheme.getCardBackground());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(currentTheme.getBorder(), 1, true),
                BorderFactory.createEmptyBorder(16, 18, 16, 18)
        ));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel header = new JLabel(sectionTitle);
        header.setFont(ModernUiFactory.uiFont(Font.BOLD, 15));
        header.setForeground(currentTheme.getAccent());
        header.setAlignmentX(Component.LEFT_ALIGNMENT);

        card.add(header);
        card.add(Box.createVerticalStrut(10));
        return card;
    }

    private JLabel createSettingLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(ModernUiFactory.uiFont(Font.PLAIN, 13));
        lbl.setForeground(currentTheme.getTextPrimary());
        return lbl;
    }

    public void applyTheme(ThemePalette theme) {
        this.currentTheme = theme;
        setBackground(theme.getBackground());
        mainContainer.setBackground(theme.getBackground());
        if (mainScroll != null && mainScroll.getViewport() != null) {
            mainScroll.getViewport().setBackground(theme.getBackground());
        }
        buildFullSettingsSections();
        revalidate();
        repaint();
    }
}
