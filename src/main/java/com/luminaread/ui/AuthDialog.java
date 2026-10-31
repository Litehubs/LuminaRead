package com.luminaread.ui;

import com.luminaread.service.LibAccountService;
import com.luminaread.service.LibAccountService.LibUser;
import com.luminaread.service.YandexAuthService;
import com.luminaread.service.YandexAuthService.YandexUser;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class AuthDialog {

    public static void show(Component parent, ThemePalette theme, Runnable onAuthChanged) {
        Window parentWin = SwingUtilities.getWindowAncestor(parent);
        JDialog dlg = new JDialog(
                parentWin instanceof Frame ? (Frame) parentWin : null,
                "Авторизация: Яндекс ID и Lib.social",
                Dialog.ModalityType.APPLICATION_MODAL
        );
        dlg.setLayout(new BorderLayout());
        dlg.setResizable(false);

        YandexAuthService yaService = YandexAuthService.getInstance();
        LibAccountService libService = LibAccountService.getInstance();

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.BOLD, 13));
        tabs.setBackground(new Color(28, 30, 40));
        tabs.setForeground(Color.WHITE);

        tabs.addTab("🟡 Яндекс ID", buildYandexTab(dlg, yaService, libService, onAuthChanged));

        tabs.addTab("📚 Lib.social (Закладки)", buildLibSocialTab(dlg, libService, onAuthChanged));

        dlg.add(tabs, BorderLayout.CENTER);
        dlg.pack();
        dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true);
    }

    private static JPanel buildYandexTab(JDialog dlg, YandexAuthService yaService, LibAccountService libService, Runnable onAuthChanged) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(new Color(28, 30, 40));
        panel.setBorder(new EmptyBorder(20, 24, 20, 24));

        YandexUser ya = yaService.getCurrentUser();
        if (ya != null && ya.isAuthenticated()) {

            JLabel title = new JLabel("Вы вошли через Яндекс ID", SwingConstants.CENTER);
            title.setFont(new Font("SansSerif", Font.BOLD, 16));
            title.setForeground(Color.WHITE);
            title.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel nameLbl = new JLabel("🟡 " + ya.getDisplayTitle() + (ya.defaultEmail.isEmpty() ? "" : " (" + ya.defaultEmail + ")"));
            nameLbl.setFont(new Font("SansSerif", Font.BOLD, 15));
            nameLbl.setForeground(new Color(255, 214, 0));
            nameLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel statusLbl = new JLabel("Аккаунт Яндекс подключен и активен");
            statusLbl.setFont(new Font("SansSerif", Font.PLAIN, 12));
            statusLbl.setForeground(new Color(160, 165, 180));
            statusLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

            JPanel linkBox = new JPanel();
            linkBox.setLayout(new BoxLayout(linkBox, BoxLayout.Y_AXIS));
            linkBox.setBackground(new Color(36, 39, 52));
            linkBox.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(55, 60, 80), 1, true),
                    new EmptyBorder(12, 14, 12, 14)
            ));
            linkBox.setAlignmentX(Component.CENTER_ALIGNMENT);
            linkBox.setMaximumSize(new Dimension(460, 150));

            JLabel linkTitle = new JLabel("Связка с закладками MangaLib / RanobeLib:");
            linkTitle.setFont(new Font("SansSerif", Font.BOLD, 12));
            linkTitle.setForeground(Color.WHITE);
            linkTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

            String linkedName = !ya.linkedLibUsername.isEmpty() ? ya.linkedLibUsername : (!ya.linkedLibUserId.isEmpty() ? ya.linkedLibUserId : "");
            JLabel linkDesc = new JLabel("<html><div style='width:380px; color:#b0b7c6; font-size:11px;'>"
                    + (linkedName.isEmpty()
                    ? "Укажите ваш никнейм или ID на Lib.social, чтобы закладки манги и ранобэ загружались автоматически под вашим Яндекс ID."
                    : "Текущий привязанный профиль: <b>" + linkedName + "</b>")
                    + "</div></html>");
            linkDesc.setAlignmentX(Component.LEFT_ALIGNMENT);

            JPanel inputRow = new JPanel(new BorderLayout(8, 0));
            inputRow.setOpaque(false);
            inputRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            JTextField linkField = new JTextField(linkedName);
            linkField.setFont(new Font("SansSerif", Font.PLAIN, 13));
            JButton saveLinkBtn = new JButton(linkedName.isEmpty() ? "Привязать" : "Обновить");
            saveLinkBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
            saveLinkBtn.setBackground(new Color(67, 160, 71));
            saveLinkBtn.setForeground(Color.WHITE);
            saveLinkBtn.setFocusPainted(false);
            saveLinkBtn.addActionListener(e -> {
                String val = linkField.getText().trim();
                if (!val.isEmpty()) {
                    yaService.linkLibSocial(val, val);
                    try {
                        libService.loginWithProfile(val);
                    } catch (Exception ignored) {}
                    JOptionPane.showMessageDialog(dlg, "Профиль Lib.social «" + val + "» успешно привязан к Яндекс ID!", "Привязка сохранена", JOptionPane.INFORMATION_MESSAGE);
                    if (onAuthChanged != null) onAuthChanged.run();
                }
            });
            inputRow.add(linkField, BorderLayout.CENTER);
            inputRow.add(saveLinkBtn, BorderLayout.EAST);

            linkBox.add(linkTitle);
            linkBox.add(Box.createVerticalStrut(4));
            linkBox.add(linkDesc);
            linkBox.add(Box.createVerticalStrut(8));
            linkBox.add(inputRow);

            JButton logoutBtn = new JButton("Выйти из Яндекс ID");
            logoutBtn.setBackground(new Color(198, 40, 40));
            logoutBtn.setForeground(Color.WHITE);
            logoutBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
            logoutBtn.setFocusPainted(false);
            logoutBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
            logoutBtn.addActionListener(e -> {
                yaService.logout();
                dlg.dispose();
                if (onAuthChanged != null) onAuthChanged.run();
            });

            panel.add(title);
            panel.add(Box.createVerticalStrut(10));
            panel.add(nameLbl);
            panel.add(Box.createVerticalStrut(4));
            panel.add(statusLbl);
            panel.add(Box.createVerticalStrut(16));
            panel.add(linkBox);
            panel.add(Box.createVerticalStrut(20));
            panel.add(logoutBtn);
        } else {

            JLabel title = new JLabel("Вход через Яндекс ID");
            title.setFont(new Font("SansSerif", Font.BOLD, 16));
            title.setForeground(Color.WHITE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel hint = new JLabel("<html><div style='width:420px; color:#b0b7c6; font-size:12px; line-height:1.4;'>"
                    + "Авторизуйтесь через ваш Яндекс Аккаунт. Вы можете нажать кнопку ниже для открытия входа в браузере, "
                    + "либо указать ваш логин / OAuth токен Яндекс."
                    + "</div></html>");
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);

            JButton browserOAuthBtn = new JButton("🟡 Войти через Яндекс ID (открыть в браузере)");
            browserOAuthBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
            browserOAuthBtn.setBackground(new Color(255, 214, 0));
            browserOAuthBtn.setForeground(new Color(20, 20, 20));
            browserOAuthBtn.setFocusPainted(false);
            browserOAuthBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
            browserOAuthBtn.setMaximumSize(new Dimension(440, 38));

            JLabel tokenLbl = new JLabel("OAuth Токен Яндекс (из браузера):");
            tokenLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
            tokenLbl.setForeground(Color.WHITE);
            tokenLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

            JTextField tokenField = new JTextField(24);
            tokenField.setFont(new Font("SansSerif", Font.PLAIN, 13));
            tokenField.setMaximumSize(new Dimension(440, 32));
            tokenField.setAlignmentX(Component.LEFT_ALIGNMENT);
            tokenField.setToolTipText("Вставьте OAuth токен Яндекс, полученный в браузере");

            JLabel altLbl = new JLabel("Либо ваш логин Яндекс или Email:");
            altLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
            altLbl.setForeground(Color.WHITE);
            altLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

            JTextField loginField = new JTextField(24);
            loginField.setFont(new Font("SansSerif", Font.PLAIN, 13));
            loginField.setMaximumSize(new Dimension(440, 32));
            loginField.setAlignmentX(Component.LEFT_ALIGNMENT);
            loginField.setToolTipText("Например: my_login или my_login@yandex.ru");

            JLabel statusMsg = new JLabel("");
            statusMsg.setForeground(new Color(255, 138, 101));
            statusMsg.setFont(new Font("SansSerif", Font.PLAIN, 12));
            statusMsg.setAlignmentX(Component.LEFT_ALIGNMENT);

            browserOAuthBtn.addActionListener(e -> {
                boolean opened = yaService.openYandexOAuthInBrowser();
                if (opened) {
                    statusMsg.setText("✓ Страница Яндекса открыта в браузере. Скопируйте токен и вставьте выше.");
                    statusMsg.setForeground(new Color(129, 199, 132));
                } else {
                    statusMsg.setText("Не удалось открыть браузер. Введите логин или токен вручную.");
                    statusMsg.setForeground(new Color(255, 138, 101));
                }
            });

            JButton submitBtn = new JButton("✓ Подключить Яндекс Аккаунт");
            submitBtn.setBackground(new Color(67, 160, 71));
            submitBtn.setForeground(Color.WHITE);
            submitBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
            submitBtn.setFocusPainted(false);
            submitBtn.setAlignmentX(Component.LEFT_ALIGNMENT);

            submitBtn.addActionListener(e -> {
                String token = tokenField.getText().trim();
                String login = loginField.getText().trim();

                if (token.isEmpty() && login.isEmpty()) {
                    statusMsg.setText("Укажите OAuth токен или логин Яндекс.");
                    statusMsg.setForeground(new Color(255, 138, 101));
                    return;
                }

                statusMsg.setText("⏳ Проверка авторизации Яндекс ID...");
                statusMsg.setForeground(new Color(180, 185, 200));
                submitBtn.setEnabled(false);

                new SwingWorker<YandexUser, Void>() {
                    @Override
                    protected YandexUser doInBackground() throws Exception {
                        if (!token.isEmpty()) {
                            return yaService.loginWithOAuthToken(token);
                        } else {
                            return yaService.loginWithLoginOrEmail(login);
                        }
                    }

                    @Override
                    protected void done() {
                        submitBtn.setEnabled(true);
                        try {
                            YandexUser logged = get();

                            try {
                                libService.loginWithProfile(logged.login);
                            } catch (Exception ignored) {}

                            dlg.dispose();
                            if (onAuthChanged != null) onAuthChanged.run();
                            JOptionPane.showMessageDialog(dlg,
                                    "Вы успешно вошли через Яндекс ID: " + logged.getDisplayTitle(),
                                    "Вход выполнен", JOptionPane.INFORMATION_MESSAGE);
                        } catch (Exception ex) {
                            String err = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                            statusMsg.setText("Ошибка: " + err);
                            statusMsg.setForeground(new Color(255, 138, 101));
                        }
                    }
                }.execute();
            });

            panel.add(title);
            panel.add(Box.createVerticalStrut(6));
            panel.add(hint);
            panel.add(Box.createVerticalStrut(14));
            panel.add(browserOAuthBtn);
            panel.add(Box.createVerticalStrut(14));
            panel.add(tokenLbl);
            panel.add(Box.createVerticalStrut(4));
            panel.add(tokenField);
            panel.add(Box.createVerticalStrut(10));
            panel.add(altLbl);
            panel.add(Box.createVerticalStrut(4));
            panel.add(loginField);
            panel.add(Box.createVerticalStrut(10));
            panel.add(statusMsg);
            panel.add(Box.createVerticalStrut(12));
            panel.add(submitBtn);
        }

        return panel;
    }

    private static JPanel buildLibSocialTab(JDialog dlg, LibAccountService libService, Runnable onAuthChanged) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(new Color(28, 30, 40));
        panel.setBorder(new EmptyBorder(20, 24, 20, 24));

        LibUser u = libService.getCurrentUser();
        if (u != null && u.isAuthenticated()) {
            JLabel title = new JLabel("Вы вошли в аккаунт Lib.social", SwingConstants.CENTER);
            title.setFont(new Font("SansSerif", Font.BOLD, 16));
            title.setForeground(Color.WHITE);
            title.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel nameLbl = new JLabel("Пользователь: " + u.username + " (ID: " + u.id + ")");
            nameLbl.setFont(new Font("SansSerif", Font.BOLD, 14));
            nameLbl.setForeground(new Color(129, 199, 132));
            nameLbl.setAlignmentX(Component.CENTER_ALIGNMENT);

            JButton logoutBtn = new JButton("Отключить профиль Lib.social");
            logoutBtn.setBackground(new Color(198, 40, 40));
            logoutBtn.setForeground(Color.WHITE);
            logoutBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
            logoutBtn.setFocusPainted(false);
            logoutBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
            logoutBtn.addActionListener(e -> {
                libService.logout();
                dlg.dispose();
                if (onAuthChanged != null) onAuthChanged.run();
            });

            panel.add(title);
            panel.add(Box.createVerticalStrut(10));
            panel.add(nameLbl);
            panel.add(Box.createVerticalStrut(20));
            panel.add(logoutBtn);
        } else {
            JLabel title = new JLabel("Подключение профиля Lib.social");
            title.setFont(new Font("SansSerif", Font.BOLD, 16));
            title.setForeground(Color.WHITE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel hint = new JLabel("<html><div style='width:420px; color:#b0b7c6; font-size:12px; line-height:1.4;'>"
                    + "Используется для синхронизации закладок <b>RanobeLib</b> и <b>MangaLib</b>.<br/>"
                    + "Укажите ваш <b>ID, никнейм или ссылку на профиль</b>."
                    + "</div></html>");
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel inputLbl = new JLabel("ID пользователя, никнейм или ссылка на профиль:");
            inputLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
            inputLbl.setForeground(Color.WHITE);
            inputLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

            JTextField inputField = new JTextField(24);
            inputField.setFont(new Font("SansSerif", Font.PLAIN, 14));
            inputField.setMaximumSize(new Dimension(440, 32));
            inputField.setAlignmentX(Component.LEFT_ALIGNMENT);
            inputField.setToolTipText("Например: 10300544 или UltraBebrik6432");

            JLabel tokenLbl = new JLabel("Либо Bearer токен (опционально):");
            tokenLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
            tokenLbl.setForeground(Color.WHITE);
            tokenLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

            JTextField tokenField = new JTextField(24);
            tokenField.setFont(new Font("SansSerif", Font.PLAIN, 13));
            tokenField.setMaximumSize(new Dimension(440, 32));
            tokenField.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel statusMsg = new JLabel("");
            statusMsg.setForeground(new Color(255, 138, 101));
            statusMsg.setFont(new Font("SansSerif", Font.PLAIN, 12));
            statusMsg.setAlignmentX(Component.LEFT_ALIGNMENT);

            JButton loginBtn = new JButton("✓ Подключить профиль Lib.social");
            loginBtn.setBackground(new Color(67, 160, 71));
            loginBtn.setForeground(Color.WHITE);
            loginBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
            loginBtn.setFocusPainted(false);
            loginBtn.setAlignmentX(Component.LEFT_ALIGNMENT);

            loginBtn.addActionListener(e -> {
                String token = tokenField.getText().trim();
                String input = inputField.getText().trim();
                if (token.isEmpty() && input.isEmpty()) {
                    statusMsg.setText("Укажите никнейм, ID или токен.");
                    return;
                }
                statusMsg.setText("⏳ Проверка подключения...");
                loginBtn.setEnabled(false);

                new SwingWorker<LibUser, Void>() {
                    @Override
                    protected LibUser doInBackground() throws Exception {
                        if (!token.isEmpty()) {
                            return libService.loginWithToken(token);
                        } else {
                            return libService.loginWithProfile(input);
                        }
                    }

                    @Override
                    protected void done() {
                        loginBtn.setEnabled(true);
                        try {
                            LibUser logged = get();
                            dlg.dispose();
                            if (onAuthChanged != null) onAuthChanged.run();
                            JOptionPane.showMessageDialog(dlg,
                                    "Профиль «" + logged.username + "» успешно подключён!",
                                    "Вход выполнен", JOptionPane.INFORMATION_MESSAGE);
                        } catch (Exception ex) {
                            String err = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                            statusMsg.setText("Ошибка: " + err);
                        }
                    }
                }.execute();
            });

            panel.add(title);
            panel.add(Box.createVerticalStrut(6));
            panel.add(hint);
            panel.add(Box.createVerticalStrut(14));
            panel.add(inputLbl);
            panel.add(Box.createVerticalStrut(4));
            panel.add(inputField);
            panel.add(Box.createVerticalStrut(10));
            panel.add(tokenLbl);
            panel.add(Box.createVerticalStrut(4));
            panel.add(tokenField);
            panel.add(Box.createVerticalStrut(10));
            panel.add(statusMsg);
            panel.add(Box.createVerticalStrut(12));
            panel.add(loginBtn);
        }

        return panel;
    }
}
