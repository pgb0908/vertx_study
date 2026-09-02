package org.example.gateway.day9.filter;

import io.vertx.ext.web.RoutingContext;

/**
 * day8까지는 필터가 전부 Handler<RoutingContext> 하나였다. 이 시그니처만 봐서는 그
 * 안에서 요청 쪽을 다루는지 응답 쪽을 다루는지 코드를 안 열어보면 알 수가 없었다
 * (logging()이 대표적 — "[logging] " + ctx.request()... 한 줄만 있으면 요청 로그인지
 * 응답 로그인지 이름만으로 구분이 안 됨).
 *
 * Envoy의 HTTP 필터가 decodeHeaders(요청)/encodeHeaders(응답)를 별개 메서드로 분리하는
 * 것처럼, 여기서도 "요청이 업스트림으로 가기 전"과 "응답이 클라이언트로 가기 전"을
 * 컴파일 타임에 구분되는 별개 메서드로 나눈다.
 *
 * onRequestHeaders가 즉시 continueRequest()를 호출하지 않고 비동기로 넘어갈 수 있는
 * 이유는 authJwt처럼 JWT 검증이 비동기(Future)이기 때문 — Envoy도 StopIteration 이후
 * 필터가 나중에 스스로 continueDecoding()을 호출하는 것과 같은 모델이다.
 */
public interface GatewayFilter {

    /**
     * 요청이 다음 필터/프록시로 넘어가기 전에 호출된다 (Envoy의 decodeHeaders에 해당).
     * 기본 구현은 아무 것도 안 하고 바로 다음으로 넘긴다.
     */
    default void onRequestHeaders(RoutingContext ctx, FilterCallback callback) {
        callback.continueRequest();
    }

    /**
     * 업스트림 응답 헤더가 클라이언트로 흘러나가기 직전에 호출된다 (Envoy의 encodeHeaders에
     * 해당). RoutingContext.addHeadersEndHandler로 구현되며, 실제 응답을 누가 만들었는지
     * (프록시 핸들러, short-circuit 등)와 무관하게 항상 호출된다.
     */
    default void onResponseHeaders(RoutingContext ctx) {
    }
}
