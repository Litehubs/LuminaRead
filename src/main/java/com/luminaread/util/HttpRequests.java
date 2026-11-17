package com.luminaread.util;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Applies the request deadline to the entire buffered response, including its body. */
public final class HttpRequests {
    private HttpRequests() {}

    public static HttpResponse<byte[]> send(HttpClient client, HttpRequest request,
                                            HttpResponse.BodyHandler<byte[]> body) throws IOException, InterruptedException {
        var pending = client.sendAsync(request, body);
        try {
            long timeout = request.timeout().orElse(Duration.ofSeconds(30)).toMillis();
            return pending.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw new HttpTimeoutException("Сервер не завершил передачу вовремя.");
        } catch (InterruptedException ex) {
            pending.cancel(true);
            throw ex;
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof IOException error) throw error;
            throw new IOException("Не удалось получить ответ сервера.", ex.getCause());
        }
    }
}
