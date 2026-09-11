package org.example.gateway.day10.runtime.vertx.stream;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReadStream<Buffer> -> Flow.Publisher<byte[]> 어댑터. Vert.x 4.5.10에는
 * ReadStream.toFlowPublisher() 같은 내장 변환이 없어서(vertx-core jar를 직접
 * javap로 확인) 직접 다리를 놓는다.
 *
 * Vert.x의 fetch(n)이 정확히 n개의 handler() 콜백만 발생시키도록 설계돼 있어서
 * Reactive Streams의 request(n)와 자연스럽게 대응된다 — subscribe() 직후 소스를
 * pause()해두고, request(n)이 올 때마다 fetch(n)으로 위임한다.
 */
public final class VertxReadStreamPublisher implements Flow.Publisher<byte[]> {

    private final ReadStream<Buffer> source;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);

    public VertxReadStreamPublisher(ReadStream<Buffer> source) {
        this.source = source;
        source.pause();
    }

    @Override
    public void subscribe(Flow.Subscriber<? super byte[]> subscriber) {
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(noopSubscription());
            subscriber.onError(new IllegalStateException("this body has already been subscribed to once"));
            return;
        }

        source.exceptionHandler(subscriber::onError);
        source.handler(buffer -> subscriber.onNext(buffer.getBytes()));
        source.endHandler(v -> subscriber.onComplete());

        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                if (n <= 0) {
                    subscriber.onError(new IllegalArgumentException("request(n) must be positive, got " + n));
                    return;
                }
                source.fetch(n);
            }

            @Override
            public void cancel() {
                source.handler(null);
                source.endHandler(null);
                source.exceptionHandler(null);
                source.resume();
            }
        });
    }

    private static Flow.Subscription noopSubscription() {
        return new Flow.Subscription() {
            @Override
            public void request(long n) {
            }

            @Override
            public void cancel() {
            }
        };
    }
}
