package org.example.gateway.day2.filter;

import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

/**
 * 지금 등록 가능한 필터 구현들. 실제 인증/레이트리밋 로직이 아니라
 * "설정으로 체인에 끼워 넣고 뺄 수 있다"는 메커니즘을 보여주기 위한 스텁이다.
 */
public final class Filters {

    private Filters() {
    }

    public static Handler<RoutingContext> logging() {
        return ctx -> {
            System.out.println("[logging] " + ctx.request().method() + " " + ctx.request().path());
            ctx.next();
        };
    }

    public static Handler<RoutingContext> auth() {
        return ctx -> {
            String apiKey = ctx.request().getHeader("X-Api-Key");
            if (apiKey == null || apiKey.isBlank()) {
                // ctx.next()를 호출하지 않으므로 여기서 체인이 끊긴다.
                ctx.response().setStatusCode(401).end("missing X-Api-Key header");
                return;
            }
            ctx.next();
        };
    }

    // 체인의 마지막에 두는 필터: 응답을 끝내고 next()는 호출하지 않는다.
    public static Handler<RoutingContext> echo() {
        return ctx -> ctx.response()
            .putHeader("content-type", "application/json")
            .end(new JsonObject()
                .put("path", ctx.request().path())
                .put("message", "reached end of filter chain")
                .encode());
    }
}
