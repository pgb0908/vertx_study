package org.example.gateway.day10;

import io.vertx.core.Vertx;
import org.example.gateway.day10.domain.route.GatewayRoute;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.policy.LoggingPolicy;
import org.example.gateway.day10.runtime.vertx.VertxGatewayServer;
import org.example.gateway.day9.upstream.DummyUpstreamVerticle;

import java.util.List;

/**
 * v1 최소 범위: 라우트 하나(/echo -> 로컬 더미 업스트림), 정책 하나(LoggingPolicy).
 * config 파일도, hot-reload도, TLS도 없다 — day9 기능 전체를 옮기는 게 아니라
 * GatewayEngine/도메인 경계가 실제로 성립하는지 검증하는 게 이 단계의 목적.
 */
public class Main {

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();

        vertx.deployVerticle(new DummyUpstreamVerticle(9001))
            .compose(id -> {
                GatewayRoute route = new GatewayRoute("/echo", new Endpoint("localhost", 9001));
                VertxGatewayServer server = new VertxGatewayServer(vertx, route, List.of(new LoggingPolicy()));
                return server.start(8080);
            })
            .onFailure(err -> {
                err.printStackTrace();
                System.exit(1);
            });
    }
}
