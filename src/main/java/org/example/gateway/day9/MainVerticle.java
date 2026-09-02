package org.example.gateway.day9;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import org.example.gateway.day9.config.ConfigLoader;
import org.example.gateway.day9.config.GatewayConfig;
import org.example.gateway.day9.filter.FilterRegistry;
import org.example.gateway.day9.filter.auth.AuthJwtFilter;
import org.example.gateway.day9.filter.auth.JwtConfig;
import org.example.gateway.day9.filter.bodylogger.BodyLoggerFilter;
import org.example.gateway.day9.filter.logging.LoggingFilter;
import org.example.gateway.day9.proxy.ProxyHandlerFactory;
import org.example.gateway.day9.reload.ConfigWatcher;
import org.example.gateway.day9.routing.GatewayRoute;
import org.example.gateway.day9.routing.GatewayRouteResolver;
import org.example.gateway.day9.routing.GatewayRouterBuilder;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * grill-me 세션에서 합의된 리팩터링: buildRouter()가 하던 "설정 이름 → 객체 해석"과
 * "객체 → Vert.x Router 조립"을 GatewayRouteResolver/GatewayRouterBuilder로 분리했다.
 * 이 클래스는 이제 그 둘을 순서대로 부르는 얇은 오케스트레이션만 담당한다 — 필터/라우트
 * 구체 로직은 전부 filter/routing 패키지로 옮겨갔다.
 */
public class MainVerticle extends AbstractVerticle {

    private static final String CONFIG_DIR = "config/day9";
    private static final String ROUTES_CONFIG_PATH = CONFIG_DIR + "/routes.json";
    private static final String UPSTREAMS_CONFIG_PATH = CONFIG_DIR + "/upstreams.json";
    private static final String TLS_CERT_PATH = CONFIG_DIR + "/tls/cert.pem";
    private static final String TLS_KEY_PATH = CONFIG_DIR + "/tls/key.pem";

    private FilterRegistry registry;
    private ProxyHandlerFactory proxyFactory;
    private GatewayRouteResolver routeResolver;
    private Router activeRouter;

    @Override
    public void start(Promise<Void> startPromise) {
        JWTAuth jwtAuth = JwtConfig.createProvider(vertx);
        registry = new FilterRegistry()
            .register("logging", new LoggingFilter())
            .register("authJwt", new AuthJwtFilter(jwtAuth))
            .register("bodyLogger", new BodyLoggerFilter());
        proxyFactory = new ProxyHandlerFactory(vertx);
        routeResolver = new GatewayRouteResolver(registry, vertx);

        HttpServerOptions serverOptions = new HttpServerOptions()
            .setSsl(true)
            .setKeyCertOptions(new PemKeyCertOptions()
                .setCertPath(TLS_CERT_PATH)
                .setKeyPath(TLS_KEY_PATH));

        reload()
            .compose(v -> vertx.createHttpServer(serverOptions)
                .requestHandler(req -> activeRouter.handle(req))
                .listen(8443))
            .onSuccess(server -> {
                System.out.println("Gateway listening on port " + server.actualPort() + " (HTTPS)");
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
        List<GatewayRoute> routes = routeResolver.resolve(config);
        for (GatewayRoute gr : routes) {
            System.out.println("  " + gr.path() + " -> filters=" + gr.filters().size()
                + " rateLimit=" + (gr.rateLimiter() != null) + " circuitBreaker=" + (gr.circuitBreaker() != null));
        }
        return GatewayRouterBuilder.build(vertx, routes, proxyFactory);
    }
}
