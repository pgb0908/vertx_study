package org.example.gateway.day10.runtime.vertx.resilience;

import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Vertx;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.UpstreamClient;
import org.example.gateway.day10.domain.upstream.resilience.CircuitBreaker;

import java.util.concurrent.CompletableFuture;

/**
 * domain.upstream.CircuitBreaker의 실제 구현체. 재발명하지 않고 검증된
 * io.vertx.circuitbreaker.CircuitBreaker를 그대로 래핑한다(PLAN.md 2차 항목 1,
 * day9 GatewayRouteResolver/ProxyHandlerFactory와 동일 패턴). CircuitBreakerOptions의
 * setTimeout이 곧 이 라우트의 타임아웃 구현이다 — 별도 TimeoutPolicy 구현 없이도
 * "백엔드가 응답 안 하면 무한 대기" 문제가 여기서 함께 해결된다.
 */
public final class VertxCircuitBreaker implements CircuitBreaker {

    private final io.vertx.circuitbreaker.CircuitBreaker breaker;

    public VertxCircuitBreaker(Vertx vertx, String name, int maxFailures, long timeoutMs, long resetTimeoutMs) {
        CircuitBreakerOptions options = new CircuitBreakerOptions()
            .setMaxFailures(maxFailures)
            .setTimeout(timeoutMs)
            .setResetTimeout(resetTimeoutMs);
        this.breaker = io.vertx.circuitbreaker.CircuitBreaker.create(name, vertx, options);
    }

    @Override
    public CompletableFuture<GatewayResponse> execute(Endpoint endpoint, GatewayRequest request, UpstreamClient client) {
        CompletableFuture<GatewayResponse> result = new CompletableFuture<>();

        breaker.<GatewayResponse>execute(promise ->
            client.execute(endpoint, request).whenComplete((response, err) -> {
                if (err != null) {
                    promise.tryFail(err);
                } else if (response.statusCode() >= 500) {
                    // 5xx도 breaker 입장에서는 실패다 — 안 그러면 업스트림이 계속 500을
                    // 내려줘도 "매번 성공적으로 500을 전달"한 셈이 되어 회로차단기가
                    // 무용지물이 된다(day9 ProxyHandlerFactory와 동일 이유).
                    promise.tryFail(new RuntimeException("upstream returned " + response.statusCode()));
                } else {
                    promise.tryComplete(response);
                }
            })
        ).onSuccess(result::complete).onFailure(result::completeExceptionally);

        return result;
    }
}
