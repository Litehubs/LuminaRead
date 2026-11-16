package com.luminaread.ui;

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
                "Вход через Яндекс ID",
                Dialog.ModalityType.APPLICATION_MODAL
        );
        dlg.setLayout(new BorderLayout());
        dlg.setResizable(false);
        dlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        YandexAuthService yaService = YandexAuthService.getInstance();
        dlg.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent event) {
                yaService.cancelBrowserAuthorization();
            }
            @Override public void windowClosed(java.awt.event.WindowEvent event) {
                yaService.cancelBrowserAuthorization();
            }
        });

        dlg.add(buildYandexTab(dlg, yaService, onAuthChanged), BorderLayout.CENTER);
        dlg.pack();
        dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true);
    }

    private static JPanel buildYandexTab(JDialog dlg, YandexAuthService yaService, Runnable onAuthChanged) {
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

            JButton logoutBtn = new JButton("Выйти из Яндекс ID");
            logoutBtn.setBackground(new Color(198, 40, 40));
            logoutBtn.setForeground(Color.WHITE);
            logoutBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
            logoutBtn.setFocusPainted(false);
            logoutBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
            logoutBtn.addActionListener(e -> {
                try {
                    yaService.logout();
                } catch (java.io.UncheckedIOException ex) {
                    JOptionPane.showMessageDialog(dlg,
                            "Вы вышли из аккаунта, но не удалось удалить сохранённые данные профиля.",
                            "Ошибка удаления данных", JOptionPane.ERROR_MESSAGE);
                } finally {
                    dlg.dispose();
                    if (onAuthChanged != null) onAuthChanged.run();
                }
            });

            panel.add(title);
            panel.add(Box.createVerticalStrut(10));
            panel.add(nameLbl);
            panel.add(Box.createVerticalStrut(4));
            panel.add(statusLbl);
            panel.add(Box.createVerticalStrut(20));
            panel.add(logoutBtn);
        } else {

            JLabel title = new JLabel("Вход через Яндекс ID");
            title.setFont(new Font("SansSerif", Font.BOLD, 16));
            title.setForeground(Color.WHITE);
            title.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel hint = new JLabel("<html><div style='width:420px; color:#b0b7c6; font-size:12px; line-height:1.4;'>"
                    + "Авторизуйтесь через ваш Яндекс Аккаунт. Вы можете нажать кнопку ниже для открытия входа в браузере, "
                    + "и разрешите доступ. Затем вернитесь в приложение."
                    + "</div></html>");
            hint.setAlignmentX(Component.LEFT_ALIGNMENT);

            JButton browserOAuthBtn = new JButton("🟡 Войти через Яндекс ID (открыть в браузере)");
            browserOAuthBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
            browserOAuthBtn.setBackground(new Color(255, 214, 0));
            browserOAuthBtn.setForeground(new Color(20, 20, 20));
            browserOAuthBtn.setFocusPainted(false);
            browserOAuthBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
            browserOAuthBtn.setMaximumSize(new Dimension(440, 38));

            JLabel statusMsg = new JLabel("");
            statusMsg.setForeground(new Color(255, 138, 101));
            statusMsg.setFont(new Font("SansSerif", Font.PLAIN, 12));
            statusMsg.setAlignmentX(Component.LEFT_ALIGNMENT);

            browserOAuthBtn.addActionListener(e -> {
                browserOAuthBtn.setEnabled(false);
                long generation = yaService.reserveBrowserAuthorization();
                statusMsg.setText("Ожидание входа в браузере...");
                new SwingWorker<YandexUser, Void>() {
                    @Override protected YandexUser doInBackground() throws Exception {
                        return yaService.loginInBrowser(generation);
                    }
                    @Override protected void done() {
                        if (!dlg.isDisplayable()) return;
                        browserOAuthBtn.setEnabled(true);
                        try {
                            YandexUser user = get();
                            dlg.dispose();
                            if (onAuthChanged != null) onAuthChanged.run();
                            JOptionPane.showMessageDialog(dlg, "Вы вошли через Яндекс ID: " + user.getDisplayTitle());
                        } catch (Exception ex) {
                            String message = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                            statusMsg.setText("Ошибка входа: " + message);
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
            panel.add(statusMsg);
        }

        return panel;
    }

}
