package org.example.gateway.day10.engine.route;

import java.util.List;
import java.util.Optional;

/**
 * 컴파일된 라우트 목록에서 경로+메서드를 매칭한다. v1은 정확 일치만 지원한다 —
 * 라우트가 많아지고 prefix/wildcard 매칭이 필요해지는 시점에(PLAN.md 2차 항목)
 * Vert.x Router로 매칭을 위임하는 RouteMatcher로 교체한다(day9 GatewayRouterBuilder와
 * 동일한 이유: 매칭 로직 재구현은 실제 배포 동작과 괴리될 위험이 있다).
 *
 * path가 같고 method만 다른 Router 리소스가 여러 개 있을 수 있어(예: GET /orders,
 * POST /orders가 별도 Router) method까지 같이 비교한다. path는 맞는데 method가
 * 안 맞으면(405에 해당하는 상황) 지금은 405가 아니라 404로 처리한다 — "path는
 * 맞는데 method가 틀렸다"를 구분하려면 매칭을 한 번 더(경로만) 해야 하는데, v1
 * 범위에서는 그 구분의 실익이 크지 않아 미룬다.
 */
public final class RouteTable {

    private final List<RuntimeRoute> routes;

    public RouteTable(List<RuntimeRoute> routes) {
        this.routes = routes;
    }

    /**
     * @param uri GatewayRequest.uri() 그대로 — 쿼리스트링이 붙어 있을 수 있다
     *            (예: {@code /echo?delayMs=1000}). 라우트 경로는 쿼리스트링과
     *            무관해야 하므로 매칭 전에 잘라낸다.
     */
    public Optional<RuntimeRoute> match(String method, String uri) {
        String path = uri.indexOf('?') >= 0 ? uri.substring(0, uri.indexOf('?')) : uri;
        return routes.stream()
            .filter(r -> r.route().path().equals(path) && r.route().method().equalsIgnoreCase(method))
            .findFirst();
    }
}
