package org.example.gateway.day7.upstream;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;

/**
 * day6와 다른 점: 회로차단기/타임아웃/재시도를 테스트하려면 업스트림이 일부러
 * 느리거나 에러를 내야 하므로, 쿼리 파라미터로 장애를 흉내낼 수 있게 했다.
 *   ?delayMs=2000 -> 2초 지연 후 응답 (breaker의 timeout 유발용)
 *   ?fail=true    -> 500 즉시 응답 (breaker의 maxFailures 유발용)
 */
public class DummyUpstreamVerticle extends AbstractVerticle {

    private final int port;

    public DummyUpstreamVerticle(int port) {
        this.port = port;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        vertx.createHttpServer()
            .requestHandler(req -> {
                boolean fail = "true".equals(req.getParam("fail"));
                long delayMs = parseLong(req.getParam("delayMs"), 0);

                Runnable respond = () -> {
                    if (fail) {
                        req.response().setStatusCode(500).end("simulated upstream failure");
                        return;
                    }
                    req.response()
                        .putHeader("content-type", "application/json")
                        .end(new JsonObject()
                            .put("servedBy", "upstream-" + port)
                            .put("path", req.path())
                            .encode());
                };

                if (delayMs > 0) {
                    vertx.setTimer(delayMs, id -> respond.run());
                } else {
                    respond.run();
                }
            })
            .listen(port)
            .onSuccess(server -> {
                System.out.println("Dummy upstream listening on port " + server.actualPort());
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }

    private static long parseLong(String value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
