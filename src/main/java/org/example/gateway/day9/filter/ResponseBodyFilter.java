package org.example.gateway.day9.filter;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * grill-me 세션에서 합의된 마커 인터페이스. RequestBodyFilter와 대칭이지만 조립 방식은
 * 대칭이 아니다 — 이 인터페이스는 route.handler() 체인에 안 붙는다. 프록시 핸들러
 * (ProxyHandlerFactory)가 terminal handler라 ctx.next()를 절대 안 부르기 때문에, 그 뒤에
 * route.handler()를 등록해도 호출되지 않는다(grill-me 세션에서 실제로 확인한 문제).
 * 그래서 ProxyHandlerFactory가 이 필터들의 목록을 직접 받아서, 업스트림 응답을 받은 직후
 * 클라이언트로 쓰기 전에 내부적으로 호출한다.
 *
 * 순서 비대칭 주의: onResponseHeaders는 Vert.x의 addHeadersEndHandler가 등록 역순으로
 * 자동 호출해주지만, onResponseBody는 그 메커니즘을 안 타므로 GatewayRouterBuilder가
 * 필터 리스트를 명시적으로 .reversed() 해서 ProxyHandlerFactory에 넘겨야 온ion 모델
 * (요청 1→2→3, 응답 3→2→1)이 유지된다.
 */
public interface ResponseBodyFilter extends GatewayFilter {

    void onResponseBody(RoutingContext ctx, Buffer body, ResponseBodyCallback callback);
}
