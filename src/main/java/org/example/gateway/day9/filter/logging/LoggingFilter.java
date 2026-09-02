package org.example.gateway.day9.filter.logging;

import io.vertx.ext.web.RoutingContext;
import org.example.gateway.day9.filter.FilterCallback;
import org.example.gateway.day9.filter.GatewayFilter;

/**
 * Filters.java에서 분리됨. onRequestHeaders/onResponseHeaders로 나뉘어 있는 것 자체가
 * "요청 로그인지 응답 로그인지"를 코드에서 바로 드러내준다(이 리팩터링의 원래 동기).
 */
public class LoggingFilter implements GatewayFilter {

    @Override
    public void onRequestHeaders(RoutingContext ctx, FilterCallback callback) {
        System.out.println("[logging] request  " + ctx.request().method() + " " + ctx.request().path());
        callback.continueRequest();
    }

    @Override
    public void onResponseHeaders(RoutingContext ctx) {
        // 이 시점엔 프록시 핸들러가 이미 clientResponse의 상태코드를 ctx.response()에
        // 복사해둔 뒤라, statusCode()로 업스트림 응답 결과를 읽을 수 있다.
        System.out.println("[logging] response " + ctx.response().getStatusCode()
            + " " + ctx.request().path());
    }
}
