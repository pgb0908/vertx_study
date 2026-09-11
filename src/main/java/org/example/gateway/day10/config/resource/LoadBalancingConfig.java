package org.example.gateway.day10.config.resource;

import java.util.List;

/**
 * doc/Connector.md spec.loadBalancing. algorithm은 v1에서 ROUND_ROBIN만 실제로
 * 구현돼 있다 — LEAST_CONN/IP_HASH/RANDOM을 주면 ConfigLoader가 부팅을 막는다
 * (엉뚱하게 ROUND_ROBIN으로 조용히 폴백하지 않기 위해서).
 */
public record LoadBalancingConfig(String algorithm, List<ConnectorTarget> targets) {
}
