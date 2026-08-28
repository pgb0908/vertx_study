package org.example.gateway.day3.filter;

import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

public final class Filters {

    private Filters() {
    }

    public static Handler<RoutingContext> logging() {
        return ctx -> {
            System.out.println("[logging] " + ctx.request().method() + " " + ctx.request().path());
            ctx.next();
        };
    }

    public static Handler<RoutingContext> auth() {
        return ctx -> {
            String apiKey = ctx.request().getHeader("X-Api-Key");
            if (apiKey == null || apiKey.isBlank()) {
                ctx.response().setStatusCode(401).end("missing X-Api-Key header");
                return;
            }
            ctx.next();
        };
    }
}
