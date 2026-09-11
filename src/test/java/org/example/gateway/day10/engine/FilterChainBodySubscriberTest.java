package org.example.gateway.day10.engine;

import org.example.gateway.day10.domain.filter.Filter;
import org.example.gateway.day10.domain.filter.FilterResult;
import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FilterChainBodySubscriber는 chunk별 필터 처리 시간이 달라도(A=30ms, B=5ms, C=10ms
 * 같은 케이스) HTTP body의 chunk 순서를 절대 바꾸면 안 된다. 이전 구현은 upstream
 * Subscription을 sink에 그대로 넘겨서, sink가 한 번에 여러 개를 request하면
 * chunk별 비동기 처리가 겹쳐 실행돼 먼저 끝나는 chunk가 먼저 전달될 수 있었다.
 */
class FilterChainBodySubscriberTest {

    @Test
    void preservesChunkOrderRegardlessOfPerChunkProcessingTime() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger maxInFlight = new AtomicInteger();

            Filter variableDelayFilter = new Filter() {
                @Override
                public CompletableFuture<FilterResult> onRequest(GatewayExchange exchange, GatewayRequest request) {
                    return CompletableFuture.completedFuture(new FilterResult.Next(request));
                }

                @Override
                public CompletableFuture<GatewayResponse> onResponse(GatewayExchange exchange, GatewayResponse response) {
                    return CompletableFuture.completedFuture(response);
                }

                @Override
                public CompletableFuture<byte[]> onRequestBody(byte[] chunk) {
                    int now = inFlight.incrementAndGet();
                    maxInFlight.updateAndGet(m -> Math.max(m, now));
                    long delayMs = switch (chunk[0]) {
                        case 'A' -> 30L;
                        case 'B' -> 5L;
                        default -> 10L;
                    };
                    return CompletableFuture.supplyAsync(() -> {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        inFlight.decrementAndGet();
                        return chunk;
                    }, pool);
                }
            };

            List<byte[]> received = new CopyOnWriteArrayList<>();
            CompletableFuture<Void> done = new CompletableFuture<>();

            Flow.Subscriber<byte[]> sink = new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(3); // 한 번에 여러 개를 요청 — 예전 구현이면 겹쳐 처리됨
                }

                @Override
                public void onNext(byte[] item) {
                    received.add(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    done.completeExceptionally(throwable);
                }

                @Override
                public void onComplete() {
                    done.complete(null);
                }
            };

            FilterChainBodySubscriber subject = new FilterChainBodySubscriber(sink, List.of(variableDelayFilter), true);
            new EagerUpstream(List.of(
                "A".getBytes(StandardCharsets.UTF_8),
                "B".getBytes(StandardCharsets.UTF_8),
                "C".getBytes(StandardCharsets.UTF_8)
            )).subscribe(subject);

            done.get(5, TimeUnit.SECONDS);

            assertEquals(List.of("A", "B", "C"),
                received.stream().map(b -> new String(b, StandardCharsets.UTF_8)).collect(Collectors.toList()));
            assertEquals(1, maxInFlight.get(), "at most one chunk should be under filter processing at a time");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void completesImmediatelyWhenUpstreamHasNoChunksAtAll() throws Exception {
        // GET 요청처럼 body가 아예 없는 경우: request(1)에 대한 응답이 onNext가 아니라
        // onComplete로 곧장 오는 상황 — "처리 중인 chunk가 있어야만 onComplete를
        // 미룬다"는 상태 구분이 없으면 여기서 영원히 onComplete가 전달되지 않는다.
        Filter passthrough = new Filter() {
            @Override
            public CompletableFuture<FilterResult> onRequest(GatewayExchange exchange, GatewayRequest request) {
                return CompletableFuture.completedFuture(new FilterResult.Next(request));
            }

            @Override
            public CompletableFuture<GatewayResponse> onResponse(GatewayExchange exchange, GatewayResponse response) {
                return CompletableFuture.completedFuture(response);
            }
        };

        CompletableFuture<Void> done = new CompletableFuture<>();
        Flow.Subscriber<byte[]> sink = new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(byte[] item) {
                done.completeExceptionally(new AssertionError("no chunk should have been delivered"));
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }
        };

        FilterChainBodySubscriber subject = new FilterChainBodySubscriber(sink, List.of(passthrough), true);
        new EagerUpstream(List.of()).subscribe(subject);

        done.get(5, TimeUnit.SECONDS);
    }

    /**
     * request(n)을 받으면 그 즉시(같은 호출 프레임에서) n개까지 밀어넣는 "공격적인"
     * upstream. 실제 VertxReadStreamPublisher보다 더 관대하게 동작해서, subscriber가
     * 자기 demand를 스스로 관리하지 않으면(=요청을 그대로 sink에 넘기면) 겹쳐 처리
     * 버그를 확실히 드러낸다.
     */
    private static final class EagerUpstream implements Flow.Publisher<byte[]> {
        private final List<byte[]> items;
        private int index = 0;

        EagerUpstream(List<byte[]> items) {
            this.items = items;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super byte[]> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    for (long i = 0; i < n && index < items.size(); i++) {
                        subscriber.onNext(items.get(index++));
                    }
                    if (index >= items.size()) {
                        subscriber.onComplete();
                    }
                }

                @Override
                public void cancel() {
                }
            });
        }
    }
}
