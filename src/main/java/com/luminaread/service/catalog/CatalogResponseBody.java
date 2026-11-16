package com.luminaread.service.catalog;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Stops reading an oversized feed before the entire body is buffered. */
public final class CatalogResponseBody implements HttpResponse.BodySubscriber<byte[]> {
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private int remaining = MAX_BYTES;
    private boolean rejected;

    private CatalogResponseBody() {}

    public static HttpResponse.BodyHandler<byte[]> bounded() {
        return response -> new CatalogResponseBody();
    }

    @Override public CompletionStage<byte[]> getBody() {
        return body.getBody();
    }

    @Override public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        body.onSubscribe(subscription);
    }

    @Override public void onNext(List<ByteBuffer> buffers) {
        if (rejected) return;
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > remaining) {
                rejected = true;
                subscription.cancel();
                body.onError(new IOException("Ответ каталога превышает допустимый размер."));
                return;
            }
            remaining -= buffer.remaining();
        }
        body.onNext(buffers);
    }

    @Override public void onError(Throwable error) {
        if (!rejected) body.onError(error);
    }

    @Override public void onComplete() {
        if (!rejected) body.onComplete();
    }
}
