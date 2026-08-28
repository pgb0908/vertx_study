package org.example.gateway.day6.proxy;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.RoutingContext;

/**
 * 요청을 받으면 그룹에서 라운드로빈으로 업스트림 하나를 골라, 요청/응답 바디를
 * 모두 버퍼링하지 않고 스트리밍으로 그대로 전달(pipe)하는 handler를 만든다.
 *
 * - 요청 바디: HttpClientRequest.send(ReadStream)로 ctx.request()를 그대로 흘려보냄
 * - 응답 바디: HttpClientResponse.pipeTo(ctx.response())로 그대로 흘려보냄
 * 어느 쪽도 전체를 메모리에 Buffer로 모으지 않는다.
 */
public final class ProxyHandlerFactory {

    private final HttpClient client;

    public ProxyHandlerFactory(Vertx vertx) {
        this.client = vertx.createHttpClient();
    }

    public Handler<RoutingContext> forGroup(UpstreamGroup group) {
        return ctx -> {
            // 업스트림 커넥션을 맺는 동안(비동기) 요청 바디의 end 이벤트를 놓치지 않도록
            // 핸들러 진입 즉시 동기적으로 pause 해서 큐잉해둔다. 이걸 안 하면 GET처럼
            // 바디가 없는 요청은 "Request has already been read"로 실패한다.
            ctx.request().pause();

            Upstream upstream = group.next();

            RequestOptions options = new RequestOptions()
                .setHost(upstream.host())
                .setPort(upstream.port())
                .setMethod(ctx.request().method())
                .setURI(ctx.request().uri());

            client.request(options)
                .compose(clientRequest -> forward(ctx, clientRequest))
                .onSuccess(clientResponse -> {
                    ctx.response().setStatusCode(clientResponse.statusCode());
                    copyHeaders(clientResponse.headers(), ctx.response().headers());
                    // content-length는 홉바이홉 필터에서 제거했으므로, 업스트림 바디를
                    // 스트리밍(chunked)으로 그대로 흘려보내려면 명시적으로 켜야 한다.
                    ctx.response().setChunked(true);
                    clientResponse.pipeTo(ctx.response())
                        .onFailure(err -> ctx.response().reset());
                })
                .onFailure(err -> ctx.response().setStatusCode(502).end("upstream error: " + err.getMessage()));
        };
    }

    private io.vertx.core.Future<io.vertx.core.http.HttpClientResponse> forward(
        RoutingContext ctx, HttpClientRequest clientRequest) {
        copyHeaders(ctx.request().headers(), clientRequest.headers());
        return clientRequest.send(ctx.request());
    }

    private void copyHeaders(MultiMap from, MultiMap to) {
        for (var entry : from) {
            if (!HopByHopHeaders.NAMES.contains(entry.getKey().toLowerCase())) {
                to.add(entry.getKey(), entry.getValue());
            }
        }
    }
}
