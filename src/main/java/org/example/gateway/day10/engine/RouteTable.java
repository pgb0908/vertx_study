package org.example.gateway.day10.engine;

import java.util.List;
import java.util.Optional;

/**
 * 컴파일된 라우트 목록에서 경로를 매칭한다. v1은 정확 일치만 지원한다 —
 * 라우트가 여러 개가 되고 prefix/wildcard 매칭이 필요해지는 시점에
 * (PLAN.md 2차 항목 2) Vert.x Router로 매칭을 위임하는 RouteMatcher로 교체한다
 * (day9 GatewayRouterBuilder와 동일한 이유: 매칭 로직 재구현은 실제 배포 동작과
 * 괴리될 위험이 있다).
 */
public final class RouteTable {

    private final List<RuntimeRoute> routes;

    public RouteTable(List<RuntimeRoute> routes) {
        this.routes = routes;
    }

    public Optional<RuntimeRoute> match(String path) {
        return routes.stream()
            .filter(r -> r.route().path().equals(path))
            .findFirst();
    }
}
