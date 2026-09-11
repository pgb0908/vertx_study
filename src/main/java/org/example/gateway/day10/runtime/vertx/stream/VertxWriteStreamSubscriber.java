package org.example.gateway.day10.runtime.vertx.stream;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Flow.Subscriber<byte[]> -> WriteStream<Buffer> 어댑터. writeQueueFull()/
 * drainHandler()가 WriteStream 쪽의 backpressure 신호이므로, 큐가 꽉 찼으면
 * 다음 request(1)을 drainHandler가 불릴 때까지 미룬다.
 */
public final class VertxWriteStreamSubscriber implements Flow.Subscriber<byte[]> {

    private final WriteStream<Buffer> target;
    private final CompletableFuture<Void> done;
    private Flow.Subscription subscription;

    public VertxWriteStreamSubscriber(WriteStream<Buffer> target, CompletableFuture<Void> done) {
        this.target = target;
        this.done = done;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        target.exceptionHandler(this::onError);
        target.drainHandler(v -> subscription.request(1));
        subscription.request(1);
    }

    @Override
    public void onNext(byte[] item) {
        target.write(Buffer.buffer(item));
        if (!target.writeQueueFull()) {
            subscription.request(1);
        }
        // writeQueueFull이면 여기서 더 요청하지 않고, drainHandler가 다시 불릴 때 재개한다.
    }

    @Override
    public void onError(Throwable throwable) {
        done.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        target.end();
        done.complete(null);
    }
}
