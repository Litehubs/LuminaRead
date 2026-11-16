package com.luminaread;

import com.luminaread.service.YandexAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class YandexAuthSafetyTest {
    @Test void usernameAloneDoesNotProveIdentity() {
        var user = new YandexAuthService.YandexUser("ya_anyone", "anyone", "anyone", "", "", "", "");
        assertFalse(user.isAuthenticated());
    }

    @Test void oldTokenIsRemovedAndSavedIdentityIsNotTrusted(@TempDir Path home) throws Exception {
        Path dir = Files.createDirectories(home.resolve(".luminaread"));
        Path account = dir.resolve("yandex_account.json");
        Files.writeString(account, "{\"id\":\"old-id\",\"login\":\"old-user\",\"token\":\"legacy-secret\",\"linkedLibUsername\":\"reader\"}");
        String appClasses = Path.of(YandexAuthService.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String testClasses = Path.of(YandexAuthSafetyTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home, "-cp", appClasses + File.pathSeparator + testClasses,
                YandexAuthSafetyTest.Probe.class.getName()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
        String saved = Files.readString(account);
        assertFalse(saved.contains("legacy-secret"));
        assertFalse(saved.contains("\"token\""));
        assertFalse(saved.contains("test-token"), "New token must not be stored");
    }

    @Test void failedProfileRemovalIsReportedAfterLogout(@TempDir Path home) throws Exception {
        Path account = Files.createDirectories(home.resolve(".luminaread/yandex_account.json"));
        Files.writeString(account.resolve("keep"), "test");
        String appClasses = Path.of(YandexAuthService.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String testClasses = Path.of(YandexAuthSafetyTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home, "-cp", appClasses + File.pathSeparator + testClasses,
                YandexAuthSafetyTest.LogoutProbe.class.getName()).redirectErrorStream(true).start();
        try { assertTrue(process.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes())); }
        finally { process.destroyForcibly(); }
    }

    public static class LogoutProbe {
        public static void main(String[] args) {
            YandexAuthService service = YandexAuthService.getInstance();
            int[] notices = {0};
            service.addAuthListener(user -> notices[0]++);
            try { service.logout(); throw new AssertionError("Deletion failure was hidden"); }
            catch (java.io.UncheckedIOException expected) { }
            if (service.isLoggedIn() || notices[0] < 2) throw new AssertionError("Logout state or notification failed");
        }
    }

    public static class Probe {
        public static void main(String[] args) {
            java.net.URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new java.net.URLStreamHandler() {
                protected java.net.URLConnection openConnection(java.net.URL url) {
                    return new java.net.HttpURLConnection(url) {
                        public void disconnect() { }
                        public boolean usingProxy() { return false; }
                        public void connect() { }
                        public int getResponseCode() {
                            if (getInstanceFollowRedirects()) throw new AssertionError("Redirects must be disabled");
                            if (!"OAuth test-token".equals(getRequestProperty("Authorization"))) throw new AssertionError("Token must be sent to Yandex");
                            return 200;
                        }
                        public java.io.InputStream getInputStream() {
                            return new java.io.ByteArrayInputStream("{\"id\":\"verified-id\",\"login\":\"verified\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    };
                }
            } : null);
            YandexAuthService service = YandexAuthService.getInstance();
            if (service.isLoggedIn()) throw new AssertionError("Saved identity must not authenticate");
            try {
                service.loginWithLoginOrEmail("anyone");
                throw new AssertionError("Username must not authenticate");
            } catch (IllegalArgumentException expected) { }
            try {
                var user = service.loginWithOAuthToken("test-token");
                if (!user.isAuthenticated() || !"verified-id".equals(user.id)) throw new AssertionError("Valid provider response must authenticate");
            } catch (Exception ex) { throw new AssertionError(ex); }
        }
    }
}
