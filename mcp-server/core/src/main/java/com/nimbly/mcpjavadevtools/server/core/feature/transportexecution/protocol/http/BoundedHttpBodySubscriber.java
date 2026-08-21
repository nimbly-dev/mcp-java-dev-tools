package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Collects an HTTP response body while enforcing its byte limit during delivery. */
final class BoundedHttpBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private final int maximumBytes;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;
    private boolean completed;

    BoundedHttpBodySubscriber(int maximumBytes) {
        this.maximumBytes = maximumBytes;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        value.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (completed) {
            return;
        }
        for (ByteBuffer buffer : buffers) {
            int byteCount = buffer.remaining();
            if (output.size() + byteCount > maximumBytes) {
                completed = true;
                subscription.cancel();
                body.completeExceptionally(new HttpResponseTooLargeException());
                return;
            }
            byte[] bytes = new byte[byteCount];
            buffer.get(bytes);
            output.writeBytes(bytes);
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable throwable) {
        if (!completed) {
            completed = true;
            body.completeExceptionally(throwable);
        }
    }

    @Override
    public void onComplete() {
        if (!completed) {
            completed = true;
            body.complete(output.toByteArray());
        }
    }
}
