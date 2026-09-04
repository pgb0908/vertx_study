package org.example.gateway.day10.engine;

import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.policy.GatewayPolicy;
import org.example.gateway.day10.domain.upstream.UpstreamClient;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * day9의 Vert.x Router Handler Chain을 대체하는 Gateway 고유의 실행 모델
 * (Arch.md 7절). 이 클래스만 읽어도 "Request Policy -> Upstream -> Response
 * Policy" 순서가 그대로 보인다 — Router 등록 순서를 추적할 필요가 없다.
 *
 * Vert.x Future가 아니라 java.util.concurrent.CompletableFuture를 쓴다:
 * Arch.md 7절 예시는 io.vertx.core.Future를 쓰지만, 그러면 원칙 1(Vert.x 타입이
 * domain/engine에 안 보여야 함)을 문서 자신이 어기게 된다. runtime-vertx 경계
 * 에서만 Vert.x Future <-> CompletableFuture를 변환한다.
 */
public final class GatewayEngine {

    private final List<GatewayPolicy> policies;
    private final UpstreamClient upstreamClient;

    public GatewayEngine(List<GatewayPolicy> policies, UpstreamClient upstreamClient) {
        this.policies = policies;
        this.upstreamClient = upstreamClient;
    }

    public CompletableFuture<GatewayResponse> execute(GatewayExchange exchange) {
        System.out.println("[engine] execute() start: " + exchange.request().method() + " " + exchange.request().uri()
            + " route=" + exchange.route().path() + " policies=" + policies.size());
        return runBefore(exchange, 0)
            .thenCompose(v -> {
                System.out.println("[engine] before-chain done -> dispatching to upstream " + exchange.route().endpoint());
                return upstreamClient.execute(exchange.route().endpoint(), exchange.request());
            })
            .thenCompose(response -> {
                System.out.println("[engine] upstream responded status=" + response.statusCode() + " -> running after-chain");
                exchange.response(response);
                return runAfter(exchange, response, policies.size() - 1);
            })
            .whenComplete((response, err) -> {
                if (err != null) {
                    System.out.println("[engine] execute() failed: " + err);
                } else {
                    System.out.println("[engine] execute() complete -> status=" + response.statusCode());
                }
            });
    }

    /** 요청 정책은 등록 순서대로 (1 -> 2 -> 3) 실행한다. */
    private CompletableFuture<Void> runBefore(GatewayExchange exchange, int index) {
        if (index >= policies.size()) {
            return CompletableFuture.completedFuture(null);
        }
        GatewayPolicy policy = policies.get(index);
        System.out.println("[engine]   before-policy[" + index + "] " + policy.getClass().getSimpleName());
        return policy.before(exchange)
            .thenCompose(v -> runBefore(exchange, index + 1));
    }

    /** 응답 정책은 등록 역순으로 (3 -> 2 -> 1) 실행한다 — 요청 1->2->3, 응답 3->2->1 어니언(onion) 모델. */
    private CompletableFuture<GatewayResponse> runAfter(GatewayExchange exchange, GatewayResponse response, int index) {
        if (index < 0) {
            return CompletableFuture.completedFuture(response);
        }
        GatewayPolicy policy = policies.get(index);
        System.out.println("[engine]   after-policy[" + index + "] " + policy.getClass().getSimpleName());
        return policy.after(exchange, response)
            .thenCompose(next -> runAfter(exchange, next, index - 1));
    }
}
