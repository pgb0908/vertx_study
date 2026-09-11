package org.example.gateway.day10.runtime.vertx;

import io.vertx.circuitbreaker.OpenCircuitException;
import io.vertx.circuitbreaker.TimeoutException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.engine.GatewayEngine;
import org.example.gateway.day10.runtime.vertx.adapter.VertxRequestAdapter;
import org.example.gateway.day10.runtime.vertx.adapter.VertxResponseWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile HttpServer httpServer;

    public VertxGatewayServer(Vertx vertx, GatewayEngine engine) {
        this.vertx = vertx;
        this.engine = engine;
    }

    public Future<Void> start(int port) {
        return vertx.createHttpServer()
            .requestHandler(this::handle)
            .listen(port)
            .onSuccess(server -> {
                this.httpServer = server;
                log.info("day10 gateway listening on port {}", server.actualPort());
            })
            .mapEmpty();
    }

    /**
     * Graceful shutdown: 처리 중인 요청이 있으면 끝날 때까지(혹은 drainTimeoutMs가
     * 지날 때까지) 먼저 기다린 뒤에 리스닝 소켓을 닫는다.
     *
     * 순서가 중요하다 — {@code HttpServer.close()}의 실제 동작은 "Any open HTTP
     * connections will be closed"(Vert.x javadoc, 직접 확인함)다. 즉 in-flight
     * 요청이 있어도 즉시 끊어버린다. 그래서 close()를 먼저 부르면 draining 자체가
     * 무의미해진다 — 반드시 in-flight 카운트가 0이 되는 걸 먼저 기다린 뒤에 닫아야
     * 한다. 대신 이 구현은 draining 도중 새 연결을 막지는 못한다(Vert.x가 "새 연결만
     * 거부하고 기존 연결은 유지"하는 API를 따로 제공하지 않음) — 실제 배포에서는
     * SIGTERM 직후 로드밸런서가 이 인스턴스로의 라우팅을 먼저 끊어준다고 가정한다.
     */
    public Future<Void> stop(long drainTimeoutMs) {
        if (httpServer == null) {
            return Future.succeededFuture();
        }
        return awaitDrain(System.currentTimeMillis() + drainTimeoutMs)
            .compose(v -> httpServer.close());
    }

    private Future<Void> awaitDrain(long deadline) {
        if (inFlight.get() == 0) {
            return Future.succeededFuture();
        }
        if (System.currentTimeMillis() >= deadline) {
            log.warn("[server] shutdown drain timeout with {} request(s) still in flight", inFlight.get());
            return Future.succeededFuture();
        }
        Future<Void> next = Future.future(promise -> vertx.setTimer(50, id -> promise.complete()));
        return next.compose(v -> awaitDrain(deadline));
    }

    private void handle(HttpServerRequest request) {
        inFlight.incrementAndGet();
        log.debug("[server] <- {} {}", request.method(), request.path());
        GatewayRequest gatewayRequest = VertxRequestAdapter.adapt(request);

        engine.execute(gatewayRequest).whenComplete((response, err) -> {
            if (err != null) {
                respondError(request.response(), err);
                inFlight.decrementAndGet();
                return;
            }
            log.debug("[server] -> status={}", response.statusCode());
            VertxResponseWriter.write(request.response(), response)
                .onComplete(v -> inFlight.decrementAndGet());
        });
    }

    /**
     * 예외 상세(err.getMessage())를 클라이언트에 그대로 노출하지 않는다 — 내부
     * 호스트명/스택 원인 등이 새어나갈 수 있는 정보 노출이다. 상세는 로그에만 남기고,
     * 클라이언트에는 예외 종류에 맞는 상태 코드와 고정 메시지만 돌려준다
     * (day9 ProxyHandlerFactory.respondError와 동일한 매핑).
     */
    private void respondError(HttpServerResponse response, Throwable err) {
        Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;

        if (cause instanceof OpenCircuitException) {
            log.warn("[server] circuit open -> 503");
            response.setStatusCode(503).putHeader("Retry-After", "1").end("circuit open, upstream unavailable");
        } else if (cause instanceof TimeoutException) {
            log.warn("[server] upstream timeout -> 504");
            response.setStatusCode(504).end("upstream timeout");
        } else {
            log.error("[server] engine failed -> 502: {}", cause.getMessage(), cause);
            response.setStatusCode(502).end("bad gateway");
        }
    }
}
