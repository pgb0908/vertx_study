package org.example.gateway.day10.domain.model;

import org.example.gateway.day10.domain.route.GatewayRoute;

import java.util.UUID;

/**
 * Gateway가 처리 중인 거래 한 건의 실행 상태. Arch.md 6절의 GatewayExchange ≠
 * RoutingContext 원칙대로, Vert.x RoutingContext 대신 이 객체가 상태를 들고
 * GatewayEngine의 파이프라인 단계를 거치며 route/response가 채워진다.
 *
 * request/response 자체는 immutable value object이지만(feedback 4절), 파이프라인이
 * 진행되며 "지금 어떤 값을 들고 있는지"는 바뀐다 — 그 진행 상태를 4단계로 구분해서
 * 들고 있는다(feedback 5절):
 *
 *   originalRequest  = 클라이언트가 실제로 보낸 요청, 파이프라인 내내 불변
 *   request          = 지금까지의 downstream FilterChain을 거친 "현재" 요청
 *   upstreamResponse = Backend가 실제로 보낸 응답(원본), upstream 호출 직후 1회 확정
 *   response         = upstream FilterChain을 거친 "현재/최종" 응답
 *
 * request/response를 이렇게 나누지 않으면, 정책이 응답을 변형해도 exchange는 여전히
 * 변형 전 값을 들고 있는 채로 남는 문제가 생긴다 — 필터 체인 파라미터로만 흐르는
 * "진짜 값"과 exchange가 보여주는 값이 어긋나기 때문이다.
 *
 * requestId는 비동기 콜백이 스레드 경계를 넘어도 안전하게 추적하기 위해
 * MDC 대신 exchange 자체에 들고 다닌다.
 */
public final class GatewayExchange {

    private final String requestId;
    private final GatewayRequest originalRequest;
    private final GatewayRoute route;

    private GatewayRequest request;
    private GatewayResponse upstreamResponse;
    private GatewayResponse response;

    public GatewayExchange(GatewayRequest request, GatewayRoute route) {
        this.requestId = UUID.randomUUID().toString().substring(0, 8);
        this.originalRequest = request;
        this.request = request;
        this.route = route;
    }

    public String requestId() {
        return requestId;
    }

    /** 클라이언트가 실제로 보낸 요청 — 파이프라인 내내 절대 바뀌지 않는다. */
    public GatewayRequest originalRequest() {
        return originalRequest;
    }

    /** 지금까지의 downstream FilterChain을 거친 현재 요청. */
    public GatewayRequest request() {
        return request;
    }

    public void request(GatewayRequest request) {
        this.request = request;
    }

    public GatewayRoute route() {
        return route;
    }

    /** Backend가 실제로 보낸 원본 응답 — upstream 호출 직후 1회만 설정된다. */
    public GatewayResponse upstreamResponse() {
        return upstreamResponse;
    }

    public void upstreamResponse(GatewayResponse upstreamResponse) {
        this.upstreamResponse = upstreamResponse;
    }

    /** upstream FilterChain을 거친 현재/최종 응답. */
    public GatewayResponse response() {
        return response;
    }

    public void response(GatewayResponse response) {
        this.response = response;
    }
}
