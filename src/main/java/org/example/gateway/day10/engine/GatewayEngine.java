package org.example.gateway.day10.engine;

import org.example.gateway.day10.domain.filter.FilterResult;
import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayHeaders;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;

/**
 * Gateway 요청 한 건의 전체 실행 흐름을 소유하는 오케스트레이터 (Arch.md 4절
 * 실행 흐름 다이어그램 그대로):
 *
 *   Route Match → Downstream FilterChain [0→N] → Endpoint Selection
 *     → Upstream(Retry/CircuitBreaker/Timeout 포함) → Upstream FilterChain [N→0]
 *
 * GatewayEngine 자신은 상태를 갖지 않는다(feedback 6절) — snapshot/upstreamExecutor는
 * 요청마다 바뀌지 않는 설정이고, 거래별 상태는 전부 GatewayExchange가 가진다.
 * 라우트마다 다른 FilterChain/EndpointSelector를 쓸 수 있도록, 이 두 가지는
 * RuntimeRoute(라우트 매칭 결과)에서 꺼내 쓴다 — Engine 생성자에 고정하지 않는다.
 */
public final class GatewayEngine {

    private static final Logger log = LoggerFactory.getLogger(GatewayEngine.class);

    private final Supplier<RuntimeSnapshot> snapshot;
    private final UpstreamExecutor upstreamExecutor;

    public GatewayEngine(Supplier<RuntimeSnapshot> snapshot, UpstreamExecutor upstreamExecutor) {
        this.snapshot = snapshot;
        this.upstreamExecutor = upstreamExecutor;
    }

    public CompletableFuture<GatewayResponse> execute(GatewayRequest request) {
        Optional<RuntimeRoute> matched = snapshot.get().routeTable().match(request.uri());
        if (matched.isEmpty()) {
            log.debug("[engine] no route matches {} {}", request.method(), request.uri());
            return CompletableFuture.completedFuture(notFound());
        }

        RuntimeRoute runtimeRoute = matched.get();
        GatewayExchange exchange = new GatewayExchange(request, runtimeRoute.route());
        FilterChain filterChain = runtimeRoute.filterChain();

        log.debug("[engine] rid={} start {} {}", exchange.requestId(), request.method(), request.uri());

        return filterChain.executeDownstream(exchange, exchange.request())
            .thenCompose(result -> {
                if (result instanceof FilterResult.Abort abort) {
                    log.debug("[engine] rid={} aborted -> status={}", exchange.requestId(), abort.response().statusCode());
                    exchange.response(abort.response());
                    return CompletableFuture.completedFuture(abort.response());
                }
                GatewayRequest current = ((FilterResult.Next) result).request();
                exchange.request(current);
                Endpoint endpoint = runtimeRoute.endpointSelector().select(exchange);
                log.debug("[engine] rid={} downstream-chain done -> upstream {}", exchange.requestId(), endpoint);
                GatewayRequest withBody = withWrappedRequestBody(current, filterChain);
                return upstreamExecutor.execute(endpoint, withBody)
                    .thenCompose(rawResponse -> {
                        log.debug("[engine] rid={} upstream status={} -> upstream-chain", exchange.requestId(), rawResponse.statusCode());
                        exchange.upstreamResponse(rawResponse);
                        GatewayResponse withRespBody = withWrappedResponseBody(rawResponse, filterChain);
                        return filterChain.executeUpstream(exchange, withRespBody)
                            .thenApply(finalResponse -> {
                                exchange.response(finalResponse);
                                return finalResponse;
                            });
                    });
            })
            .whenComplete((response, err) -> {
                if (err != null) log.error("[engine] rid={} failed: {}", exchange.requestId(), err.getMessage(), err);
                else log.debug("[engine] rid={} complete status={}", exchange.requestId(), response.statusCode());
            });
    }

    private static GatewayResponse notFound() {
        byte[] body = "no route matched".getBytes(StandardCharsets.UTF_8);
        return new GatewayResponse(404, GatewayHeaders.empty(), GatewayBody.of(body));
    }

    private static GatewayRequest withWrappedRequestBody(GatewayRequest req, FilterChain filterChain) {
        GatewayBody wrapped = filterChain.wrapRequestBody(req.body());
        return new GatewayRequest(req.method(), req.uri(), req.headers(), wrapped);
    }

    private static GatewayResponse withWrappedResponseBody(GatewayResponse res, FilterChain filterChain) {
        GatewayBody wrapped = filterChain.wrapResponseBody(res.body());
        return new GatewayResponse(res.statusCode(), res.headers(), wrapped);
    }
}
