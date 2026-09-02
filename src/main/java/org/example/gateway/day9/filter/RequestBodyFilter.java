package org.example.gateway.day9.filter;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * grill-me 세션에서 합의된 마커 인터페이스. GatewayFilter를 구현하는 클래스가 이것도
 * 같이 implements하면, GatewayFilterAdapter가 이 라우트에 한해서만 요청 바디를
 * 전체 버퍼링(BodyHandler)하고 onRequestBody를 호출해준다 — 이 인터페이스를 안 쓰는
 * 라우트는 지금처럼 바디를 순수 스트리밍한다 (day2-3에서 만든 버퍼링 없는 프록시의
 * 이점을 그대로 유지).
 *
 * 청크 단위가 아니라 "전체 바디를 한 번에" 넘기는 이유: 실제로 바디를 봐야 하는 필터는
 * 대부분 "전체 내용을 보고 판단"이지 스트리밍 최적화가 필요한 경우가 아니었고, 청크 단위
 * 파이프라인은 헤더 단계 필터 조립 방식(Vert.x ctx.next() 기반)과 근본적으로 다른 메커니즘이
 * 필요해서 오히려 "디버깅과 직관성"이라는 목표에 역행한다고 판단했다.
 */
public interface RequestBodyFilter extends GatewayFilter {

    void onRequestBody(RoutingContext ctx, Buffer body, RequestBodyCallback callback);
}
