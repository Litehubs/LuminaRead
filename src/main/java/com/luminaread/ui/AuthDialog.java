package com.luminaread.ui;

import com.luminaread.service.YandexAuthService;
import com.luminaread.service.YandexAuthService.BrowserLogin;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;

final class AuthDialog {
    private AuthDialog() {}

    static void show(Component parent, ThemePalette theme) {
        var dialog = new JDialog(SwingUtilities.getWindowAncestor(parent), "Яндекс ID",
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setResizable(false);

        var service = YandexAuthService.getInstance();
        var account = service.account();
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(theme.getCardBackground());
        panel.setBorder(new EmptyBorder(20, 24, 20, 24));
        panel.setPreferredSize(new Dimension(455, account.profile() != null ? 240 : 200));
        var status = new JTextArea(2, 36);
        status.setOpaque(false);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setFont(ModernUiFactory.uiFont(Font.PLAIN, 12));
        status.setForeground(theme.getTextPrimary());

        JLabel title = new JLabel(switch (account.status()) {
            case VERIFIED -> "Вход через Яндекс ID подтверждён";
            case SAVED_PROFILE -> "Сохранённый профиль Яндекс ID";
            case SIGNED_OUT -> "Вход через Яндекс ID";
        });
        title.setFont(ModernUiFactory.uiFont(Font.BOLD, 16));
        title.setForeground(theme.getTextPrimary());
        panel.add(title);
        panel.add(Box.createVerticalStrut(8));
        if (account.profile() != null) {
            JLabel profile = new JLabel("Аккаунт: " + account.profile().getDisplayTitle());
            profile.setToolTipText(profile.getText());
            profile.setForeground(theme.getTextPrimary());
            panel.add(profile);
            if (!account.profile().defaultEmail().isBlank()) {
                JLabel email = new JLabel("Почта: " + account.profile().defaultEmail());
                email.setForeground(theme.getTextSecondary());
                panel.add(email);
            }
            panel.add(Box.createVerticalStrut(6));
        }

        if (account.status() != YandexAuthService.Status.VERIFIED) {
            JLabel hint = new JLabel("Подтвердите вход в браузере и вернитесь в LuminaRead.");
            hint.setForeground(theme.getTextSecondary());
            panel.add(hint);
            panel.add(Box.createVerticalStrut(12));
            addLogin(panel, dialog, theme, service, status, account.status() == YandexAuthService.Status.SAVED_PROFILE);
        }

        if (account.profile() != null) {
            panel.add(Box.createVerticalStrut(8));
            JButton logout = ModernUiFactory.createFlatButton(
                    account.status() == YandexAuthService.Status.VERIFIED ? "Выйти" : "Удалить сохранённый профиль", theme);
            var request = new LatestSwingRequest<Void>();
            logout.addActionListener(event -> {
                logout.setEnabled(false);
                request.start(() -> {
                    service.logout();
                    return null;
                }, ignored -> dialog.dispose(), error -> {
                    logout.setEnabled(true);
                    status.setText("Не удалось удалить сохранённый профиль.");
                });
            });
            panel.add(logout);
        }

        panel.add(Box.createVerticalStrut(8));
        panel.add(status);
        dialog.add(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static void addLogin(JPanel panel, JDialog dialog, ThemePalette theme, YandexAuthService service,
                                 JTextArea status, boolean reconnect) {
        JButton login = ModernUiFactory.createPrimaryButton(
                reconnect ? "Подтвердить вход в браузере" : "Войти в браузере", theme);
        var request = new LatestSwingRequest<YandexAuthService.YandexUser>();
        login.addActionListener(event -> {
            BrowserLogin attempt = service.prepareBrowserLogin();
            login.setEnabled(false);
            status.setText("Ожидание входа в браузере…");
            var close = new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { cancel(); }
                @Override public void windowClosed(WindowEvent event) { cancel(); }

                private void cancel() {
                    request.cancel();
                    attempt.close();
                    dialog.removeWindowListener(this);
                }
            };
            dialog.addWindowListener(close);
            request.start(attempt::complete, user -> dialog.dispose(), error -> {
                dialog.removeWindowListener(close);
                login.setEnabled(true);
                status.setText(error instanceof IOException ? error.getMessage() : "Не удалось завершить вход.");
            });
        });
        panel.add(login);
    }
}
