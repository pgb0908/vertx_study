package org.example.gateway.day10.domain.policy;

import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayResponse;

import java.util.concurrent.CompletableFuture;

/**
 * Vert.x Handler<RoutingContext>가 아니라 exchange를 직접 받는 Gateway 고유의
 * 실행 모델 (Arch.md 10절). before는 업스트림 호출 전, after는 업스트림 응답을
 * 클라이언트로 흘려보내기 전에 실행된다.
 */
public interface GatewayPolicy {

    default CompletableFuture<Void> before(GatewayExchange exchange) {
        return CompletableFuture.completedFuture(null);
    }

    default CompletableFuture<GatewayResponse> after(GatewayExchange exchange, GatewayResponse response) {
        return CompletableFuture.completedFuture(response);
    }
}
