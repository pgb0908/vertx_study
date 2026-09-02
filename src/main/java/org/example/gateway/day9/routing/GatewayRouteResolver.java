package org.example.gateway.day9.routing;

import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Vertx;
import org.example.gateway.day9.config.GatewayConfig;
import org.example.gateway.day9.config.RouteConfig;
import org.example.gateway.day9.config.UpstreamConfig;
import org.example.gateway.day9.filter.FilterRegistry;
import org.example.gateway.day9.filter.GatewayFilter;
import org.example.gateway.day9.proxy.UpstreamGroup;
import org.example.gateway.day9.filter.ratelimit.RateLimiter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "설정 이름 → 실제 객체" 해석만 담당한다. Vert.x의 Router/Route는 전혀 만지지 않는다
 * (Vertx 자체는 CircuitBreaker.create(name, vertx, options)가 요구해서 참조는 하지만,
 * HTTP 서버나 라우팅과는 무관). day6/day7에서 buildRouter() 안에 있던 "라우트마다
 * RateLimiter/CircuitBreaker/UpstreamGroup을 새로 만들거나 재사용하는" 로직이 여기로
 * 옮겨왔다 — upstreamGroup 이름별로 인스턴스를 공유하는 캐싱(groups 맵)도 그대로다.
 */
public class GatewayRouteResolver {

    private final FilterRegistry registry;
    private final Vertx vertx;

    public GatewayRouteResolver(FilterRegistry registry, Vertx vertx) {
        this.registry = registry;
        this.vertx = vertx;
    }

    public List<GatewayRoute> resolve(GatewayConfig config) {
        UpstreamConfig upstreamConfig = config.upstreams();
        Map<String, UpstreamGroup> groups = new HashMap<>();
        List<GatewayRoute> routes = new ArrayList<>();

        for (RouteConfig rc : config.routes()) {
            List<GatewayFilter> filters = rc.filters().stream().map(registry::get).toList();

            RateLimiter rateLimiter = rc.rateLimit() == null ? null
                : new RateLimiter(rc.rateLimit().requestsPerSecond(), rc.rateLimit().burstSize());

            CircuitBreaker circuitBreaker = null;
            int maxRetries = 0;
            if (rc.resilience() != null) {
                CircuitBreakerOptions options = new CircuitBreakerOptions()
                    .setMaxFailures(rc.resilience().maxFailures())
                    .setTimeout(rc.resilience().timeoutMs())
                    .setResetTimeout(rc.resilience().resetTimeoutMs());
                circuitBreaker = CircuitBreaker.create(rc.path() + "-breaker", vertx, options);
                maxRetries = rc.resilience().maxRetries();
            }

            UpstreamGroup group = rc.upstreamGroup() == null ? null
                : groups.computeIfAbsent(rc.upstreamGroup(), name -> new UpstreamGroup(upstreamConfig.group(name)));

            routes.add(new GatewayRoute(rc.path(), filters, rateLimiter, circuitBreaker, maxRetries, group));
        }
        return routes;
    }
}
