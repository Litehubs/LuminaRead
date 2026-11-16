package com.luminaread.ui;

import com.luminaread.service.YandexAuthService;
import com.luminaread.service.YandexAuthService.Login;
import com.luminaread.service.YandexAuthService.Profile;
import com.luminaread.service.YandexAuthService.Status;

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
import java.util.concurrent.Callable;

final class AuthDialog extends JDialog {
    private final YandexAuthService auth = YandexAuthService.getInstance();
    private final LatestSwingRequest<Object> request = new LatestSwingRequest<>();
    private final JTextArea status = new JTextArea(2, 36);
    private final JButton signIn;
    private final JButton signOut;
    private Login login;

    static void show(Component parent, ThemePalette theme) {
        var dialog = new AuthDialog(parent, theme);
        dialog.setVisible(true);
    }

    private AuthDialog(Component parent, ThemePalette theme) {
        super(SwingUtilities.getWindowAncestor(parent), "Яндекс ID", Dialog.ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setResizable(false);

        var account = auth.account();
        boolean verified = account.status() == Status.VERIFIED;
        boolean saved = account.status() == Status.SAVED;
        signIn = ModernUiFactory.createPrimaryButton(saved ? "Подтвердить вход в браузере" : "Войти в браузере", theme);
        signOut = ModernUiFactory.createFlatButton(verified ? "Выйти" : "Удалить сохранённый профиль", theme);
        signIn.addActionListener(event -> signIn());
        signOut.addActionListener(event -> run(() -> {
            auth.logout();
            return null;
        }));

        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(theme.getCardBackground());
        panel.setBorder(new EmptyBorder(20, 24, 20, 24));
        panel.setPreferredSize(new Dimension(455, account.profile() == null ? 200 : 240));

        var title = new JLabel(switch (account.status()) {
            case VERIFIED -> "Вход через Яндекс ID подтверждён";
            case SAVED -> "Сохранённый профиль Яндекс ID";
            case SIGNED_OUT -> "Вход через Яндекс ID";
        });
        title.setFont(ModernUiFactory.uiFont(Font.BOLD, 16));
        title.setForeground(theme.getTextPrimary());
        panel.add(title);
        panel.add(Box.createVerticalStrut(8));
        if (account.profile() != null) addProfile(panel, account.profile(), theme);

        if (!verified) {
            var hint = new JLabel("Подтвердите вход в браузере и вернитесь в LuminaRead.");
            hint.setForeground(theme.getTextSecondary());
            panel.add(hint);
            panel.add(Box.createVerticalStrut(12));
            panel.add(signIn);
        }
        if (account.profile() != null) {
            panel.add(Box.createVerticalStrut(8));
            panel.add(signOut);
        }

        status.setOpaque(false);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setFont(ModernUiFactory.uiFont(Font.PLAIN, 12));
        status.setForeground(theme.getTextPrimary());
        panel.add(Box.createVerticalStrut(8));
        panel.add(status);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { cancelLogin(); }
            @Override public void windowClosed(WindowEvent event) { cancelLogin(); }
        });
        add(panel);
        pack();
        setLocationRelativeTo(parent);
    }

    private static void addProfile(JPanel panel, Profile profile, ThemePalette theme) {
        var name = new JLabel("Аккаунт: " + profile.title());
        name.setToolTipText(name.getText());
        name.setForeground(theme.getTextPrimary());
        panel.add(name);
        if (!profile.defaultEmail().isBlank()) {
            var email = new JLabel("Почта: " + profile.defaultEmail());
            email.setForeground(theme.getTextSecondary());
            panel.add(email);
        }
        panel.add(Box.createVerticalStrut(6));
    }

    private void signIn() {
        Login attempt = auth.beginLogin();
        login = attempt;
        status.setText("Ожидание входа в браузере…");
        run(attempt::run);
    }

    private void run(Callable<Object> task) {
        signIn.setEnabled(false);
        signOut.setEnabled(false);
        request.start(task, ignored -> {
            login = null;
            dispose();
        }, error -> {
            login = null;
            signIn.setEnabled(true);
            signOut.setEnabled(true);
            status.setText(error instanceof IOException ? error.getMessage() : "Не удалось обновить аккаунт.");
        });
    }

    private void cancelLogin() {
        if (login == null) return;
        request.cancel();
        login.close();
        login = null;
    }
}
