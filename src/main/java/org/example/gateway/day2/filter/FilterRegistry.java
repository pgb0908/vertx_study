package org.example.gateway.day2.filter;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

import java.util.HashMap;
import java.util.Map;

/**
 * "필터 이름" -> "실제 Handler 구현" 매핑.
 * 실제 필터 코드는 여기(서버 안)에 미리 배포되어 있고, 설정 파일은 그중 무엇을
 * 어떤 순서로 켤지만 이름으로 지정한다. (동적 클래스로딩이 아니라 config toggle 방식)
 */
public class FilterRegistry {

    private final Map<String, Handler<RoutingContext>> filters = new HashMap<>();

    public FilterRegistry register(String name, Handler<RoutingContext> filter) {
        filters.put(name, filter);
        return this;
    }

    public Handler<RoutingContext> get(String name) {
        Handler<RoutingContext> filter = filters.get(name);
        if (filter == null) {
            throw new IllegalArgumentException(
                "unknown filter: " + name + " (registered: " + filters.keySet() + ")");
        }
        return filter;
    }
}
