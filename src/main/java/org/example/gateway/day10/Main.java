package org.example.gateway.day10;

import io.vertx.core.Vertx;
import org.example.gateway.day10.domain.route.GatewayRoute;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.filter.LoggingFilter;
import org.example.gateway.day10.runtime.vertx.VertxGatewayServer;
import org.example.gateway.day9.upstream.DummyUpstreamVerticle;

import java.util.List;

public class Main {

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();

        vertx.deployVerticle(new DummyUpstreamVerticle(9001))
            .compose(id -> {
                GatewayRoute route = new GatewayRoute("/echo", new Endpoint("localhost", 9001));
                VertxGatewayServer server = new VertxGatewayServer(vertx, route, List.of(new LoggingFilter()));
                return server.start(8080);
            })
            .onFailure(err -> {
                err.printStackTrace();
                System.exit(1);
            });
    }
}
