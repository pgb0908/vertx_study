package org.example.gateway.day10.config;

import io.vertx.core.Vertx;
import org.example.gateway.day10.config.resource.ConnectorConfig;
import org.example.gateway.day10.config.resource.RouterConfig;
import org.example.gateway.day10.domain.filter.Filter;
import org.example.gateway.day10.domain.route.GatewayRoute;
import org.example.gateway.day10.domain.upstream.EgressGroup;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.UpstreamClient;
import org.example.gateway.day10.domain.upstream.balance.RoundRobinLoadBalancer;
import org.example.gateway.day10.domain.upstream.resilience.RetryPolicy;
import org.example.gateway.day10.domain.upstream.resilience.TimeoutPolicy;
import org.example.gateway.day10.engine.connector.ConnectorSelector;
import org.example.gateway.day10.engine.connector.ConnectorSelector.WeightedConnector;
import org.example.gateway.day10.engine.connector.RuntimeConnector;
import org.example.gateway.day10.engine.connector.UpstreamExecutor;
import org.example.gateway.day10.engine.route.RouteTable;
import org.example.gateway.day10.engine.route.RuntimeRoute;
import org.example.gateway.day10.engine.route.RuntimeSnapshot;
import org.example.gateway.day10.filter.LoggingFilter;
import org.example.gateway.day10.runtime.vertx.VertxUpstreamClient;
import org.example.gateway.day10.runtime.vertx.resilience.VertxCircuitBreaker;
import org.example.gateway.day10.runtime.vertx.resilience.VertxRetryPolicy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GatewayConfig(파싱된 리소스) -> RuntimeSnapshot(실행 가능한 형태) 컴파일.
 * Arch.md 12/13절의 "Config World → compile → Runtime World" 경계가 정확히
 * 여기다 — 참조(Router.destinations[].destinationRef -> Connector.id)를 이
 * 단계에서 전부 해석해서 실제 객체 포인터로 바꿔둔다. 요청 처리 시점에는 이름으로
 * 다시 찾을 일이 없다.
 *
 * 이 클래스는 runtime.vertx의 VertxCircuitBreaker/VertxRetryPolicy/VertxUpstreamClient를
 * 직접 참조한다 — Vert.x 구현체를 실제로 조립해야 하는 지점이라 config 레이어는
 * 이 경계를 알아도 된다(GatewayEngine 자신은 여전히 모른다 — Arch.md 원칙 1).
 */
public final class RuntimeSnapshotCompiler {

    private RuntimeSnapshotCompiler() {
    }

    public static RuntimeSnapshot compile(Vertx vertx, GatewayConfig config) {
        UpstreamClient sharedClient = new VertxUpstreamClient(vertx);

        Map<String, RuntimeConnector> connectorsById = new HashMap<>();
        for (ConnectorConfig connectorConfig : config.connectors()) {
            connectorsById.put(connectorConfig.id(), compileConnector(vertx, sharedClient, connectorConfig));
        }

        List<RuntimeRoute> routes = config.routers().stream()
            .map(routerConfig -> compileRoute(routerConfig, connectorsById))
            .toList();

        return new RuntimeSnapshot(new RouteTable(routes));
    }

    /** Connector.md 한 리소스 = endpoint pool(EgressGroup) + 그 Connector 전용 resilience. */
    private static RuntimeConnector compileConnector(Vertx vertx, UpstreamClient sharedClient, ConnectorConfig cc) {
        EgressGroup egressGroup = new EgressGroup(cc.id(),
            cc.loadBalancing().targets().stream()
                .map(t -> new Endpoint(t.host(), t.port()))
                .toList());

        VertxCircuitBreaker circuitBreaker = new VertxCircuitBreaker(vertx, cc.id() + "-breaker",
            cc.resilience().maxFailures(), cc.resilience().timeoutMs(), cc.resilience().resetTimeoutMs());
        RetryPolicy retryPolicy = new VertxRetryPolicy(cc.resilience().maxRetries());
        UpstreamExecutor upstreamExecutor = new UpstreamExecutor(sharedClient, retryPolicy, circuitBreaker, TimeoutPolicy.none());

        return new RuntimeConnector(cc.id(), egressGroup, new RoundRobinLoadBalancer(),
            upstreamExecutor, cc.method(), cc.proxyPath());
    }

    /** Router.md 한 리소스 = path+method 매칭 규칙 + (가중치 분산 가능한) 목적지 목록. */
    private static RuntimeRoute compileRoute(RouterConfig rc, Map<String, RuntimeConnector> connectorsById) {
        List<WeightedConnector> destinations = rc.destinations().stream()
            .map(d -> {
                RuntimeConnector connector = connectorsById.get(d.connectorId());
                if (connector == null) {
                    throw new GatewayConfigException("Router '" + rc.id() + "'가 참조하는 Connector '"
                        + d.connectorId() + "'를 찾을 수 없습니다 (connectors/ 디렉토리 확인)");
                }
                return new WeightedConnector(d.weight(), connector);
            })
            .toList();

        // Policy_auth_apikey 등 Policy 리소스 연동 전까지는 기본 로깅 필터만 붙인다.
        List<Filter> filters = List.of(new LoggingFilter());
        GatewayRoute route = new GatewayRoute(rc.path(), rc.method(), filters);
        return new RuntimeRoute(route, new ConnectorSelector(destinations));
    }
}
