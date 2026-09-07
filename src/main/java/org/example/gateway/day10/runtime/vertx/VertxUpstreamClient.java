package org.example.gateway.day10.runtime.vertx;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayHeaders;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.UpstreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * domain.UpstreamClient의 유일한 구현체. GatewayEngine은 이 클래스의 존재를
 * 모르고 UpstreamClient 인터페이스만 본다 (Arch.md 9절).
 */
public final class VertxUpstreamClient implements UpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(VertxUpstreamClient.class);

    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of("content-length", "transfer-encoding", "host");
    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of("content-length", "transfer-encoding");

    private final HttpClient client;

    public VertxUpstreamClient(Vertx vertx) {
        this.client = vertx.createHttpClient();
    }

    @Override
    public CompletableFuture<GatewayResponse> execute(Endpoint endpoint, GatewayRequest request) {
        log.debug("[upstream] connecting {}:{} {} {}", endpoint.host(), endpoint.port(),
            request.method(), request.uri());

        RequestOptions options = new RequestOptions()
            .setHost(endpoint.host())
            .setPort(endpoint.port())
            .setMethod(HttpMethod.valueOf(request.method()))
            .setURI(request.uri());

        CompletableFuture<GatewayResponse> result = new CompletableFuture<>();

        client.request(options)
            .onFailure(err -> {
                log.error("[upstream] connection failed: {}", err.getMessage(), err);
                result.completeExceptionally(err);
            })
            .onSuccess(clientRequest -> {
                log.debug("[upstream] connected -> streaming request body");
                forward(clientRequest, request, result);
            });

        return result;
    }

    private void forward(HttpClientRequest clientRequest, GatewayRequest request,
                          CompletableFuture<GatewayResponse> result) {
        clientRequest.setChunked(true);
        copyRequestHeaders(request.headers(), clientRequest);

        clientRequest.response()
            .onFailure(err -> {
                log.error("[upstream] no response received: {}", err.getMessage(), err);
                result.completeExceptionally(err);
            })
            .onSuccess(clientResponse -> {
                log.debug("[upstream] response status={}", clientResponse.statusCode());
                GatewayHeaders headers = GatewayHeaders.empty();
                clientResponse.headers().forEach(entry -> {
                    if (!SKIP_RESPONSE_HEADERS.contains(entry.getKey().toLowerCase())) {
                        headers.add(entry.getKey(), entry.getValue());
                    }
                });
                GatewayBody body = new VertxReadStreamPublisher(clientResponse)::subscribe;
                result.complete(new GatewayResponse(clientResponse.statusCode(), headers, body));
            });

        CompletableFuture<Void> bodyDone = new CompletableFuture<>();
        bodyDone.whenComplete((v, err) -> {
            if (err != null) {
                result.completeExceptionally(err);
            }
        });
        request.body().subscribe(new VertxWriteStreamSubscriber(clientRequest, bodyDone));
    }

    private void copyRequestHeaders(GatewayHeaders headers, HttpClientRequest target) {
        headers.asMap().forEach((name, values) -> {
            if (!SKIP_REQUEST_HEADERS.contains(name.toLowerCase())) {
                values.forEach(value -> target.putHeader(name, value));
            }
        });
    }
}
