package org.example.gateway.day6;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import org.example.gateway.day6.config.ConfigLoader;
import org.example.gateway.day6.config.GatewayConfig;
import org.example.gateway.day6.config.RouteConfig;
import org.example.gateway.day6.config.UpstreamConfig;
import org.example.gateway.day6.filter.FilterRegistry;
import org.example.gateway.day6.filter.Filters;
import org.example.gateway.day6.proxy.ProxyHandlerFactory;
import org.example.gateway.day6.proxy.UpstreamGroup;
import org.example.gateway.day6.ratelimit.RateLimiter;
import org.example.gateway.day6.reload.ConfigWatcher;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Day 6 실습: day5의 JWT 인증 게이트웨이에 레이트리밋 필터를 얹는다.
 * rateLimit은 upstreamGroup과 같은 이유로 filters 이름 목록이 아니라 라우트별
 * 전용 필드로 다룬다 — 라우트마다 파라미터(초당 요청 수, 버스트)가 다르기 때문.
 */
public class MainVerticle extends AbstractVerticle {

    private static final String CONFIG_DIR = "config/day6";
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
                        + " upstreamGroup=" + rc.upstreamGroup() + " rateLimit=" + rc.rateLimit());
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
            // day5와 다른 점: rateLimit 설정이 있으면, 그 라우트 전용 RateLimiter를
            // 새로 만들어서 필터 체인에 끼워 넣는다 (upstreamGroup 처리와 같은 패턴).
            if (rc.rateLimit() != null) {
                RateLimiter limiter = new RateLimiter(rc.rateLimit().requestsPerSecond(), rc.rateLimit().burstSize());
                route.handler(Filters.rateLimit(limiter));
            }
            if (rc.upstreamGroup() != null) {
                UpstreamGroup group = groups.computeIfAbsent(rc.upstreamGroup(),
                    name -> new UpstreamGroup(upstreamConfig.group(name)));
                route.handler(proxyFactory.forGroup(group));
            }
        }
        return router;
    }
}
