package org.example.gateway.day10.runtime.vertx.resilience;

import io.vertx.circuitbreaker.OpenCircuitException;
import io.vertx.circuitbreaker.TimeoutException;
import io.vertx.core.Vertx;
import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayHeaders;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.UpstreamClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * io.vertx.circuitbreaker.CircuitBreaker는 실제로 동작시키려면 Vertx 인스턴스가
 * 필요하지만, 소켓 통신은 필요 없다 — 그래서 curl 기반 TESTING.md 대신 여기서
 * JUnit으로 커버한다.
 */
class VertxCircuitBreakerTest {

    private static final Endpoint ENDPOINT = new Endpoint("localhost", 9999);

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void opensAfterMaxFailuresAndRejectsWithoutCallingClient() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        UpstreamClient failingClient = (endpoint, request) -> {
            calls.incrementAndGet();
            return failed(new RuntimeException("boom"));
        };

        VertxCircuitBreaker breaker = new VertxCircuitBreaker(vertx, "test-breaker-1", 2, 500, 60_000);

        awaitFailure(breaker.execute(ENDPOINT, request(), failingClient));
        awaitFailure(breaker.execute(ENDPOINT, request(), failingClient));
        assertEquals(2, calls.get());

        // 이제 회로가 열렸어야 한다 — 세 번째 호출은 client를 부르지도 않고 즉시 실패해야 한다.
        Exception ex = awaitFailure(breaker.execute(ENDPOINT, request(), failingClient));
        assertEquals(2, calls.get(), "circuit open이면 client가 다시 호출되면 안 됨");
        assertInstanceOf(OpenCircuitException.class, unwrap(ex));
    }

    @Test
    void treatsUpstream5xxAsFailure() {
        UpstreamClient client = (endpoint, request) -> CompletableFuture.completedFuture(response(500));
        VertxCircuitBreaker breaker = new VertxCircuitBreaker(vertx, "test-breaker-2", 5, 500, 60_000);

        Exception ex = awaitFailure(breaker.execute(ENDPOINT, request(), client));
        assertTrue(unwrap(ex).getMessage().contains("500"));
    }

    @Test
    void timesOutSlowUpstream() {
        UpstreamClient neverResponds = (endpoint, request) -> new CompletableFuture<>(); // 절대 안 끝남
        VertxCircuitBreaker breaker = new VertxCircuitBreaker(vertx, "test-breaker-3", 5, 100, 60_000);

        Exception ex = awaitFailure(breaker.execute(ENDPOINT, request(), neverResponds));
        assertInstanceOf(TimeoutException.class, unwrap(ex));
    }

    @Test
    void successPassesResponseThrough() {
        UpstreamClient client = (endpoint, request) -> CompletableFuture.completedFuture(response(200));
        VertxCircuitBreaker breaker = new VertxCircuitBreaker(vertx, "test-breaker-4", 2, 500, 60_000);

        GatewayResponse response = breaker.execute(ENDPOINT, request(), client).join();
        assertEquals(200, response.statusCode());
    }

    private static Exception awaitFailure(CompletableFuture<GatewayResponse> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
            fail("expected failure");
            return null;
        } catch (ExecutionException e) {
            return e;
        } catch (Exception e) {
            return e;
        }
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cause = t;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static <T> CompletableFuture<T> failed(Throwable t) {
        CompletableFuture<T> cf = new CompletableFuture<>();
        cf.completeExceptionally(t);
        return cf;
    }

    private static GatewayRequest request() {
        return new GatewayRequest("GET", "/echo", GatewayHeaders.empty(), GatewayBody.EMPTY);
    }

    private static GatewayResponse response(int statusCode) {
        return new GatewayResponse(statusCode, GatewayHeaders.empty(), GatewayBody.EMPTY);
    }
}
