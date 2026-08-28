package org.example.gateway.day5;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import org.example.gateway.day5.config.ConfigLoader;
import org.example.gateway.day5.config.GatewayConfig;
import org.example.gateway.day5.config.RouteConfig;
import org.example.gateway.day5.config.UpstreamConfig;
import org.example.gateway.day5.filter.FilterRegistry;
import org.example.gateway.day5.filter.Filters;
import org.example.gateway.day5.proxy.ProxyHandlerFactory;
import org.example.gateway.day5.proxy.UpstreamGroup;
import org.example.gateway.day5.reload.ConfigWatcher;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Day 5 실습: day4의 hot-reload 가능한 게이트웨이에 실제 JWT 인증 필터를 얹는다.
 * day4까지의 auth()는 헤더 존재 여부만 보는 가짜 필터였지만, authJwt()는
 * vertx-auth-jwt로 서명/만료를 실제로 검증한다.
 */
public class MainVerticle extends AbstractVerticle {

    private static final String CONFIG_DIR = "config/day5";
    private static final String ROUTES_CONFIG_PATH = CONFIG_DIR + "/routes.json";
    private static final String UPSTREAMS_CONFIG_PATH = CONFIG_DIR + "/upstreams.json";

    // day4와 다른 점: registry가 필드 선언부에서 바로 초기화되지 않고 start()에서 만들어진다.
    // JWTAuth 생성에 vertx가 필요한데, 필드 초기화 시점엔 아직 vertx가 주입되기 전이라서.
    private FilterRegistry registry;
    private ProxyHandlerFactory proxyFactory;
    private Router activeRouter;

    @Override
    public void start(Promise<Void> startPromise) {
        // day4와 다른 점: JWTAuth 프로바이더를 만들고, "auth"(가짜 헤더체크) 대신
        // "authJwt"(실제 서명검증) 이름으로 등록한다.
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
                        + " upstreamGroup=" + rc.upstreamGroup());
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
            if (rc.upstreamGroup() != null) {
                UpstreamGroup group = groups.computeIfAbsent(rc.upstreamGroup(),
                    name -> new UpstreamGroup(upstreamConfig.group(name)));
                route.handler(proxyFactory.forGroup(group));
            }
        }
        return router;
    }
}
