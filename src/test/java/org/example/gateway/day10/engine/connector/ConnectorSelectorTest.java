package org.example.gateway.day10.engine.connector;

import org.example.gateway.day10.domain.upstream.EgressGroup;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.UpstreamClient;
import org.example.gateway.day10.domain.upstream.balance.RoundRobinLoadBalancer;
import org.example.gateway.day10.domain.upstream.resilience.CircuitBreaker;
import org.example.gateway.day10.domain.upstream.resilience.RetryPolicy;
import org.example.gateway.day10.domain.upstream.resilience.TimeoutPolicy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Router.md 예시1(90/10 가중치 분산) 검증. Connector 내부 endpoint 선택과는 별개
 * 레이어라는 게 핵심 — ConnectorSelector는 "어느 Connector로 갈지"만 정한다.
 */
class ConnectorSelectorTest {

    @Test
    void singleDestinationAlwaysWinsRegardlessOfWeight() {
        RuntimeConnector only = connector("only");
        ConnectorSelector selector = new ConnectorSelector(
            List.of(new ConnectorSelector.WeightedConnector(0, only)));

        for (int i = 0; i < 20; i++) {
            assertEquals(only, selector.select());
        }
    }

    @Test
    void distributesRoughlyByWeightOverManyTrials() {
        RuntimeConnector heavy = connector("heavy");
        RuntimeConnector light = connector("light");
        ConnectorSelector selector = new ConnectorSelector(List.of(
            new ConnectorSelector.WeightedConnector(90, heavy),
            new ConnectorSelector.WeightedConnector(10, light)
        ));

        Map<String, Integer> counts = new HashMap<>();
        int trials = 10_000;
        for (int i = 0; i < trials; i++) {
            counts.merge(selector.select().id(), 1, Integer::sum);
        }

        double heavyRatio = counts.getOrDefault("heavy", 0) / (double) trials;
        // 확률적 테스트라 넉넉한 허용 범위(±10%p)를 둔다 — 0.9 근처면 통과.
        assertTrue(heavyRatio > 0.80 && heavyRatio < 1.0, "heavy ratio was " + heavyRatio);
        assertTrue(counts.containsKey("light"), "light destination should still be selected sometimes");
    }

    private static RuntimeConnector connector(String id) {
        EgressGroup group = new EgressGroup(id, List.of(new Endpoint("localhost", 9001)));
        UpstreamClient client = (endpoint, request) -> CompletableFuture.completedFuture(null);
        UpstreamExecutor executor = new UpstreamExecutor(
            client, RetryPolicy.none(), CircuitBreaker.disabled(), TimeoutPolicy.none());
        return new RuntimeConnector(id, group, new RoundRobinLoadBalancer(), executor, "GET", "/x");
    }
}
