package org.example.gateway.day10.domain.upstream.balance;

import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.upstream.EgressGroup;
import org.example.gateway.day10.domain.upstream.Endpoint;

/**
 * Endpoint 선택 정책 (feedback 11절). Vert.x의 네트워크 기능과 별개의 문제 —
 * 어떤 네트워크 런타임을 쓰든 이 인터페이스는 그대로 유지된다.
 */
public interface LoadBalancer {

    Endpoint select(EgressGroup group, GatewayExchange exchange);
}
