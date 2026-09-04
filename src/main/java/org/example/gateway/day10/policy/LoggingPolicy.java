package org.example.gateway.day10.policy;

import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.policy.GatewayPolicy;

import java.util.concurrent.CompletableFuture;

/**
 * Vert.x를 전혀 모르는 첫 GatewayPolicy 구현체 — RoutingContext 대신
 * GatewayExchange만 보고 로깅한다.
 */
public final class LoggingPolicy implements GatewayPolicy {

    @Override
    public CompletableFuture<Void> before(GatewayExchange exchange) {
        System.out.println("[request] " + exchange.request().method() + " " + exchange.request().uri());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<GatewayResponse> after(GatewayExchange exchange, GatewayResponse response) {
        System.out.println("[response] " + exchange.request().method() + " " + exchange.request().uri()
            + " -> " + response.statusCode());
        return CompletableFuture.completedFuture(response);
    }
}
