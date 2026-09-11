package org.example.gateway.day10.engine.connector;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 하나의 Router가 여러 Connector에 가중치로 분산되는 경우(Router.md 예시1: 90/10)를
 * 담당한다 — Connector 내부의 endpoint 간 로드밸런싱(RuntimeConnector가 담당)과는
 * 별개의, 한 단계 위 레이어다. 목적지가 1개뿐이면 weight와 무관하게 항상 그것을
 * 고른다(Router.md: "단일 목적지 라우팅: 목적지 1개만 정의").
 */
public final class ConnectorSelector {

    /** @param weight Router.spec.destinations[].weight — 상대적 가중치(합이 100일 필요는 없음). */
    public record WeightedConnector(int weight, RuntimeConnector connector) {
    }

    private final List<WeightedConnector> destinations;
    private final int totalWeight;

    public ConnectorSelector(List<WeightedConnector> destinations) {
        if (destinations.isEmpty()) {
            throw new IllegalArgumentException("ConnectorSelector requires at least one destination");
        }
        this.destinations = destinations;
        this.totalWeight = destinations.stream().mapToInt(WeightedConnector::weight).sum();
    }

    public RuntimeConnector select() {
        if (destinations.size() == 1) {
            return destinations.get(0).connector();
        }
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;
        for (WeightedConnector destination : destinations) {
            cumulative += destination.weight();
            if (roll < cumulative) {
                return destination.connector();
            }
        }
        // 부동소수점/반올림 문제 없는 정수 누적이라 이론상 못 오지만, 방어적으로 마지막 걸 반환.
        return destinations.get(destinations.size() - 1).connector();
    }
}
