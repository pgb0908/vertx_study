package org.example.gateway.day10.runtime.vertx.resilience;

import io.vertx.circuitbreaker.OpenCircuitException;
import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayHeaders;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.UpstreamClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 순수 JUnit5 — 실제 Vertx 인스턴스 없이 VertxRetryPolicy의 재시도 규칙을 검증한다.
 * OpenCircuitException은 싱글턴(io.vertx.circuitbreaker.OpenCircuitException.INSTANCE)
 * 이라 Vertx 없이도 인스턴스화 가능하다.
 */
class VertxRetryPolicyTest {

    private static final Endpoint ENDPOINT = new Endpoint("localhost", 9999);

    @Test
    void idempotentRequestRetriesOnFailureUntilSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        UpstreamClient client = (endpoint, request) -> {
            int n = attempts.incrementAndGet();
            if (n < 3) {
                return failed(new RuntimeException("boom-" + n));
            }
            return CompletableFuture.completedFuture(response(200));
        };

        VertxRetryPolicy policy = new VertxRetryPolicy(2);
        GatewayResponse result = policy.execute(ENDPOINT, request("GET"), client).join();

        assertEquals(200, result.statusCode());
        assertEquals(3, attempts.get());
    }

    @Test
    void idempotentRequestGivesUpAfterMaxRetries() {
        AtomicInteger attempts = new AtomicInteger();
        UpstreamClient client = (endpoint, request) -> {
            attempts.incrementAndGet();
            return failed(new RuntimeException("always fails"));
        };

        VertxRetryPolicy policy = new VertxRetryPolicy(2);
        CompletableFuture<GatewayResponse> future = policy.execute(ENDPOINT, request("GET"), client);

        assertThrows(Exception.class, future::join);
        assertEquals(3, attempts.get()); // 최초 1회 + 재시도 2회
    }

    @Test
    void nonIdempotentRequestNeverRetries() {
        AtomicInteger attempts = new AtomicInteger();
        UpstreamClient client = (endpoint, request) -> {
            attempts.incrementAndGet();
            return failed(new RuntimeException("boom"));
        };

        VertxRetryPolicy policy = new VertxRetryPolicy(2);
        CompletableFuture<GatewayResponse> future = policy.execute(ENDPOINT, request("POST"), client);

        assertThrows(Exception.class, future::join);
        assertEquals(1, attempts.get());
    }

    @Test
    void openCircuitStopsRetryingEvenWithBudgetLeft() {
        AtomicInteger attempts = new AtomicInteger();
        UpstreamClient client = (endpoint, request) -> {
            attempts.incrementAndGet();
            return failed(OpenCircuitException.INSTANCE);
        };

        VertxRetryPolicy policy = new VertxRetryPolicy(2);
        CompletableFuture<GatewayResponse> future = policy.execute(ENDPOINT, request("GET"), client);

        assertThrows(Exception.class, future::join);
        assertEquals(1, attempts.get()); // 회로가 열렸으면 예산이 남아도 즉시 포기
    }

    @Test
    void idempotentRetryAlwaysSendsEmptyBodyEvenOnFirstAttempt() {
        List<String> seenMethods = new ArrayList<>();
        UpstreamClient client = (endpoint, request) -> {
            seenMethods.add(request.method());
            // GatewayBody.EMPTY는 몇 번이든 구독 가능해야 한다 — 여기서 실제로 구독해본다.
            CompletableFuture<Boolean> hadData = new CompletableFuture<>();
            request.body().subscribe(new java.util.concurrent.Flow.Subscriber<byte[]>() {
                boolean sawData = false;
                public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                public void onNext(byte[] item) { sawData = true; }
                public void onError(Throwable t) { hadData.completeExceptionally(t); }
                public void onComplete() { hadData.complete(sawData); }
            });
            assertFalse(hadData.join());
            return CompletableFuture.completedFuture(response(200));
        };

        VertxRetryPolicy policy = new VertxRetryPolicy(2);
        policy.execute(ENDPOINT, request("GET"), client).join();

        assertEquals(List.of("GET"), seenMethods);
    }

    private static <T> CompletableFuture<T> failed(Throwable t) {
        CompletableFuture<T> cf = new CompletableFuture<>();
        cf.completeExceptionally(t);
        return cf;
    }

    private static GatewayRequest request(String method) {
        return new GatewayRequest(method, "/echo", GatewayHeaders.empty(), GatewayBody.EMPTY);
    }

    private static GatewayResponse response(int statusCode) {
        return new GatewayResponse(statusCode, GatewayHeaders.empty(), GatewayBody.EMPTY);
    }
}
