package org.example.gateway.day3;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import org.example.gateway.day3.config.ConfigLoader;
import org.example.gateway.day3.config.GatewayConfig;
import org.example.gateway.day3.config.RouteConfig;
import org.example.gateway.day3.config.UpstreamConfig;
import org.example.gateway.day3.filter.FilterRegistry;
import org.example.gateway.day3.filter.Filters;
import org.example.gateway.day3.proxy.ProxyHandlerFactory;
import org.example.gateway.day3.proxy.UpstreamGroup;

import java.util.HashMap;
import java.util.Map;

/**
 * Day 3 실습: day2의 필터 체인 뒤에 실제 리버스 프록시 handler를 붙인다.
 * routes.json은 각 경로가 어떤 필터를 거쳐 어떤 upstreamGroup으로 가는지,
 * upstreams.json은 그 그룹에 실제 어떤 서버들이 있는지 정의한다.
 */
public class MainVerticle extends AbstractVerticle {

    private static final String ROUTES_CONFIG_PATH = "config/day3/routes.json";
    private static final String UPSTREAMS_CONFIG_PATH = "config/day3/upstreams.json";

    @Override
    public void start(Promise<Void> startPromise) {
        FilterRegistry registry = new FilterRegistry()
            .register("logging", Filters.logging())
            .register("auth", Filters.auth());

        ProxyHandlerFactory proxyFactory = new ProxyHandlerFactory(vertx);

        ConfigLoader.load(vertx, ROUTES_CONFIG_PATH, UPSTREAMS_CONFIG_PATH)
            .onSuccess(config -> startServer(config, registry, proxyFactory, startPromise))
            .onFailure(startPromise::fail);
    }

    private void startServer(GatewayConfig config, FilterRegistry registry,
                              ProxyHandlerFactory proxyFactory, Promise<Void> startPromise) {
        Router router = buildRouter(config, registry, proxyFactory);

        vertx.createHttpServer()
            .requestHandler(router)
            .listen(8080)
            .onSuccess(server -> {
                System.out.println("Gateway listening on port " + server.actualPort());
                for (RouteConfig rc : config.routes()) {
                    System.out.println("  " + rc.path() + " -> filters=" + rc.filters()
                        + " upstreamGroup=" + rc.upstreamGroup());
                }
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }

    private Router buildRouter(GatewayConfig config, FilterRegistry registry, ProxyHandlerFactory proxyFactory) {
        Router router = Router.router(vertx);
        UpstreamConfig upstreamConfig = config.upstreams();
        // 같은 upstreamGroup 이름을 여러 라우트가 참조해도 라운드로빈 커서를 공유하도록,
        // 그룹당 UpstreamGroup 인스턴스를 하나만 만들어 재사용한다.
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
