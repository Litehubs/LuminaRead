package com.luminaread;

import com.luminaread.service.YandexAuthService;
import com.luminaread.service.YandexPkce;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

final class YandexBrowserFlowTest {
    @Test void exchangesCodeWithoutFollowingRedirects(@TempDir Path home) throws Exception {
        String main = Path.of(YandexAuthService.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String tests = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home, "-cp", main + File.pathSeparator + tests,
                Probe.class.getName()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }
    public static class Probe {
        public static void main(String[] args) throws Exception {
            YandexPkce attempt = YandexPkce.create(YandexAuthService.YANDEX_OAUTH_CLIENT_ID);
            var oversizedProfile = new java.util.concurrent.atomic.AtomicBoolean();
            java.net.URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new java.net.URLStreamHandler() {
                protected java.net.URLConnection openConnection(java.net.URL url) {
                    return new java.net.HttpURLConnection(url) {
                        private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                        public void disconnect() { }
                        public boolean usingProxy() { return false; }
                        public void connect() { }
                        public java.io.OutputStream getOutputStream() { return output; }
                        public int getResponseCode() {
                            if (getInstanceFollowRedirects()) throw new AssertionError("Redirects enabled");
                            if (url.getHost().equals("oauth.yandex.ru")) {
                                String body = output.toString(java.nio.charset.StandardCharsets.UTF_8);
                                if (!body.contains("code=one-time-code") || !body.contains("code_verifier=" + attempt.verifier()))
                                    throw new AssertionError("Missing code or verifier");
                            } else if (!"OAuth issued-token".equals(getRequestProperty("Authorization")))
                                throw new AssertionError("Wrong authorization header");
                            return 200;
                        }
                        public java.io.InputStream getInputStream() {
                            if (!url.getHost().equals("oauth.yandex.ru") && oversizedProfile.get()) {
                                return new java.io.ByteArrayInputStream(new byte[65_537]);
                            }
                            String result = url.getHost().equals("oauth.yandex.ru") ? "{\"access_token\":\"issued-token\"}" : "{\"id\":\"verified-id\"}";
                            return new java.io.ByteArrayInputStream(result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    };
                }
            } : null);
            YandexAuthService service = YandexAuthService.getInstance();
            var field = YandexAuthService.class.getDeclaredField("pendingAuthorization");
            field.setAccessible(true);
            field.set(service, attempt);
            if (!service.completeBrowserAuthorization("one-time-code").isAuthenticated()) throw new AssertionError("Login failed");
            try { service.completeBrowserAuthorization("one-time-code"); throw new AssertionError("Code reused"); }
            catch (IllegalStateException expected) { }
            oversizedProfile.set(true);
            try {
                service.loginWithOAuthToken("issued-token");
                throw new AssertionError("Oversized profile was accepted");
            } catch (java.io.IOException expected) {
                if (!expected.getMessage().contains("Слишком большой ответ")) throw expected;
            }
        }
    }
}
