package org.example.gateway.day9.routing;

import io.vertx.circuitbreaker.CircuitBreaker;
import org.example.gateway.day9.filter.GatewayFilter;
import org.example.gateway.day9.filter.RequestBodyFilter;
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
    /**
     * improve-codebase-architecture 세션에서 합의된 단일 진실 공급원. 이전에는 "이 라우트가
     * 요청 바디를 버퍼링하는지"를 GatewayRouterBuilder(instanceof 체크)와
     * ProxyHandlerFactory(ctx.body().available() 추측)가 각자 따로 판단했는데, 그 불일치
     * 때문에 실제로 NPE와 요청 hang 버그를 겪었다. filters()만 보고 결정되는 순수한
     * 사실이라 GatewayRoute의 파생 메서드로 두고, 저장 필드로 따로 두지 않는다 — filters()와
     * 어긋날 수 있는 "두 번째 진실"을 만들지 않기 위해서다.
     */
    public boolean bufferedRequestBody() {
        return filters.stream().anyMatch(f -> f instanceof RequestBodyFilter);
    }
}
