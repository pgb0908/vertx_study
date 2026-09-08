package org.example.gateway.day10.domain.upstream;

import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;

import java.util.concurrent.CompletableFuture;

/**
 * 재시도를 Filter가 아니라 execution policy로 분리한다 (feedback 7절) — 재시도는
 * "요청/응답 내용을 다루는 정책"이 아니라 "업스트림 호출 자체를 몇 번 어떻게
 * 시도할지"를 다루는 문제라서 Filter 체인과 층이 다르다.
 *
 * 구현체는 client.execute(endpoint, request)를 원하는 만큼 다시 호출하면 된다.
 * v1은 no-op(none()) 구현만 제공 — 실제 재시도 로직(멱등성 판단, 바디 replay 가능
 * 여부 등)은 다음 단계에서 채운다.
 */
public interface RetryPolicy {

    CompletableFuture<GatewayResponse> execute(Endpoint endpoint, GatewayRequest request, UpstreamClient client);

    static RetryPolicy none() {
        return (endpoint, request, client) -> client.execute(endpoint, request);
    }
}
