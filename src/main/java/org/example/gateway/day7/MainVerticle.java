package org.example.gateway.day7;

import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import org.example.gateway.day7.config.ConfigLoader;
import org.example.gateway.day7.config.GatewayConfig;
import org.example.gateway.day7.config.RouteConfig;
import org.example.gateway.day7.config.UpstreamConfig;
import org.example.gateway.day7.filter.FilterRegistry;
import org.example.gateway.day7.filter.Filters;
import org.example.gateway.day7.proxy.ProxyHandlerFactory;
import org.example.gateway.day7.proxy.UpstreamGroup;
import org.example.gateway.day7.ratelimit.RateLimiter;
import org.example.gateway.day7.reload.ConfigWatcher;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Day 7 실습: day6의 레이트리밋 게이트웨이에 회로차단기·타임아웃·재시도를 얹는다.
 * resilience는 rateLimit/upstreamGroup과 같은 이유로 라우트 전용 필드로 다룬다.
 */
public class MainVerticle extends AbstractVerticle {

    private static final String CONFIG_DIR = "config/day7";
    private static final String ROUTES_CONFIG_PATH = CONFIG_DIR + "/routes.json";
    private static final String UPSTREAMS_CONFIG_PATH = CONFIG_DIR + "/upstreams.json";

    private FilterRegistry registry;
    private ProxyHandlerFactory proxyFactory;
    private Router activeRouter;

    @Override
    public void start(Promise<Void> startPromise) {
        JWTAuth jwtAuth = JwtConfig.createProvider(vertx);
        registry = new FilterRegistry()
            .register("logging", Filters.logging())
            .register("authJwt", Filters.authJwt(jwtAuth));
        proxyFactory = new ProxyHandlerFactory(vertx);

        reload()
            .compose(v -> vertx.createHttpServer()
                .requestHandler(req -> activeRouter.handle(req))
                .listen(8080))
            .onSuccess(server -> {
                System.out.println("Gateway listening on port " + server.actualPort());
                startWatcher();
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }

    private Future<Void> reload() {
        return ConfigLoader.load(vertx, ROUTES_CONFIG_PATH, UPSTREAMS_CONFIG_PATH)
            .map(config -> {
                activeRouter = buildRouter(config);
                System.out.println("[reload] " + config.routes().size() + " routes loaded from " + ROUTES_CONFIG_PATH);
                for (RouteConfig rc : config.routes()) {
                    System.out.println("  " + rc.path() + " -> filters=" + rc.filters()
                        + " upstreamGroup=" + rc.upstreamGroup() + " rateLimit=" + rc.rateLimit()
                        + " resilience=" + rc.resilience());
                }
                return (Void) null;
            });
    }

    private void startWatcher() {
        Path dir = Path.of(CONFIG_DIR);
        ConfigWatcher watcher = new ConfigWatcher(dir, Set.of("routes.json", "upstreams.json"),
            () -> vertx.runOnContext(v -> reload()
                .onFailure(err -> System.err.println("[reload] failed: " + err.getMessage()))));
        watcher.start();
    }

    private Router buildRouter(GatewayConfig config) {
        Router router = Router.router(vertx);
        UpstreamConfig upstreamConfig = config.upstreams();
        Map<String, UpstreamGroup> groups = new HashMap<>();

        for (RouteConfig rc : config.routes()) {
            Route route = router.route(rc.path());
            for (String filterName : rc.filters()) {
                route.handler(registry.get(filterName));
            }
            if (rc.rateLimit() != null) {
                RateLimiter limiter = new RateLimiter(rc.rateLimit().requestsPerSecond(), rc.rateLimit().burstSize());
                route.handler(Filters.rateLimit(limiter));
            }
            if (rc.upstreamGroup() != null) {
                UpstreamGroup group = groups.computeIfAbsent(rc.upstreamGroup(),
                    name -> new UpstreamGroup(upstreamConfig.group(name)));
                // day6와 다른 점: resilience 설정이 있으면 CircuitBreaker로 감싼 핸들러를 쓴다
                // (UpstreamGroup/RateLimiter와 같은 패턴으로, reload()마다 breaker도 새로 만들어져
                // open/half-open 상태와 실패 카운트가 리셋된다 — 회로가 열려있던 중 설정을
                // 고쳐서 반영하면 바로 닫힌 상태로 재출발한다는 뜻).
                if (rc.resilience() != null) {
                    CircuitBreakerOptions options = new CircuitBreakerOptions()
                        .setMaxFailures(rc.resilience().maxFailures())
                        .setTimeout(rc.resilience().timeoutMs())
                        .setResetTimeout(rc.resilience().resetTimeoutMs());
                    CircuitBreaker breaker = CircuitBreaker.create(rc.path() + "-breaker", vertx, options);
                    route.handler(proxyFactory.forResilientGroup(group, breaker, rc.resilience().maxRetries()));
                } else {
                    route.handler(proxyFactory.forGroup(group));
                }
            }
        }
        return router;
    }
}
