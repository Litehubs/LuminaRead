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
    public record YandexUser(String id, String login, String displayName, String defaultEmail) {
        public String getDisplayTitle() {
            if (!displayName.isBlank()) return displayName;
            if (!login.isBlank()) return login;
            return "Пользователь Яндекса";
        }
    }

    public enum Status { SIGNED_OUT, SAVED_PROFILE, VERIFIED }

    public record Account(Status status, YandexUser profile) {
        public String description() {
            return switch (status) {
                case SIGNED_OUT -> "Не подключён";
                case SAVED_PROFILE -> "Подтвердите вход: " + profile.getDisplayTitle();
                case VERIFIED -> profile.getDisplayTitle();
            };
        }
    }

    @FunctionalInterface
    interface Browser {
        void open(URI url) throws IOException;
    }

    private static final Logger LOG = Logger.getLogger(YandexAuthService.class.getName());
    private static final Account SIGNED_OUT = new Account(Status.SIGNED_OUT, null);

    private static class DefaultInstance {
        private static final YandexAuthService INSTANCE = createDefault();

        private static YandexAuthService createDefault() {
            Path directory = Path.of(System.getProperty("user.home"), ".luminaread");
            String clientId = clientId(directory);
            return new YandexAuthService(clientId, new YandexOAuthClient(clientId),
                    new YandexProfileStore(directory.resolve("yandex_account.json")), SystemBrowser::open, YandexCallback.PORT);
        }

        private static String clientId(Path directory) {
            String configured = System.getProperty("yandex.client.id");
            if (configured != null && !configured.isBlank()) return configured.strip();
            configured = System.getenv("YANDEX_CLIENT_ID");
            if (configured != null && !configured.isBlank()) return configured.strip();
            try (InputStream input = Files.newInputStream(directory.resolve("auth.properties"))) {
                configured = readClientId(input);
                if (!configured.isBlank()) return configured;
            } catch (NoSuchFileException expected) {
                // Use the bundled public client identifier by default.
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

    public static YandexAuthService getInstance() {
        return DefaultInstance.INSTANCE;
    }

    private final Object stateLock = new Object();
    private final String clientId;
    private final YandexOAuthClient oauth;
    private final YandexProfileStore profiles;
    private final Browser browser;
    private final int callbackPort;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Account account = SIGNED_OUT;
    private BrowserLogin pending;

    YandexAuthService(String clientId, YandexOAuthClient oauth, YandexProfileStore profiles, Browser browser, int callbackPort) {
        this.clientId = clientId;
        this.oauth = oauth;
        this.profiles = profiles;
        this.browser = browser;
        this.callbackPort = callbackPort;
        try {
            YandexUser saved = profiles.load();
            if (saved != null) account = new Account(Status.SAVED_PROFILE, saved);
        } catch (IOException | IllegalArgumentException | SecurityException ex) {
            LOG.warning("Не удалось прочитать сохранённый профиль Яндекс.");
        }
    }

    public Account account() {
        return account;
    }

    public void addAuthListener(Runnable listener) {
        listeners.add(listener);
        notifyListener(listener);
    }

    public void removeAuthListener(Runnable listener) {
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

    public BrowserLogin prepareBrowserLogin() {
        BrowserLogin attempt = new BrowserLogin();
        BrowserLogin previous;
        synchronized (stateLock) {
            previous = pending;
            pending = attempt;
        }
        if (previous != null) previous.closeCallback();
        return attempt;
    }

    public final class BrowserLogin implements AutoCloseable {
        private volatile YandexCallback callback;
        private boolean started;

        private BrowserLogin() {}

        public YandexUser complete() throws IOException, InterruptedException {
            synchronized (stateLock) {
                ensurePending();
                if (started) throw new IllegalStateException("Этот вход уже запущен.");
                started = true;
            }
            try {
                YandexCallback local;
                YandexPkce pkce;
                synchronized (stateLock) {
                    ensurePending();
                    if (clientId.isBlank()) throw new IOException("Не задан идентификатор приложения Яндекс ID.");
                    pkce = YandexPkce.create(clientId);
                    try {
                        local = YandexCallback.open(callbackPort);
                    } catch (BindException ex) {
                        throw new IOException("Порт входа Яндекс ID занят. Закройте другую копию LuminaRead и повторите вход.");
                    }
                    callback = local;
                }

                try {
                    browser.open(pkce.authorizationUri(local.redirectUri(), local.state()));
                } catch (IOException ex) {
                    throw new IOException("Не удалось открыть браузер для входа через Яндекс ID.");
                }
                String code = local.awaitCode();
                synchronized (stateLock) {
                    ensurePending();
                }
                YandexUser profile = oauth.verify(code, pkce.verifier(), local.redirectUri());
                synchronized (stateLock) {
                    ensurePending();
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

        private void ensurePending() throws IOException {
            if (pending != this) throw new IOException("Авторизация отменена.");
        }

        @Override public void close() {
            synchronized (stateLock) {
                if (pending == this) pending = null;
            }
            closeCallback();
        }

        private void closeCallback() {
            YandexCallback local = callback;
            if (local != null) local.close();
        }
    }

    public void logout() throws IOException {
        BrowserLogin attempt = null;
        try {
            synchronized (stateLock) {
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
            if (attempt != null) attempt.closeCallback();
            notifyListeners();
        }
    }
}
