package org.example.gateway.day9.filter.ratelimit;

import io.vertx.ext.web.RoutingContext;
import org.example.gateway.day9.filter.FilterCallback;
import org.example.gateway.day9.filter.GatewayFilter;

/**
 * Filters.java에서 분리됨. 클라이언트를 식별해서(X-Client-Id 헤더, 없으면 접속 IP)
 * RateLimiter에 토큰을 요청한다. 토큰이 없으면 체인을 끊고 429 + Retry-After로 응답한다.
 * RateLimiter(토큰 버킷 알고리즘 자체)는 이 필터의 유일한 소비자라 같은 패키지에 둔다.
 */
public class RateLimitFilter implements GatewayFilter {

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void onRequestHeaders(RoutingContext ctx, FilterCallback callback) {
        String clientId = clientId(ctx);
        if (limiter.tryAcquire(clientId)) {
            callback.continueRequest();
            return;
        }
        int retryAfter = limiter.retryAfterSeconds(clientId);
        ctx.response()
            .putHeader("Retry-After", String.valueOf(retryAfter))
            .setStatusCode(429)
            .end("rate limit exceeded for " + clientId);
        callback.stopWithResponse();
    }

    private static String clientId(RoutingContext ctx) {
        String header = ctx.request().getHeader("X-Client-Id");
        if (header != null && !header.isBlank()) {
            return header;
        }
        return ctx.request().remoteAddress().host();
    }
}
