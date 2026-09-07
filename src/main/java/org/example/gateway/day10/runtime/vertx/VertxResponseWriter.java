package org.example.gateway.day10.runtime.vertx;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerResponse;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * GatewayResponse -> HttpServerResponse. 항상 chunked로 쓰기 때문에, 업스트림이
 * 보낸 content-length/transfer-encoding은 그대로 복사하지 않는다 (둘 다 세팅하면
 * Vert.x가 IllegalStateException을 던진다).
 */
final class VertxResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(VertxResponseWriter.class);
    private static final Set<String> SKIP_HEADERS = Set.of("content-length", "transfer-encoding");

    private VertxResponseWriter() {
    }

    static Future<Void> write(HttpServerResponse response, GatewayResponse gatewayResponse) {
        response.setStatusCode(gatewayResponse.statusCode());
        response.setChunked(true);
        gatewayResponse.headers().asMap().forEach((name, values) -> {
            if (!SKIP_HEADERS.contains(name.toLowerCase())) {
                values.forEach(value -> response.putHeader(name, value));
            }
        });

        log.debug("[response-writer] streaming body to client, status={}", gatewayResponse.statusCode());
        CompletableFuture<Void> done = new CompletableFuture<>();
        gatewayResponse.body().subscribe(new VertxWriteStreamSubscriber(response, done));

        Promise<Void> promise = Promise.promise();
        done.whenComplete((v, err) -> {
            if (err != null) {
                log.error("[response-writer] failed while streaming response body: {}", err.getMessage(), err);
                promise.tryFail(err);
            } else {
                log.debug("[response-writer] response fully written to client");
                promise.tryComplete();
            }
        });
        return promise.future();
    }
}
