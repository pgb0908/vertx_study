package org.example.gateway.day2;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import org.example.gateway.day2.config.ConfigLoader;
import org.example.gateway.day2.config.RouteConfig;
import org.example.gateway.day2.filter.FilterRegistry;
import org.example.gateway.day2.filter.Filters;

import java.util.List;

/**
 * Day 2 실습: 고정된 .handler() 대신, 파일(config/day2/routes.json)에 적힌
 * 라우팅 규칙 + 필터 이름 목록을 읽어 런타임에 handler 체인을 조립한다.
 *
 * 프로젝트 루트에서 `./gradlew runDay2`로 실행 (working dir이 프로젝트 루트여야
 * config/day2/routes.json 상대경로가 맞는다).
 */
public class MainVerticle extends AbstractVerticle {

    private static final String ROUTES_CONFIG_PATH = "config/day2/routes.json";

    @Override
    public void start(Promise<Void> startPromise) {
        FilterRegistry registry = new FilterRegistry()
            .register("logging", Filters.logging())
            .register("auth", Filters.auth())
            .register("echo", Filters.echo());

        ConfigLoader.load(vertx, ROUTES_CONFIG_PATH)
            .onSuccess(routeConfigs -> startServer(routeConfigs, registry, startPromise))
            .onFailure(startPromise::fail);
    }

    private void startServer(List<RouteConfig> routeConfigs, FilterRegistry registry, Promise<Void> startPromise) {
        Router router = buildRouter(routeConfigs, registry);

        vertx.createHttpServer()
            .requestHandler(router)
            .listen(8080)
            .onSuccess(server -> {
                System.out.println("Gateway listening on port " + server.actualPort());
                System.out.println("Loaded " + routeConfigs.size() + " routes from " + ROUTES_CONFIG_PATH);
                for (RouteConfig rc : routeConfigs) {
                    System.out.println("  " + rc.path() + " -> " + rc.filters());
                }
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }

    // 핵심: 어떤 route에 어떤 필터가 몇 개, 어떤 순서로 붙는지는 전부 routeConfigs(=파일 내용)가 결정한다.
    // 이 메서드 자체는 config가 무엇이든 똑같이 동작한다 — 새 라우트/필터 조합은 코드 수정 없이 파일만 바꾸면 된다.
    private Router buildRouter(List<RouteConfig> routeConfigs, FilterRegistry registry) {
        Router router = Router.router(vertx);
        for (RouteConfig rc : routeConfigs) {
            Route route = router.route(rc.path());
            for (String filterName : rc.filters()) {
                route.handler(registry.get(filterName));
            }
        }
        return router;
    }
}
