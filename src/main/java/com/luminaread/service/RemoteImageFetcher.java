package com.luminaread.service;

import com.luminaread.util.TimedInput;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.luminaread.service.MediaCacheService.*;

/** Downloads external images using the address checked for each redirect hop. */
final class RemoteImageFetcher {
    private static final Logger LOG = Logger.getLogger(RemoteImageFetcher.class.getName());
    private static final CloseableHttpClient IMAGE_HTTP = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofSeconds(8))
                            .setSocketTimeout(Timeout.ofSeconds(20))
                            .build())
                    .setMaxConnTotal(12)
                    .build())
            .setDefaultRequestConfig(RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofSeconds(8))
                    .setResponseTimeout(Timeout.ofSeconds(20))
                    .build())
            .disableRedirectHandling()
            .disableCookieManagement()
            .disableAutomaticRetries()
            .disableContentCompression()
            .build();

    private RemoteImageFetcher() {}

    static byte[] fetch(String urlStr, String referer, ImageUrlPolicy imageUrls, int maxImageBytes) {
        try {
            URI address = URI.create(urlStr);
            for (int redirects = 0; redirects <= 4; redirects++) {
                var approved = imageUrls.check(address);
                var request = imageRequest(address, redirects == 0 ? referer : null);
                try (var response = connectImage(address, approved, request)) {
                    int code = response.getCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        var location = response.getFirstHeader("Location");
                        request.cancel();
                        address = ImageUrlPolicy.redirect(address, location == null ? null : location.getValue());
                        continue;
                    }

                    var entity = response.getEntity();
                    if (code < 200 || code >= 300 || entity == null) {
                        request.cancel();
                        return null;
                    }
                    if (entity.getContentLength() > maxImageBytes) {
                        request.cancel();
                        return null;
                    }
                    try (InputStream input = new TimedInput(entity.getContent(), java.time.Duration.ofSeconds(30), request::cancel)) {
                        try {
                            return com.luminaread.util.BoundedInput.read(input, maxImageBytes);
                        } catch (java.io.IOException ex) {
                            request.cancel();
                            throw ex;
                        }
                    }
                }
            }
        } catch (Exception | LinkageError ex) {
            LOG.log(Level.FINE, "Не удалось загрузить изображение", ex);
        }
        return null;
    }

    private static ClassicHttpResponse connectImage(URI address, java.net.InetAddress[] approved, HttpGet request)
            throws java.io.IOException {
        for (int i = 0; i < approved.length; i++) {
            // Connect to the validated address while keeping the DNS hostname for Host and TLS verification.
            var pinned = new HttpHost(address.getScheme(), approved[i], address.getHost(), address.getPort());
            try {
                return IMAGE_HTTP.executeOpen(pinned, request, null);
            } catch (java.net.ConnectException | org.apache.hc.client5.http.ConnectTimeoutException ex) {
                if (i == approved.length - 1) throw ex;
            }
        }
        throw new java.io.IOException("Не удалось подключиться к серверу изображений");
    }

    private static HttpGet imageRequest(URI address, String referer) {
        HttpGet request = new HttpGet(address);
        request.setHeader("User-Agent", BROWSER_UA);
        request.setHeader("Accept", "image/avif,image/webp,image/apng,image/png,image/jpeg,image/*");
        String host = address.getHost().toLowerCase(Locale.ROOT);
        if (referer != null && !referer.isEmpty()) {
            request.setHeader("Referer", referer);
            try {
                URI refUri = URI.create(referer);
                if (refUri.getScheme() != null && refUri.getHost() != null) {
                    request.setHeader("Origin", refUri.getScheme() + "://" + refUri.getHost());
                }
            } catch (IllegalArgumentException ex) {
                LOG.log(Level.FINE, "Некорректный адрес источника изображения", ex);
            }
        } else {
            if (isMangaLibUrl(host)) {
                request.setHeader("Referer", MGL_ORIGIN + "/");
                request.setHeader("Origin", MGL_ORIGIN);
            } else if (host.contains("remanga") || host.contains("reimg")) {
                request.setHeader("Referer", REMANGA_ORIGIN + "/");
                request.setHeader("Origin", REMANGA_ORIGIN);
            } else if (host.contains("mangadex")) {
                request.setHeader("Referer", MANGADEX_ORIGIN + "/");
            } else if (host.contains("ranobelib")) {
                request.setHeader("Referer", RANOBE_ORIGIN + "/");
                request.setHeader("Origin", RANOBE_ORIGIN);
            }
        }

        return request;
    }

}
