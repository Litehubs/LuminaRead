package com.luminaread.service;

import com.luminaread.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class YandexAuthSafetyTest {
    @Test void savedProfileHasItsOwnStateAndLegacyCredentialsAreRemoved(@TempDir Path home) throws Exception {
        Path file = home.resolve("yandex_account.json");
        Files.writeString(file, """
                {"id":"old-id","login":"old-user","defaultEmail":"old-user@yandex.ru",
                 "token":"legacy-secret","refresh_token":"legacy-refresh","avatarUrl":"legacy-url"}
                """);
        var service = service(file, url -> fail("Restoring a local profile must not open the browser"));
        assertEquals(YandexAuthService.Status.SAVED, service.account().status());
        assertEquals("Подтвердите вход: old-user", service.account().description());
        assertEquals("", service.account().profile().defaultEmail());
        var fields = (Map<?, ?>) Json.parse(Files.readString(file));
        assertEquals("1", fields.get("version"));
        assertFalse(fields.containsKey("token"));
        assertFalse(fields.containsKey("refresh_token"));
        assertFalse(fields.containsKey("avatarUrl"));
        service.logout();
        assertFalse(Files.exists(file));
        assertEquals(YandexAuthService.Status.SIGNED_OUT, service.account().status());
    }

    @Test void profileJsonRoundTripsEscapesAndPreservesAConfirmedAddress(@TempDir Path home) throws Exception {
        var store = new YandexProfileStore(home.resolve("yandex_account.json"));
        var title = new StringBuilder("Имя \" \\ 🟡");
        for (char control = 0; control < 0x20; control++) title.append(control);
        var original = new YandexAuthService.Profile("id", "login", title.toString(), "login@yandex.ru");
        store.save(original);
        assertEquals(original, store.load());
        assertTrue(Files.readString(store.path()).contains("\\u0000"));
    }

    @Test void logoutCompletesWhileTheBrowserIsStillOpening(@TempDir Path home) throws Exception {
        var browserEntered = new CountDownLatch(1);
        var releaseBrowser = new CountDownLatch(1);
        var service = service(home.resolve("yandex_account.json"), url -> {
            browserEntered.countDown();
            try {
                if (!releaseBrowser.await(8, TimeUnit.SECONDS)) throw new IOException("Browser test timed out");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException(ex);
            }
        });
        var threads = Executors.newFixedThreadPool(2);
        try {
            var login = service.beginLogin();
            var auth = threads.submit(login::run);
            assertTrue(browserEntered.await(5, TimeUnit.SECONDS));
            var logout = threads.submit(() -> { service.logout(); return service.account().status(); });
            assertEquals(YandexAuthService.Status.SIGNED_OUT, logout.get(3, TimeUnit.SECONDS));
            releaseBrowser.countDown();
            var failure = assertThrows(ExecutionException.class, () -> auth.get(3, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.CancellationException.class, failure.getCause());
            assertFalse(Files.exists(home.resolve("yandex_account.json")));
        } finally {
            releaseBrowser.countDown();
            threads.shutdownNow();
        }
    }

    @Test void failedRemovalPreservesAccountAndCause(@TempDir Path home) throws Exception {
        Path file = home.resolve("account.json");
        var profile = new YandexAuthService.Profile("id", null, null, null);
        new YandexProfileStore(file).save(profile);
        var service = service(file, url -> fail());
        Files.delete(file);
        Files.createDirectories(file.resolve("occupied"));
        var notices = new java.util.concurrent.atomic.AtomicInteger();
        service.addListener(notices::incrementAndGet);
        assertNotNull(assertThrows(IOException.class, service::logout).getCause());
        assertEquals(profile, service.account().profile());
        assertEquals("Пользователь Яндекса", profile.title());
        assertEquals(0, notices.get());
        Files.delete(file.resolve("occupied"));
        service.logout();
        assertEquals(1, notices.get());
        assertEquals(YandexAuthService.Status.SIGNED_OUT, service.account().status());
        assertThrows(IllegalArgumentException.class, () -> new YandexOAuth(" "));
        assertThrows(IllegalArgumentException.class, () -> new YandexAuthService.Account(YandexAuthService.Status.SAVED, null));
    }

    private static YandexAuthService service(Path path, YandexAuthService.Browser browser) {
        return new YandexAuthService(new YandexOAuth("client"), new YandexProfileStore(path), browser, 0);
    }
}
