package org.example.gateway.day9.routing;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import org.example.gateway.day9.filter.GatewayFilter;
import org.example.gateway.day9.filter.GatewayFilterAdapter;
import org.example.gateway.day9.filter.RequestBodyFilter;
import org.example.gateway.day9.filter.ResponseBodyFilter;
import org.example.gateway.day9.filter.ratelimit.RateLimitFilter;
import org.example.gateway.day9.proxy.ProxyHandlerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "GatewayRoute 리스트 → 실제 Vert.x Router" 조립만 담당한다. GatewayFilterAdapter와
 * 함께, 이 파일이 이 라우팅 서브시스템에서 Vert.x의 Router/Route 타입을 직접 만지는
 * 유일한 지점이다 — 경로 매칭 자체는 재구현하지 않고 그대로 Vert.x에 위임한다
 * (grill-me에서 B-1로 합의: 매칭 로직을 우리가 다시 만들면 실제 배포 동작과 괴리될
 * 위험이 있어서, 테스트도 랜덤 포트 + WebClient로 Vert.x의 진짜 매칭을 그대로 검증한다).
 *
 * 여기서 미매칭 요청 로깅(404)과 미처리 예외 로깅(500)도 함께 붙인다 — grill-me에서
 * 짚었던 "지금까지 이 두 가지가 조용히 사라져서 디버깅이 어려웠다"는 문제를 해결하는 지점.
 */
public final class GatewayRouterBuilder {

    private GatewayRouterBuilder() {
    }

    public static Router build(Vertx vertx, List<GatewayRoute> routes, ProxyHandlerFactory proxyFactory) {
        Router router = Router.router(vertx);

        for (GatewayRoute gr : routes) {
            Route route = router.route(gr.path());

            // RequestBodyFilter가 하나라도 있으면 이 라우트에 한해서만 바디를 통째로
            // 버퍼링한다(BodyHandler). 필터 하나하나가 아니라 라우트 단위 opt-in인 이유:
            // ctx.request()의 바디 스트림은 한 번만 읽을 수 있어서, 필터마다 각자
            // 버퍼링을 시도하면 두 번째 필터부터 "이미 소비된 스트림" 문제가 재발한다.
            // BodyHandler가 한 번 채워두면 ctx.body()는 몇 번을 읽어도 안전하다.
            boolean needsRequestBody = gr.filters().stream().anyMatch(f -> f instanceof RequestBodyFilter);
            if (needsRequestBody) {
                route.handler(BodyHandler.create());
            }

            for (GatewayFilter filter : gr.filters()) {
                route.handler(GatewayFilterAdapter.toRouteHandler(filter));
            }

            if (gr.rateLimiter() != null) {
                route.handler(GatewayFilterAdapter.toRouteHandler(new RateLimitFilter(gr.rateLimiter())));
            }

            if (gr.upstreamGroup() != null) {
                List<ResponseBodyFilter> responseBodyFilters = new ArrayList<>();
                for (GatewayFilter filter : gr.filters()) {
                    if (filter instanceof ResponseBodyFilter rbf) {
                        responseBodyFilters.add(rbf);
                    }
                }
                // onResponseHeaders는 Vert.x의 addHeadersEndHandler가 등록 역순으로 자동
                // 호출해주지만(GatewayFilterAdapter 참고), onResponseBody는 ProxyHandlerFactory가
                // 직접 순회하는 별개 메커니즘이라 그 혜택이 없다 — 여기서 명시적으로 뒤집어야
                // "요청 1→2→3, 응답 3→2→1" 온ion 모델이 두 단계 모두에서 일관되게 유지된다.
                Collections.reverse(responseBodyFilters);

                if (gr.circuitBreaker() != null) {
                    route.handler(proxyFactory.forResilientGroup(
                        gr.upstreamGroup(), gr.circuitBreaker(), gr.maxRetries(), responseBodyFilters));
                } else {
                    route.handler(proxyFactory.forGroup(gr.upstreamGroup(), responseBodyFilters));
                }
            }
        }

        // (C-1) 설정된 라우트 중 아무것도 매칭 안 된 요청 — 지금까지는 조용히 기본 404로
        // 사라졌다. 등록 순서상 맨 마지막이라, 앞의 구체적인 라우트에 하나도 안 걸린
        // 요청만 여기 도달한다.
        router.route().handler(ctx -> {
            System.out.println("[unmatched] " + ctx.request().method() + " " + ctx.request().path());
            ctx.response().setStatusCode(404).end("no route matched");
        });

        // (C-2) 필터/프록시 내부에서 처리 안 된 예외 — 지금까지는 Vert.x 기본 500 처리로
        // 넘어가서 어느 라우트에서 났는지 로그가 안 남았다. 우리가 명시적으로 쓰는
        // 401/429/502/503/504는 정상 응답이라 이 errorHandler를 안 타므로 겹치지 않는다.
        router.errorHandler(500, ctx -> {
            System.out.println("[unhandled] " + ctx.request().method() + " " + ctx.request().path()
                + " -> " + ctx.failure());
            ctx.response().setStatusCode(500).end("internal error");
        });

        return router;
    }
}
