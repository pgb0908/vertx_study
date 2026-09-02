package org.example.gateway.day8;

import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import org.example.gateway.day8.config.ConfigLoader;
import org.example.gateway.day8.config.GatewayConfig;
import org.example.gateway.day8.config.RouteConfig;
import org.example.gateway.day8.config.UpstreamConfig;
import org.example.gateway.day8.filter.FilterRegistry;
import org.example.gateway.day8.filter.Filters;
import org.example.gateway.day8.proxy.ProxyHandlerFactory;
import org.example.gateway.day8.proxy.UpstreamGroup;
import org.example.gateway.day8.ratelimit.RateLimiter;
import org.example.gateway.day8.reload.ConfigWatcher;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Day 8 실습: day7의 회로차단기 게이트웨이 앞단을 TLS로 종단한다. day7까지는 평문
 * HTTP(8080)였지만, 여기서는 자체서명 인증서로 HTTPS(8443)를 받아 Router에 그대로
 * 넘긴다 — 필터 체인(JWT/레이트리밋/회로차단기/프록시)은 전송 계층과 무관하게 동작하는
 * Handler<RoutingContext>일 뿐이라 코드 변경 없이 그대로 재사용된다. TLS 종단은
 * HttpServer 생성 시 옵션 한 군데(setSsl + setPemKeyCertOptions)로 끝난다.
 */
public class MainVerticle extends AbstractVerticle {

    private static final String CONFIG_DIR = "config/day8";
    private static final String ROUTES_CONFIG_PATH = CONFIG_DIR + "/routes.json";
    private static final String UPSTREAMS_CONFIG_PATH = CONFIG_DIR + "/upstreams.json";
    // day7에는 없던 신규 필드 — 자체서명 인증서/키 경로.
    private static final String TLS_CERT_PATH = CONFIG_DIR + "/tls/cert.pem";
    private static final String TLS_KEY_PATH = CONFIG_DIR + "/tls/key.pem";

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

        // day7과 다른 점: day7까지는 vertx.createHttpServer()를 인자 없이(기본 옵션, 평문
        // HTTP) 호출하고 8080에 listen했다. day8은 HttpServerOptions에 setSsl(true) +
        // setKeyCertOptions(자체서명 인증서)를 넣어 TLS를 켜고, 포트도 8443(관례상 HTTPS
        // 포트)으로 바꾼 것 말고는 나머지 코드(reload/buildRouter/필터체인)가 전혀 안 바뀌었다.
        // 업스트림(9001/9002)과의 통신은 여전히 평문 HTTP — TLS는 엣지(클라이언트<->게이트웨이
        // 구간)에서만 종단한다.
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
