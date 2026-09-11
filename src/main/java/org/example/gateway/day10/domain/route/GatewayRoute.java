package org.example.gateway.day10.domain.route;

import org.example.gateway.day10.domain.filter.Filter;

import java.util.List;

/**
 * 라우트 설정(config) — Router.md의 spec.rule.match(path+method)에 대응한다.
 * 목적지(Connector) 정보는 여기 없다 — 하나의 Router가 여러 Connector에 가중치로
 * 분산될 수 있어서(Router.md 예시1) "라우트 하나 = 목적지 하나"라는 이전 가정이
 * 깨졌다. 목적지 선택은 engine.ConnectorSelector가 컴파일 시점에 담당한다.
 */
public record GatewayRoute(String path, String method, List<Filter> filters) {
}
