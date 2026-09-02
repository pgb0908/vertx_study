package org.example.gateway.day7.filter;

import io.vertx.core.Handler;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.RoutingContext;
import org.example.gateway.day7.ratelimit.RateLimiter;

public final class Filters {

    private Filters() {
    }

    public static Handler<RoutingContext> logging() {
        return ctx -> {
            System.out.println("[logging] " + ctx.request().method() + " " + ctx.request().path());
            ctx.next();
        };
    }

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
                    ctx.put("user", user);
                    ctx.next();
                })
                .onFailure(err -> ctx.response().setStatusCode(401).end("invalid token: " + err.getMessage()));
        };
    }

    /**
     * day5에는 없던 신규 필터. 클라이언트를 식별해서(X-Client-Id 헤더, 없으면 접속 IP)
     * RateLimiter에 토큰을 요청한다. 토큰이 없으면 체인을 끊고 429 + Retry-After로 응답한다
     * (short-circuit — authJwt의 401 처리와 동일한 패턴).
     */
    public static Handler<RoutingContext> rateLimit(RateLimiter limiter) {
        return ctx -> {
            String clientId = clientId(ctx);
            if (limiter.tryAcquire(clientId)) {
                ctx.next();
                return;
            }
            int retryAfter = limiter.retryAfterSeconds(clientId);
            ctx.response()
                .putHeader("Retry-After", String.valueOf(retryAfter))
                .setStatusCode(429)
                .end("rate limit exceeded for " + clientId);
        };
    }

    private static String clientId(RoutingContext ctx) {
        String header = ctx.request().getHeader("X-Client-Id");
        if (header != null && !header.isBlank()) {
            return header;
        }
        return ctx.request().remoteAddress().host();
    }
}
