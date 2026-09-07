package org.example.gateway.day10.runtime.vertx;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import org.example.gateway.day10.domain.filter.Filter;
import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.route.GatewayRoute;
import org.example.gateway.day10.engine.GatewayEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class VertxGatewayServer {

    private static final Logger log = LoggerFactory.getLogger(VertxGatewayServer.class);

    private final Vertx vertx;
    private final GatewayRoute route;
    private final GatewayEngine engine;

    public VertxGatewayServer(Vertx vertx, GatewayRoute route, List<Filter> filters) {
        this.vertx = vertx;
        this.route = route;
        this.engine = new GatewayEngine(filters, new VertxUpstreamClient(vertx));
    }

    public Future<Void> start(int port) {
        return vertx.createHttpServer()
            .requestHandler(this::handle)
            .listen(port)
            .onSuccess(server -> log.info("day10 gateway listening on port {}", server.actualPort()))
            .mapEmpty();
    }

    private void handle(HttpServerRequest request) {
        if (!request.path().equals(route.path())) {
            log.warn("[server] no route matches {} {} -> 404", request.method(), request.path());
            request.response().setStatusCode(404).end("no route matched");
            return;
        }

        GatewayExchange exchange = new GatewayExchange(VertxRequestAdapter.adapt(request), route);
        log.debug("[server] rid={} <- {} {}", exchange.requestId(), request.method(), request.path());

        engine.execute(exchange).whenComplete((response, err) -> {
            if (err != null) {
                log.error("[server] rid={} engine failed -> 502: {}", exchange.requestId(), err.getMessage(), err);
                request.response().setStatusCode(502).end("upstream error: " + err.getMessage());
                return;
            }
            log.debug("[server] rid={} -> status={}", exchange.requestId(), response.statusCode());
            VertxResponseWriter.write(request.response(), response);
        });
    }
}
