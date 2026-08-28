package org.example.gateway.day5.filter;

import io.vertx.core.Handler;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;

public final class Filters {

    private Filters() {
    }

    public static Handler<RoutingContext> logging() {
        return ctx -> {
            System.out.println("[logging] " + ctx.request().method() + " " + ctx.request().path());
            ctx.next();
        };
    }

    /**
     * day4와 다른 점: day4의 auth()는 헤더 존재 여부만 보는 가짜 필터였다. 여기서는 실제로
     * Authorization: Bearer <token>을 꺼내 JWTAuth로 서명/만료를 검증한다.
     * 검증에 실패하면(서명 불일치, 만료 등) 체인을 끊고 401로 응답한다(short-circuit).
     */
    public static Handler<RoutingContext> authJwt(JWTAuth jwtAuth) {
        return ctx -> {
            String header = ctx.request().getHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                ctx.response().setStatusCode(401).end("missing bearer token");
                return;
            }

            String token = header.substring("Bearer ".length());
            jwtAuth.authenticate(new TokenCredentials(token))
                .onSuccess(user -> {
                    // 이후 핸들러(프록시 등)가 "누가 요청했는지" 필요할 때 쓸 수 있도록 보관.
                    ctx.put("user", user);
                    ctx.next();
                })
                .onFailure(err -> ctx.response().setStatusCode(401).end("invalid token: " + err.getMessage()));
        };
    }
}
