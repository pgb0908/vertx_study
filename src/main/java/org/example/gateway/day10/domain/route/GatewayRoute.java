package org.example.gateway.day10.domain.route;

import org.example.gateway.day10.domain.filter.Filter;
import org.example.gateway.day10.domain.upstream.EgressGroup;

import java.util.List;

/**
 * 라우트 설정(config). path가 매칭되면 어떤 EgressGroup으로 보내고 어떤
 * Filter를 거칠지를 정의한다 — 이 자체는 아직 "실행 가능한" 형태가 아니다.
 * FilterChain으로 컴파일되고 EndpointSelector가 붙은 실행 가능 형태는
 * engine.RuntimeRoute (feedback 7/12절: Config World → compile → Runtime World).
 */
public record GatewayRoute(String path, EgressGroup egressGroup, List<Filter> filters) {
}
