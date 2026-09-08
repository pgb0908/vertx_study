package org.example.gateway.day10.engine;

import org.example.gateway.day10.domain.route.GatewayRoute;
import org.example.gateway.day10.domain.upstream.LoadBalancer;

/**
 * GatewayRoute(config)를 실행 가능한 형태로 컴파일한 결과 (feedback 7/12절).
 * 라우트마다 FilterChain을 따로 가진다 — 이전처럼 GatewayEngine 하나가 전체
 * 라우트에 공통 필터 목록을 강제하지 않는다.
 */
public final class RuntimeRoute {

    private final GatewayRoute route;
    private final FilterChain filterChain;
    private final EndpointSelector endpointSelector;

    public RuntimeRoute(GatewayRoute route, LoadBalancer loadBalancer) {
        this.route = route;
        this.filterChain = new FilterChain(route.filters());
        this.endpointSelector = new EndpointSelector(loadBalancer);
    }

    public GatewayRoute route() {
        return route;
    }

    public FilterChain filterChain() {
        return filterChain;
    }

    public EndpointSelector endpointSelector() {
        return endpointSelector;
    }
}
