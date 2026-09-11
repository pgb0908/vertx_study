package org.example.gateway.day10.engine.connector;

import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.upstream.UpstreamClient;
import org.example.gateway.day10.domain.upstream.resilience.CircuitBreaker;
import org.example.gateway.day10.domain.upstream.resilience.RetryPolicy;
import org.example.gateway.day10.domain.upstream.resilience.TimeoutPolicy;

import java.util.concurrent.CompletableFuture;

/**
 * Retry / CircuitBreaker / Timeout을 Filter가 아니라 실제 업스트림 호출을
 * 감싸는 별도 execution policy로 둔다 (feedback 7/9절). 바깥에서 안쪽 순서는:
 *
 *   Retry(가장 바깥 — 허용된 시도 횟수만큼 루프를 돌며 매번 아래 전체를 다시 호출)
 *     → CircuitBreaker(매 시도마다 — 실패 집계 + 자체 setTimeout으로 타임아웃까지 담당)
 *       → Timeout(각 시도를 한 번 더 감쌀 자리 — 현재는 no-op)
 *         → 실제 UpstreamClient
 *
 * 이 순서가 중요하다: CircuitBreaker가 가장 바깥이면 재시도 루프 전체가 breaker
 * 입장에서 "시도 1번"으로만 집계되어 실패율을 제대로 못 잰다 — day9
 * ProxyHandlerFactory.attempt()가 실제로 검증한 순서(Retry 루프가 매번
 * breaker.execute()를 다시 호출)를 그대로 따른다.
 *
 * TimeoutPolicy는 여전히 no-op(TimeoutPolicy.none())이다 — CircuitBreakerOptions의
 * setTimeout이 이미 매 시도의 타임아웃을 담당하므로, 별도 타임아웃 계층을 하나 더
 * 얹는 대신 CircuitBreaker 없이 타임아웃만 걸고 싶은 라우트가 생길 때를 위한 자리로
 * 남겨둔다(중복 계층을 미리 만들지 않기 위한 선택).
 */
public final class UpstreamExecutor {

    private final UpstreamClient client;
    private final RetryPolicy retryPolicy;
    private final CircuitBreaker circuitBreaker;
    private final TimeoutPolicy timeoutPolicy;

    public UpstreamExecutor(UpstreamClient client, RetryPolicy retryPolicy,
                             CircuitBreaker circuitBreaker, TimeoutPolicy timeoutPolicy) {
        this.client = client;
        this.retryPolicy = retryPolicy;
        this.circuitBreaker = circuitBreaker;
        this.timeoutPolicy = timeoutPolicy;
    }

    public CompletableFuture<GatewayResponse> execute(Endpoint endpoint, GatewayRequest request) {
        UpstreamClient withTimeout = (ep, req) -> timeoutPolicy.execute(ep, req, client);
        UpstreamClient withCircuitBreaker = (ep, req) -> circuitBreaker.execute(ep, req, withTimeout);
        return retryPolicy.execute(endpoint, request, withCircuitBreaker);
    }
}
