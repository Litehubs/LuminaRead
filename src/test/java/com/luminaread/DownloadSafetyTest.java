package com.luminaread;

import com.luminaread.service.catalog.BookDownloader;
import java.io.ByteArrayInputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

public final class DownloadSafetyTest {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("download-safety-");
        Path existing = dir.resolve("book.txt");
        Files.writeString(existing, "existing book");
        try {
            try {
                new BookDownloader(new FakeClient("short"), null).downloadBookFile("http://example.com/book.txt", "book", "TXT", dir, null);
                throw new AssertionError("Invalid download must fail");
            } catch (IllegalStateException expected) {
                if (!Files.exists(existing) || !Files.readString(existing).equals("existing book"))
                    throw new AssertionError("Failed download damaged an existing book");
            }
            String newBook = "A valid downloaded book. ".repeat(5);
            Path saved = new BookDownloader(new FakeClient(newBook), null)
                    .downloadBookFile("http://example.com/book.txt", "book", "TXT", dir, null).toPath();
            if (!Files.readString(existing).equals("existing book") || !Files.readString(saved).equals(newBook) || saved.equals(existing))
                throw new AssertionError("Successful download must preserve existing book and save new content separately");
            try (var files = Files.list(dir)) {
                if (files.anyMatch(path -> path.getFileName().toString().startsWith(".luminaread-download-")))
                    throw new AssertionError("Temporary downloads must be cleaned up");
            }
        } finally {
            try (var files = Files.list(dir)) { for (Path file : files.toList()) Files.deleteIfExists(file); }
            Files.delete(dir);
        }
        System.out.println("PASS download safety");
    }
    static final class FakeClient extends HttpClient {
        private final byte[] content;
        boolean bodyClosed;
        FakeClient(String content) { this(content.getBytes(StandardCharsets.UTF_8)); }
        FakeClient(byte[] content) { this.content = content; }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        public Optional<Duration> connectTimeout() { return Optional.empty(); }
        public Redirect followRedirects() { return Redirect.NEVER; }
        public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { return null; }
        public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); }
        public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
        @SuppressWarnings("unchecked") public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return (HttpResponse<T>) new HttpResponse<java.io.InputStream>() {
                public int statusCode() { return 200; }
                public HttpRequest request() { return request; }
                public Optional<HttpResponse<java.io.InputStream>> previousResponse() { return Optional.empty(); }
                public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (a,b) -> true); }
                public java.io.InputStream body() { return new ByteArrayInputStream(content) {
                    @Override public void close() throws java.io.IOException { bodyClosed = true; super.close(); }
                }; }
                public Optional<SSLSession> sslSession() { return Optional.empty(); }
                public URI uri() { return request.uri(); }
                public Version version() { return Version.HTTP_1_1; }
            };
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new UnsupportedOperationException(); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { throw new UnsupportedOperationException(); }
    }
}
