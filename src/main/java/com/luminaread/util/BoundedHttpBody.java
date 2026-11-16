package com.luminaread.util;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Stops reading an oversized HTTP response before the entire body is buffered. */
public final class BoundedHttpBody implements HttpResponse.BodySubscriber<byte[]> {
    public static final class LimitExceededException extends IOException {
        private LimitExceededException(String message) { super(message); }
    }

    private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private int remaining;
    private final String overflowMessage;
    private boolean rejected;

    private BoundedHttpBody(int maxBytes, String overflowMessage) {
        remaining = maxBytes;
        this.overflowMessage = overflowMessage;
    }

    public static HttpResponse.BodyHandler<byte[]> bounded(int maxBytes, String overflowMessage) {
        return response -> new BoundedHttpBody(maxBytes, overflowMessage);
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
                body.onError(new LimitExceededException(overflowMessage));
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
