package com.luminaread.service;

import com.luminaread.util.SystemBrowser;

import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public final class YandexAuthService {
    public record Profile(String id, String login, String displayName, String defaultEmail) {
        public String title() {
            if (!displayName.isBlank()) return displayName;
            if (!login.isBlank()) return login;
            return "Пользователь Яндекса";
        }
    }

    public enum Status { SIGNED_OUT, SAVED, VERIFIED }

    public record Account(Status status, Profile profile) {
        public String description() {
            return switch (status) {
                case SIGNED_OUT -> "Не подключён";
                case SAVED -> "Подтвердите вход: " + profile.title();
                case VERIFIED -> profile.title();
            };
        }
    }

    @FunctionalInterface
    interface Browser {
        void open(URI url) throws IOException;
    }

    private static final Logger LOG = Logger.getLogger(YandexAuthService.class.getName());
    private static final Account SIGNED_OUT = new Account(Status.SIGNED_OUT, null);

    private static final class Holder {
        private static final YandexAuthService INSTANCE = create();
    }

    public static YandexAuthService getInstance() {
        return Holder.INSTANCE;
    }

    private final Object lock = new Object();
    private final YandexOAuth oauth;
    private final YandexProfileStore profiles;
    private final Browser browser;
    private final int callbackPort;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Account account = SIGNED_OUT;
    private Login pending;

    YandexAuthService(YandexOAuth oauth, YandexProfileStore profiles, Browser browser, int callbackPort) {
        this.oauth = oauth;
        this.profiles = profiles;
        this.browser = browser;
        this.callbackPort = callbackPort;
        try {
            Profile saved = profiles.load();
            if (saved != null) account = new Account(Status.SAVED, saved);
        } catch (IOException | IllegalArgumentException | SecurityException ex) {
            LOG.warning("Не удалось прочитать сохранённый профиль Яндекс.");
        }
    }

    public Account account() {
        return account;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
        notifyListener(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        listeners.forEach(this::notifyListener);
    }

    private void notifyListener(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException ex) {
            LOG.warning("Не удалось обновить состояние входа Яндекс.");
        }
    }

    public Login beginLogin() {
        Login attempt = new Login();
        Login previous;
        synchronized (lock) {
            previous = pending;
            pending = attempt;
        }
        if (previous != null) previous.close();
        return attempt;
    }

    public final class Login implements AutoCloseable {
        private YandexCallback callback;

        private Login() {}

        public Profile run() throws IOException, InterruptedException {
            YandexCallback server = listen();
            try {
                YandexPkce pkce = oauth.newPkce();
                URI redirect = server.redirectUri();
                try {
                    browser.open(pkce.authorizationUri(redirect, server.state()));
                } catch (IOException ex) {
                    throw new IOException("Не удалось открыть браузер для входа через Яндекс ID.");
                }

                String code = server.awaitCode();
                synchronized (lock) {
                    checkActive();
                }

                Profile profile = oauth.verify(code, pkce.verifier(), redirect);
                synchronized (lock) {
                    checkActive();
                    try {
                        profiles.save(profile);
                    } catch (IOException | SecurityException ex) {
                        throw new IOException("Не удалось сохранить профиль Яндекс ID.");
                    }
                    account = new Account(Status.VERIFIED, profile);
                }
                notifyListeners();
                return profile;
            } finally {
                close();
            }
        }

        private YandexCallback listen() throws IOException {
            synchronized (lock) {
                checkActive();
                if (callback != null) throw new IllegalStateException("Этот вход уже запущен.");
                try {
                    callback = YandexCallback.open(callbackPort);
                    return callback;
                } catch (BindException ex) {
                    throw new IOException("Порт входа Яндекс ID занят. Закройте другую копию LuminaRead и повторите вход.");
                } finally {
                    if (callback == null) pending = null;
                }
            }
        }

        private void checkActive() throws IOException {
            if (pending != this) throw new IOException("Авторизация отменена.");
        }

        @Override public void close() {
            YandexCallback server;
            synchronized (lock) {
                if (pending == this) pending = null;
                server = callback;
            }
            if (server != null) server.close();
        }
    }

    public void logout() throws IOException {
        Login attempt = null;
        try {
            synchronized (lock) {
                attempt = pending;
                pending = null;
                account = SIGNED_OUT;
                try {
                    profiles.delete();
                } catch (IOException | SecurityException ex) {
                    throw new IOException("Не удалось удалить сохранённый профиль Яндекс ID.");
                }
            }
        } finally {
            if (attempt != null) attempt.close();
            notifyListeners();
        }
    }

    private static YandexAuthService create() {
        Path dir = Path.of(System.getProperty("user.home"), ".luminaread");
        return new YandexAuthService(new YandexOAuth(clientId(dir)),
                new YandexProfileStore(dir.resolve("yandex_account.json")), SystemBrowser::open, YandexCallback.PORT);
    }

    private static String clientId(Path dir) {
        String id = System.getProperty("yandex.client.id");
        if (id != null && !id.isBlank()) return id.strip();
        id = System.getenv("YANDEX_CLIENT_ID");
        if (id != null && !id.isBlank()) return id.strip();

        try (InputStream input = Files.newInputStream(dir.resolve("auth.properties"))) {
            id = readClientId(input);
            if (!id.isBlank()) return id;
        } catch (NoSuchFileException expected) {
            // Fall back to the bundled public client ID.
        } catch (IOException | IllegalArgumentException | SecurityException ex) {
            LOG.warning("Не удалось прочитать настройки Яндекс ID.");
        }

        try (InputStream input = YandexAuthService.class.getResourceAsStream("/luminaread-yandex.properties")) {
            return input == null ? "" : readClientId(input);
        } catch (IOException | IllegalArgumentException ex) {
            LOG.warning("Не удалось прочитать настройки Яндекс ID из приложения.");
            return "";
        }
    }

    private static String readClientId(InputStream input) throws IOException {
        var properties = new Properties();
        properties.load(input);
        return properties.getProperty("yandex.client.id", "").strip();
    }
}
