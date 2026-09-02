package org.example.gateway.day9.filter.auth;

import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;
import org.example.gateway.day9.filter.FilterCallback;
import org.example.gateway.day9.filter.GatewayFilter;

/** Filters.java에서 분리됨. JWT 서명 검증 실패/헤더 누락 시 401 + 체인 중단(short-circuit). */
public class AuthJwtFilter implements GatewayFilter {

    private final JWTAuth jwtAuth;

    public AuthJwtFilter(JWTAuth jwtAuth) {
        this.jwtAuth = jwtAuth;
    }

    @Override
    public void onRequestHeaders(RoutingContext ctx, FilterCallback callback) {
        String header = ctx.request().getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            ctx.response().setStatusCode(401).end("missing bearer token");
            callback.stopWithResponse();
            return;
        }

        String token = header.substring("Bearer ".length());
        jwtAuth.authenticate(new TokenCredentials(token))
            .onSuccess(user -> {
                ctx.put("user", user);
                callback.continueRequest();
            })
            .onFailure(err -> {
                ctx.response().setStatusCode(401).end("invalid token: " + err.getMessage());
                callback.stopWithResponse();
            });
    }
}
