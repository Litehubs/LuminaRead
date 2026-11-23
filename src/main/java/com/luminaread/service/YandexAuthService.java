package com.luminaread.service;

import com.luminaread.util.SystemBrowser;

import java.io.IOException;
import java.net.BindException;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class YandexAuthService {
    public record Profile(String id, String login, String displayName, String defaultEmail) {
        public Profile {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Пустой ID профиля");
            login = login == null ? "" : login;
            displayName = displayName == null ? "" : displayName;
            defaultEmail = defaultEmail == null ? "" : defaultEmail;
        }

        public String title() {
            if (!displayName.isBlank()) return displayName;
            if (!login.isBlank()) return login;
            return "Пользователь Яндекса";
        }
    }

    public enum Status { SIGNED_OUT, SAVED, VERIFIED }

    public record Account(Status status, Profile profile) {
        public Account {
            Objects.requireNonNull(status);
            if ((status == Status.SIGNED_OUT) != (profile == null)) throw new IllegalArgumentException("Некорректный профиль");
        }

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
        private static final YandexAuthService INSTANCE = new YandexAuthService();
    }

    public static YandexAuthService getInstance() {
        return Holder.INSTANCE;
    }

    private final Object lock = new Object();
    private final Object writes = new Object();
    private final Object sockets = new Object();
    private Login listening;
    private final CompletableFuture<YandexConfig> config;

    private final Browser browser;
    private final int callbackPort;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Account account = SIGNED_OUT;
    private Login pending;

    private YandexAuthService() {
        browser = SystemBrowser::open;
        callbackPort = YandexCallback.PORT;
        config = CompletableFuture.supplyAsync(() -> {
            var settings = YandexConfig.load();
            restore(settings.profiles());
            return settings;
        });
        config.whenCompleteAsync((settings, error) -> {
            if (error == null) notifyListeners();
            else LOG.log(Level.WARNING, "Не удалось загрузить настройки Яндекс ID.", error);
        });
    }

    private YandexConfig settings() throws IOException, InterruptedException {
        try {
            return config.get();
        } catch (ExecutionException ex) {
            throw new IOException("Не удалось загрузить настройки Яндекс ID.", ex.getCause());
        }
    }
    YandexAuthService(YandexOAuth oauth, YandexProfileStore profiles, Browser browser, int callbackPort) {
        this.config = CompletableFuture.completedFuture(new YandexConfig(oauth, profiles));
        this.browser = browser;
        this.callbackPort = callbackPort;
        restore(profiles);
    }

    private void restore(YandexProfileStore profiles) {
        try {
            Profile saved = profiles.load();
            if (saved != null) account = new Account(Status.SAVED, saved);
        } catch (IOException | IllegalArgumentException | SecurityException ex) {
            LOG.log(Level.WARNING, "Не удалось прочитать сохранённый профиль Яндекс.", ex);
        }
    }

    public CompletionStage<Void> ready() {
        return config.thenApply(settings -> null);
    }

    public Account account() {
        return account;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
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
            LOG.log(Level.WARNING, "Не удалось обновить состояние входа Яндекс.", ex);
        }
    }

    public Login beginLogin() {
        return beginLogin(false);
    }

    public Login beginLogin(boolean rememberProfile) {
        Login attempt = new Login(rememberProfile);
        Login previous;
        synchronized (lock) {
            previous = pending;
            pending = attempt;
        }
        if (previous != null) previous.cancel();
        return attempt;
    }

    public final class Login {
        private YandexCallback callback;
        private Thread worker;
        private boolean closed;

        private final boolean rememberProfile;

        private Login(boolean rememberProfile) {
            this.rememberProfile = rememberProfile;
        }

        public Profile run() throws IOException, InterruptedException {
            synchronized (lock) {
                checkActive();
                if (worker != null) throw new IllegalStateException("Этот вход уже запущен.");
                worker = Thread.currentThread();
            }
            try {
                var settings = settings();
                YandexPkce pkce = settings.oauth().newPkce();
                YandexCallback server = listen();
                URI redirect = server.redirectUri();
                try {
                    browser.open(pkce.authorizationUri(redirect, server.state()));
                } catch (IOException ex) {
                    throw new IOException("Не удалось открыть браузер для входа через Яндекс ID.", ex);
                }

                String code = server.awaitCode();
                synchronized (lock) {
                    checkActive();
                }

                releaseCallback();
                Profile profile = settings.oauth().verify(code, pkce.verifier(), redirect);
                synchronized (writes) {
                    synchronized (lock) {
                        checkActive();
                        // Once publication starts, logout orders its deletion after this write.
                        pending = null;
                        worker = null;
                    }
                    try {
                        if (rememberProfile) settings.profiles().save(profile);
                        else settings.profiles().delete();
                    } catch (IOException | SecurityException ex) {
                        throw new IOException("Не удалось сохранить профиль Яндекс ID.", ex);
                    }
                    account = new Account(Status.VERIFIED, profile);
                }
                notifyListeners();
                return profile;
            } catch (InterruptedException | IOException ex) {
                synchronized (lock) {
                    if (worker != null && pending != this) throw new CancellationException("Вход отменён");
                }
                throw ex;
            } finally {
                synchronized (lock) {
                    worker = null;
                    if (pending == this) pending = null;
                }
                releaseCallback();
            }
        }

        private YandexCallback listen() throws IOException {
            synchronized (sockets) {
                synchronized (lock) { checkActive(); }
                if (listening != null) listening.releaseCallback();
                try {
                    callback = YandexCallback.open(callbackPort);
                    listening = this;
                    return callback;
                } catch (BindException ex) {
                    throw new IOException("Порт входа Яндекс ID занят. Закройте другую копию LuminaRead и повторите вход.", ex);
                }
            }
        }

        private void checkActive() {
            if (pending != this) throw new CancellationException("Авторизация отменена.");
        }

        private void releaseCallback() {
            synchronized (sockets) {
                if (listening != this) return;
                listening = null;
                callback.close();
                callback = null;
            }
        }

        public void cancel() {
            synchronized (lock) {
                if (closed) return;
                closed = true;
                if (pending == this) pending = null;
                if (worker != null && worker != Thread.currentThread()) worker.interrupt();
            }
            releaseCallback();
        }
    }

    public void logout() throws IOException {
        YandexProfileStore store;
        try {
            store = settings().profiles();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Выход прерван.", ex);
        }
        synchronized (writes) {
            Login attempt;
            synchronized (lock) {
                attempt = pending;
                pending = null;
            }
            if (attempt != null) attempt.cancel();
            try {
                store.delete();
            } catch (IOException | SecurityException ex) {
                throw new IOException("Не удалось удалить сохранённый профиль Яндекс ID.", ex);
            }
            account = SIGNED_OUT;
        }
        notifyListeners();
    }
}
