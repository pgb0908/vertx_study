package org.example.gateway.day10.runtime.vertx;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.policy.GatewayPolicy;
import org.example.gateway.day10.domain.route.GatewayRoute;
import org.example.gateway.day10.engine.GatewayEngine;

import java.util.List;

/**
 * day9의 MainVerticle + GatewayRouterBuilder에 대응. Router의 역할은 "HTTP
 * 요청을 GatewayEngine에 연결하는 Adapter"뿐이어야 한다(Arch.md 8절).
 *
 * v1은 라우트가 하나뿐이라 Vert.x Router조차 필요 없어서 path를 직접 비교한다.
 * 라우트가 여러 개가 되는 2차 단계에서는, day9의 GatewayRouterBuilder와 같은
 * 이유(매칭 로직을 우리가 재구현하면 실제 배포 동작과 괴리될 위험)로 실제 매칭을
 * Vert.x Router에 위임하게 된다 — 그때 각 route.handler()가 이미 알고 있는
 * GatewayRoute를 그대로 engine에 넘기면 되므로, 이 클래스의 handle() 아래
 * 절반(GatewayExchange 생성 이후)은 거의 안 바뀐다.
 */
public final class VertxGatewayServer {

    private final Vertx vertx;
    private final GatewayRoute route;
    private final GatewayEngine engine;

    public VertxGatewayServer(Vertx vertx, GatewayRoute route, List<GatewayPolicy> policies) {
        this.vertx = vertx;
        this.route = route;
        this.engine = new GatewayEngine(policies, new VertxUpstreamClient(vertx));
    }

    public Future<Void> start(int port) {
        return vertx.createHttpServer()
            .requestHandler(this::handle)
            .listen(port)
            .onSuccess(server -> System.out.println("day10 gateway listening on port " + server.actualPort()))
            .mapEmpty();
    }

    private void handle(HttpServerRequest request) {
        if (!request.path().equals(route.path())) {
            System.out.println("[unmatched] " + request.method() + " " + request.path());
            request.response().setStatusCode(404).end("no route matched");
            return;
        }

        GatewayExchange exchange = new GatewayExchange(VertxRequestAdapter.adapt(request), route);
        engine.execute(exchange).whenComplete((response, err) -> {
            if (err != null) {
                System.out.println("[unhandled] " + request.method() + " " + request.path() + " -> " + err);
                request.response().setStatusCode(502).end("upstream error: " + err.getMessage());
                return;
            }
            VertxResponseWriter.write(request.response(), response);
        });
    }
}
