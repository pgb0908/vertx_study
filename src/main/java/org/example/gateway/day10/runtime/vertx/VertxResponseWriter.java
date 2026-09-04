package org.example.gateway.day10.runtime.vertx;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerResponse;
import org.example.gateway.day10.domain.model.GatewayResponse;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * GatewayResponse -> HttpServerResponse. 항상 chunked로 쓰기 때문에, 업스트림이
 * 보낸 content-length/transfer-encoding은 그대로 복사하지 않는다 (둘 다 세팅하면
 * Vert.x가 IllegalStateException을 던진다).
 */
final class VertxResponseWriter {

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

        System.out.println("[response-writer] streaming body to client, status=" + gatewayResponse.statusCode());
        CompletableFuture<Void> done = new CompletableFuture<>();
        gatewayResponse.body().subscribe(new VertxWriteStreamSubscriber(response, done));

        Promise<Void> promise = Promise.promise();
        done.whenComplete((v, err) -> {
            if (err != null) {
                System.out.println("[response-writer] failed while streaming response body: " + err);
                promise.tryFail(err);
            } else {
                System.out.println("[response-writer] response fully written to client");
                promise.tryComplete();
            }
        });
        return promise.future();
    }
}
