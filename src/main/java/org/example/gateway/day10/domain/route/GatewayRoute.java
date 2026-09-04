package org.example.gateway.day10.domain.route;

import org.example.gateway.day10.domain.upstream.Endpoint;

/**
 * v1: 라우트 하나 = path 하나 = endpoint 하나. 여러 라우트/EgressGroup/
 * LoadBalancer는 이 구조가 검증된 뒤 2차에서 추가한다.
 */
public record GatewayRoute(String path, Endpoint endpoint) {
}
