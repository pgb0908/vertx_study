package org.example.gateway.day10.filter;

import org.example.gateway.day10.domain.filter.Filter;
import org.example.gateway.day10.domain.filter.FilterResult;
import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

public final class LoggingFilter extends Filter {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    @Override
    public CompletableFuture<FilterResult> onRequest(GatewayExchange exchange, GatewayRequest request) {
        log.info("[request]  rid={} {} {}", exchange.requestId(), request.method(), request.uri());
        return CompletableFuture.completedFuture(new FilterResult.Next(request));
    }

    @Override
    public CompletableFuture<GatewayResponse> onResponse(GatewayExchange exchange, GatewayResponse response) {
        log.info("[response] rid={} {} {} -> {}", exchange.requestId(),
            exchange.request().method(), exchange.request().uri(), response.statusCode());
        return CompletableFuture.completedFuture(response);
    }
}
