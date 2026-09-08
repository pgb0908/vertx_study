package org.example.gateway.day10;

import io.vertx.core.Vertx;
import org.example.gateway.day10.domain.route.GatewayRoute;
import org.example.gateway.day10.domain.upstream.CircuitBreaker;
import org.example.gateway.day10.domain.upstream.EgressGroup;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.RetryPolicy;
import org.example.gateway.day10.domain.upstream.RoundRobinLoadBalancer;
import org.example.gateway.day10.domain.upstream.TimeoutPolicy;
import org.example.gateway.day10.domain.upstream.UpstreamClient;
import org.example.gateway.day10.engine.GatewayEngine;
import org.example.gateway.day10.engine.RouteTable;
import org.example.gateway.day10.engine.RuntimeRoute;
import org.example.gateway.day10.engine.RuntimeSnapshot;
import org.example.gateway.day10.engine.UpstreamExecutor;
import org.example.gateway.day10.filter.LoggingFilter;
import org.example.gateway.day10.runtime.vertx.VertxGatewayServer;
import org.example.gateway.day10.runtime.vertx.VertxUpstreamClient;
import org.example.gateway.day9.upstream.DummyUpstreamVerticle;

import java.util.List;

public class Main {

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();

        vertx.deployVerticle(new DummyUpstreamVerticle(9001))
            .compose(id -> {
                EgressGroup echoUpstream = new EgressGroup("echo-upstream", List.of(new Endpoint("localhost", 9001)));
                GatewayRoute route = new GatewayRoute("/echo", echoUpstream, List.of(new LoggingFilter()));
                RuntimeRoute runtimeRoute = new RuntimeRoute(route, new RoundRobinLoadBalancer());
                RuntimeSnapshot snapshot = new RuntimeSnapshot(new RouteTable(List.of(runtimeRoute)));

                UpstreamClient upstreamClient = new VertxUpstreamClient(vertx);
                UpstreamExecutor upstreamExecutor = new UpstreamExecutor(
                    upstreamClient, RetryPolicy.none(), CircuitBreaker.disabled(), TimeoutPolicy.none());

                GatewayEngine engine = new GatewayEngine(() -> snapshot, upstreamExecutor);
                VertxGatewayServer server = new VertxGatewayServer(vertx, engine);
                return server.start(8080);
            })
            .onFailure(err -> {
                err.printStackTrace();
                System.exit(1);
            });
    }
}
