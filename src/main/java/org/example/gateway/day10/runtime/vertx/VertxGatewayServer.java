package org.example.gateway.day10.runtime.vertx;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.engine.GatewayEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Router를 얇게 유지하고(Arch.md 8절) Adapter 역할만 한다 — 라우트 매칭 자체는
 * GatewayEngine이 담당한다(feedback 4절 실행 흐름의 첫 스텝). 이 클래스는
 * HttpServerRequest -> GatewayRequest 변환, engine 호출, 결과를
 * HttpServerResponse로 쓰는 것만 한다.
 */
public final class VertxGatewayServer {

    private static final Logger log = LoggerFactory.getLogger(VertxGatewayServer.class);

    private final Vertx vertx;
    private final GatewayEngine engine;

    public VertxGatewayServer(Vertx vertx, GatewayEngine engine) {
        this.vertx = vertx;
        this.engine = engine;
    }

    public Future<Void> start(int port) {
        return vertx.createHttpServer()
            .requestHandler(this::handle)
            .listen(port)
            .onSuccess(server -> log.info("day10 gateway listening on port {}", server.actualPort()))
            .mapEmpty();
    }

    private void handle(HttpServerRequest request) {
        log.debug("[server] <- {} {}", request.method(), request.path());
        GatewayRequest gatewayRequest = VertxRequestAdapter.adapt(request);

        engine.execute(gatewayRequest).whenComplete((response, err) -> {
            if (err != null) {
                log.error("[server] engine failed -> 502: {}", err.getMessage(), err);
                request.response().setStatusCode(502).end("upstream error: " + err.getMessage());
                return;
            }
            log.debug("[server] -> status={}", response.statusCode());
            VertxResponseWriter.write(request.response(), response);
        });
    }
}
