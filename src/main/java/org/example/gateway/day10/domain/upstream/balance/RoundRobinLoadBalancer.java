package org.example.gateway.day10.domain.upstream.balance;

import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.upstream.EgressGroup;
import org.example.gateway.day10.domain.upstream.Endpoint;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EgressGroup별로 순번을 독립적으로 관리하는 라운드로빈 LoadBalancer.
 * 순번(counter)은 실행 중에 바뀌는 runtime state이므로, config 객체인
 * EgressGroup이 아니라 이 구현체가 들고 있는다 (feedback 12절: config와
 * runtime state 분리).
 */
public final class RoundRobinLoadBalancer implements LoadBalancer {

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public Endpoint select(EgressGroup group, GatewayExchange exchange) {
        List<Endpoint> endpoints = group.endpoints();
        if (endpoints.isEmpty()) {
            throw new IllegalStateException("EgressGroup '" + group.name() + "' has no endpoints");
        }
        AtomicInteger counter = counters.computeIfAbsent(group.name(), name -> new AtomicInteger());
        int index = Math.floorMod(counter.getAndIncrement(), endpoints.size());
        return endpoints.get(index);
    }
}
