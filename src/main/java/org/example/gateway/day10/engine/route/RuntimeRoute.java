package org.example.gateway.day10.engine.route;

import org.example.gateway.day10.domain.route.GatewayRoute;
import org.example.gateway.day10.engine.FilterChain;
import org.example.gateway.day10.engine.connector.ConnectorSelector;

/**
 * GatewayRoute(config)를 실행 가능한 형태로 컴파일한 결과 (feedback 7/12절).
 * 라우트마다 FilterChain을 따로 가진다 — 이전처럼 GatewayEngine 하나가 전체
 * 라우트에 공통 필터 목록을 강제하지 않는다. 목적지 선택(하나 이상의 Connector,
 * 가중치 분산 포함)은 ConnectorSelector가 담당한다.
 */
public final class RuntimeRoute {

    private final GatewayRoute route;
    private final FilterChain filterChain;
    private final ConnectorSelector connectorSelector;

    public RuntimeRoute(GatewayRoute route, ConnectorSelector connectorSelector) {
        this.route = route;
        this.filterChain = new FilterChain(route.filters());
        this.connectorSelector = connectorSelector;
    }

    public GatewayRoute route() {
        return route;
    }

    public FilterChain filterChain() {
        return filterChain;
    }

    public ConnectorSelector connectorSelector() {
        return connectorSelector;
    }
}
