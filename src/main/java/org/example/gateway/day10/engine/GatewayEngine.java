package org.example.gateway.day10.engine;

import org.example.gateway.day10.domain.filter.Filter;
import org.example.gateway.day10.domain.filter.FilterResult;
import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.upstream.UpstreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Filter 체인 오케스트레이터.
 *
 * 실행 순서:
 *   onRequest  [0→N] → upstream → onResponse [N→0]
 *
 * 각 바디 청크는 FilterChainBodySubscriber를 통해 필터 체인을 통과한다:
 *   요청 바디: onRequestBody  [0→N]
 *   응답 바디: onResponseBody [N→0]
 *
 * FilterResult.Abort를 받으면 upstream 호출 없이 즉시 응답을 반환한다.
 */
public final class GatewayEngine {

    private static final Logger log = LoggerFactory.getLogger(GatewayEngine.class);

    private final List<Filter> filters;
    private final UpstreamClient upstreamClient;

    public GatewayEngine(List<Filter> filters, UpstreamClient upstreamClient) {
        this.filters = filters;
        this.upstreamClient = upstreamClient;
        linkChain();
    }

    /** upstream/downstream 포인터 연결 — 향후 동적 삽입/제거 시 활용 */
    private void linkChain() {
        for (int i = 0; i < filters.size() - 1; i++) {
            filters.get(i).linkUpstream(filters.get(i + 1));
            filters.get(i + 1).linkDownstream(filters.get(i));
        }
    }

    public CompletableFuture<GatewayResponse> execute(GatewayExchange exchange) {
        log.debug("[engine] rid={} start {} {} filters={}", exchange.requestId(),
            exchange.request().method(), exchange.request().uri(), filters.size());

        return runRequest(exchange, exchange.request(), 0)
            .thenCompose(result -> {
                if (result instanceof FilterResult.Abort abort) {
                    log.debug("[engine] rid={} aborted -> status={}", exchange.requestId(), abort.response().statusCode());
                    return CompletableFuture.completedFuture(abort.response());
                }
                GatewayRequest req = ((FilterResult.Next) result).request();
                GatewayRequest withTransformedBody = withWrappedBody(req, true);
                log.debug("[engine] rid={} onRequest-chain done -> upstream {}", exchange.requestId(), exchange.route().endpoint());
                return upstreamClient.execute(exchange.route().endpoint(), withTransformedBody)
                    .thenCompose(response -> {
                        log.debug("[engine] rid={} upstream status={} -> onResponse-chain", exchange.requestId(), response.statusCode());
                        exchange.response(response);
                        GatewayResponse withTransformedResponseBody = withWrappedBody(response, false);
                        return runResponse(exchange, withTransformedResponseBody, filters.size() - 1);
                    });
            })
            .whenComplete((response, err) -> {
                if (err != null) log.error("[engine] rid={} failed: {}", exchange.requestId(), err.getMessage(), err);
                else log.debug("[engine] rid={} complete status={}", exchange.requestId(), response.statusCode());
            });
    }

    /** onRequest 체인: 필터 순서대로 (0→N) */
    private CompletableFuture<FilterResult> runRequest(GatewayExchange exchange, GatewayRequest request, int index) {
        if (index >= filters.size()) {
            return CompletableFuture.completedFuture(new FilterResult.Next(request));
        }
        Filter filter = filters.get(index);
        log.debug("[engine] rid={} onRequest[{}] {}", exchange.requestId(), index, filter.getClass().getSimpleName());
        return filter.onRequest(exchange, request).thenCompose(result -> {
            if (result instanceof FilterResult.Abort) return CompletableFuture.completedFuture(result);
            return runRequest(exchange, ((FilterResult.Next) result).request(), index + 1);
        });
    }

    /** onResponse 체인: 필터 역순으로 (N→0) */
    private CompletableFuture<GatewayResponse> runResponse(GatewayExchange exchange, GatewayResponse response, int index) {
        if (index < 0) return CompletableFuture.completedFuture(response);
        Filter filter = filters.get(index);
        log.debug("[engine] rid={} onResponse[{}] {}", exchange.requestId(), index, filter.getClass().getSimpleName());
        return filter.onResponse(exchange, response)
            .thenCompose(next -> runResponse(exchange, next, index - 1));
    }

    private GatewayRequest withWrappedBody(GatewayRequest req, boolean requestDir) {
        GatewayBody wrapped = wrapBody(req.body(), requestDir);
        return new GatewayRequest(req.method(), req.uri(), req.headers(), wrapped);
    }

    private GatewayResponse withWrappedBody(GatewayResponse res, boolean requestDir) {
        GatewayBody wrapped = wrapBody(res.body(), requestDir);
        return new GatewayResponse(res.statusCode(), res.headers(), wrapped);
    }

    private GatewayBody wrapBody(GatewayBody body, boolean requestDir) {
        return subscriber -> body.subscribe(new FilterChainBodySubscriber(subscriber, filters, requestDir));
    }
}
