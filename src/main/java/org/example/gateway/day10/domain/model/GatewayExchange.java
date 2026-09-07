package org.example.gateway.day10.domain.model;

import org.example.gateway.day10.domain.route.GatewayRoute;

import java.util.UUID;

/**
 * Gateway가 처리 중인 거래 한 건의 실행 상태. Arch.md 6절의 GatewayExchange ≠
 * RoutingContext 원칙대로, Vert.x RoutingContext 대신 이 객체가 상태를 들고
 * GatewayEngine의 파이프라인 단계를 거치며 route/response가 채워진다.
 *
 * requestId는 비동기 콜백이 스레드 경계를 넘어도 안전하게 추적하기 위해
 * MDC 대신 exchange 자체에 들고 다닌다.
 */
public final class GatewayExchange {

    private final String requestId;
    private final GatewayRequest request;
    private final GatewayRoute route;
    private GatewayResponse response;

    public GatewayExchange(GatewayRequest request, GatewayRoute route) {
        this.requestId = UUID.randomUUID().toString().substring(0, 8);
        this.request = request;
        this.route = route;
    }

    public String requestId() {
        return requestId;
    }

    public GatewayRequest request() {
        return request;
    }

    public GatewayRoute route() {
        return route;
    }

    public GatewayResponse response() {
        return response;
    }

    public void response(GatewayResponse response) {
        this.response = response;
    }
}
