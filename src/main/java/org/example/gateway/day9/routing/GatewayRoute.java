package org.example.gateway.day9.routing;

import io.vertx.circuitbreaker.CircuitBreaker;
import org.example.gateway.day9.filter.GatewayFilter;
import org.example.gateway.day9.proxy.UpstreamGroup;
import org.example.gateway.day9.filter.ratelimit.RateLimiter;

import java.util.List;

/**
 * grill-me 세션에서 합의된 순수 도메인 타입. RouteConfig(설정 파일의 "이름"들)와 달리,
 * 여기 담긴 것들은 이미 다 실제 객체로 해석된 상태다 — filters는 registry.get()까지
 * 끝난 GatewayFilter 인스턴스, rateLimiter/circuitBreaker/upstreamGroup도 실제로 생성된
 * 객체(없으면 null). Vert.x 타입(Router, Route, RoutingContext)을 전혀 참조하지 않으므로,
 * "이 설정이 어떤 GatewayRoute들을 만들어내는지"는 HTTP 서버 없이도 순수 단위테스트가
 * 가능하다 — 다만 실제 경로 매칭 자체는 여전히 Vert.x Router에 위임한다(GatewayRouterBuilder).
 */
public record GatewayRoute(
    String path,
    List<GatewayFilter> filters,
    RateLimiter rateLimiter,
    CircuitBreaker circuitBreaker,
    int maxRetries,
    UpstreamGroup upstreamGroup
) {
}
