package org.example.gateway.day10.domain.upstream;

import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;

import java.util.concurrent.CompletableFuture;

/**
 * 타임아웃을 Filter가 아니라 execution policy로 분리한다 (feedback 7절).
 * v1은 no-op(none()) 구현만 제공한다.
 */
public interface TimeoutPolicy {

    CompletableFuture<GatewayResponse> execute(Endpoint endpoint, GatewayRequest request, UpstreamClient client);

    static TimeoutPolicy none() {
        return (endpoint, request, client) -> client.execute(endpoint, request);
    }
}
