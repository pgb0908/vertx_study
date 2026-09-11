package org.example.gateway.day10.runtime.vertx.adapter;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerResponse;
import org.example.gateway.day10.domain.model.GatewayHeaders;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.model.HopByHopHeaders;
import org.example.gateway.day10.runtime.vertx.stream.VertxWriteStreamSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * GatewayResponse -> HttpServerResponse. 항상 chunked로 쓰기 때문에 content-length/
 * transfer-encoding은 그대로 복사하지 않는다(둘 다 세팅하면 Vert.x가
 * IllegalStateException을 던진다) — 그리고 이건 hop-by-hop 헤더 집합에도 포함된다.
 * 게이트웨이-백엔드 hop에서 이미 한 번 걸러진 응답이라도, 게이트웨이-클라이언트 hop은
 * 별개의 hop이므로 여기서도 독립적으로 다시 거른다(RFC 7230 — hop-by-hop 헤더는
 * 매 hop 경계마다 각자 제거해야 한다).
 */
public final class VertxResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(VertxResponseWriter.class);

    private VertxResponseWriter() {
    }

    public static Future<Void> write(HttpServerResponse response, GatewayResponse gatewayResponse) {
        response.setStatusCode(gatewayResponse.statusCode());
        response.setChunked(true);

        GatewayHeaders headers = gatewayResponse.headers();
        Set<String> skip = HopByHopHeaders.namesFor(headers);
        headers.asMap().forEach((name, values) -> {
            if (!skip.contains(name.toLowerCase())) {
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
