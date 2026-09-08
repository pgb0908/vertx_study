package org.example.gateway.day10.engine;

import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.LoadBalancer;

/**
 * GatewayEngine의 "Endpoint Selection" 단계 (feedback 4절 실행 흐름의 한 스텝).
 * 실제 선택 전략은 LoadBalancer에 위임하고, 이 클래스는 "지금 이 라우트의
 * EgressGroup에서 골라라"는 호출 지점만 고정한다.
 */
public final class EndpointSelector {

    private final LoadBalancer loadBalancer;

    public EndpointSelector(LoadBalancer loadBalancer) {
        this.loadBalancer = loadBalancer;
    }

    public Endpoint select(GatewayExchange exchange) {
        return loadBalancer.select(exchange.route().egressGroup(), exchange);
    }
}
