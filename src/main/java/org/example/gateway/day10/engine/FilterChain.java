package org.example.gateway.day10.engine;

import org.example.gateway.day10.domain.filter.Filter;
import org.example.gateway.day10.domain.filter.FilterResult;
import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Filter 실행 topology의 유일한 소유자 (feedback 3절: "Filter는 자신의 동작만 알고,
 * 실행 topology는 FilterChain이 소유한다"). Filter끼리 서로를 아는 mutable
 * upstream/downstream pointer를 두지 않고, 이 클래스가 0→N(요청)/N→0(응답) 순회를
 * 전담한다.
 */
public final class FilterChain {

    private static final Logger log = LoggerFactory.getLogger(FilterChain.class);

    private final List<Filter> filters;

    public FilterChain(List<Filter> filters) {
        this.filters = filters;
    }

    /** 요청 방향 0→N. Abort를 만나면 즉시 멈춘다. */
    public CompletableFuture<FilterResult> executeDownstream(GatewayExchange exchange, GatewayRequest request) {
        return runRequest(exchange, request, 0);
    }

    /** 응답 방향 N→0. */
    public CompletableFuture<GatewayResponse> executeUpstream(GatewayExchange exchange, GatewayResponse response) {
        return runResponse(exchange, response, filters.size() - 1);
    }

    /** 요청 바디 각 청크에 onRequestBody를 0→N 순서로 적용하는 GatewayBody로 감싼다. */
    public GatewayBody wrapRequestBody(GatewayBody body) {
        return subscriber -> body.subscribe(new FilterChainBodySubscriber(subscriber, filters, true));
    }

    /** 응답 바디 각 청크에 onResponseBody를 N→0 순서로 적용하는 GatewayBody로 감싼다. */
    public GatewayBody wrapResponseBody(GatewayBody body) {
        return subscriber -> body.subscribe(new FilterChainBodySubscriber(subscriber, filters, false));
    }

    private CompletableFuture<FilterResult> runRequest(GatewayExchange exchange, GatewayRequest request, int index) {
        if (index >= filters.size()) {
            return CompletableFuture.completedFuture(new FilterResult.Next(request));
        }
        Filter filter = filters.get(index);
        log.debug("[filter-chain] rid={} onRequest[{}] {}", exchange.requestId(), index, filter.getClass().getSimpleName());
        return filter.onRequest(exchange, request).thenCompose(result -> {
            if (result instanceof FilterResult.Abort) {
                return CompletableFuture.completedFuture(result);
            }
            return runRequest(exchange, ((FilterResult.Next) result).request(), index + 1);
        });
    }

    private CompletableFuture<GatewayResponse> runResponse(GatewayExchange exchange, GatewayResponse response, int index) {
        if (index < 0) {
            return CompletableFuture.completedFuture(response);
        }
        Filter filter = filters.get(index);
        log.debug("[filter-chain] rid={} onResponse[{}] {}", exchange.requestId(), index, filter.getClass().getSimpleName());
        return filter.onResponse(exchange, response)
            .thenCompose(next -> runResponse(exchange, next, index - 1));
    }
}
