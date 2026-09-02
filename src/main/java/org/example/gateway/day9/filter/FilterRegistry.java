package org.example.gateway.day9.filter;

import java.util.HashMap;
import java.util.Map;

/**
 * day8와 다른 점: Handler<RoutingContext> 대신 GatewayFilter를 캐싱한다. 이름으로
 * 조회했을 때 나오는 게 이제 Vert.x 타입이 아니라 우리 인터페이스 타입이다 — 실제
 * Handler로의 변환은 MainVerticle에서 GatewayFilterAdapter를 통해 이뤄진다.
 */
public class FilterRegistry {

    private final Map<String, GatewayFilter> filters = new HashMap<>();

    public FilterRegistry register(String name, GatewayFilter filter) {
        filters.put(name, filter);
        return this;
    }

    public GatewayFilter get(String name) {
        GatewayFilter filter = filters.get(name);
        if (filter == null) {
            throw new IllegalArgumentException("unknown filter: " + name);
        }
        return filter;
    }
}
