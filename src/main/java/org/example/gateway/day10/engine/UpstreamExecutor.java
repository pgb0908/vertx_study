package org.example.gateway.day10.engine;

import org.example.gateway.day10.domain.upstream.CircuitBreaker;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.upstream.RetryPolicy;
import org.example.gateway.day10.domain.upstream.TimeoutPolicy;
import org.example.gateway.day10.domain.upstream.UpstreamClient;

import java.util.concurrent.CompletableFuture;

/**
 * Retry / CircuitBreaker / Timeout을 Filter가 아니라 실제 업스트림 호출을
 * 감싸는 별도 execution policy로 둔다 (feedback 7/9절). 바깥에서 안쪽 순서는:
 *
 *   CircuitBreaker(가장 바깥 — open이면 retry조차 시도하지 않고 즉시 실패)
 *     → Retry(허용된 시도 횟수만큼 재시도)
 *       → Timeout(각 개별 시도를 감쌈)
 *         → 실제 UpstreamClient
 *
 * v1은 세 정책 모두 no-op(RetryPolicy.none()/CircuitBreaker.disabled()/
 * TimeoutPolicy.none())으로 구성돼 있어 지금은 client.execute()를 그대로
 * 호출하는 것과 동작이 같다 — 인터페이스 자리만 잡아둔 상태(PLAN.md 2차 항목 1).
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
        UpstreamClient withRetry = (ep, req) -> retryPolicy.execute(ep, req, withTimeout);
        return circuitBreaker.execute(endpoint, request, withRetry);
    }
}
