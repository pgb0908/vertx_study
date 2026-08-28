package org.example.gateway.day6.upstream;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;

/**
 * 라운드로빈이 눈에 보이도록, 응답에 자기 자신의 포트를 servedBy로 찍어주는
 * 테스트용 업스트림. 실제 서비스가 아니라 day3 프록시 동작 확인용 더미다.
 */
public class DummyUpstreamVerticle extends AbstractVerticle {

    private final int port;

    public DummyUpstreamVerticle(int port) {
        this.port = port;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        vertx.createHttpServer()
            .requestHandler(req -> req.response()
                .putHeader("content-type", "application/json")
                .end(new JsonObject()
                    .put("servedBy", "upstream-" + port)
                    .put("path", req.path())
                    .encode()))
            .listen(port)
            .onSuccess(server -> {
                System.out.println("Dummy upstream listening on port " + server.actualPort());
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }
}
