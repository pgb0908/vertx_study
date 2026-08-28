package org.example.gateway.day6.filter;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

import java.util.HashMap;
import java.util.Map;

public class FilterRegistry {

    private final Map<String, Handler<RoutingContext>> filters = new HashMap<>();

    public FilterRegistry register(String name, Handler<RoutingContext> filter) {
        filters.put(name, filter);
        return this;
    }

    public Handler<RoutingContext> get(String name) {
        Handler<RoutingContext> filter = filters.get(name);
        if (filter == null) {
            throw new IllegalArgumentException("unknown filter: " + name);
        }
        return filter;
    }
}
