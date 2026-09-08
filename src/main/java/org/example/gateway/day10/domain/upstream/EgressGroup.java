package org.example.gateway.day10.domain.upstream;

import java.util.List;

/**
 * 라우트가 트래픽을 보낼 수 있는 Endpoint 후보 집합 (feedback 11절).
 * 실제 어떤 Endpoint를 고를지는 EgressGroup 자신이 정하지 않는다 — LoadBalancer가 정한다.
 */
public record EgressGroup(String name, List<Endpoint> endpoints) {
}
