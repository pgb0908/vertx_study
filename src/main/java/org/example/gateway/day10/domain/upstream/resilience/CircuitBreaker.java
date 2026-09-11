package org.example.gateway.day10.domain.upstream.resilience;

import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.UpstreamClient;

import java.util.concurrent.CompletableFuture;

/**
 * 회로차단을 Filter가 아니라 execution policy로 분리한다 (feedback 7/9절).
 * PLAN.md 2차 목록에 합의된 대로, 실제 구현체는 도메인이 자체 재발명하지 않고
 * runtime.vertx에서 io.vertx.circuitbreaker.CircuitBreaker를 래핑할 예정이다 —
 * 이번 단계는 그 자리를 잡아두는 인터페이스와 no-op(disabled()) 구현까지만.
 */
public interface CircuitBreaker {

    CompletableFuture<GatewayResponse> execute(Endpoint endpoint, GatewayRequest request, UpstreamClient client);

    static CircuitBreaker disabled() {
        return (endpoint, request, client) -> client.execute(endpoint, request);
    }
}
