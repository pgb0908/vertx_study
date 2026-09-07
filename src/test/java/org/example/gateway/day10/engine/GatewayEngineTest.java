package org.example.gateway.day10.engine;

import org.example.gateway.day10.domain.filter.Filter;
import org.example.gateway.day10.domain.filter.FilterResult;
import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayHeaders;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.route.GatewayRoute;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.UpstreamClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GatewayEngine이 Vert.x 없이(순수 JUnit5) Filter 체인 순서를 지키는지 검증.
 */
class GatewayEngineTest {

    private static final GatewayRoute ROUTE = new GatewayRoute("/echo", new Endpoint("localhost", 9999));

    @Test
    void runsOnRequestInOrderThenUpstreamThenOnResponseInReverse() {
        List<String> calls = new ArrayList<>();
        Filter first = recordingFilter(calls, "1");
        Filter second = recordingFilter(calls, "2");
        UpstreamClient upstream = (endpoint, request) -> {
            calls.add("upstream");
            return CompletableFuture.completedFuture(response(200));
        };

        GatewayEngine engine = new GatewayEngine(List.of(first, second), upstream);
        GatewayResponse result = engine.execute(exchange()).join();

        assertEquals(200, result.statusCode());
        assertEquals(List.of("onRequest-1", "onRequest-2", "upstream", "onResponse-2", "onResponse-1"), calls);
    }

    @Test
    void abortSkipsUpstreamAndRemainingFilters() {
        List<String> calls = new ArrayList<>();
        Filter abortingFilter = new Filter() {
            @Override
            public CompletableFuture<FilterResult> onRequest(GatewayExchange exchange, GatewayRequest request) {
                calls.add("onRequest-abort");
                return CompletableFuture.completedFuture(new FilterResult.Abort(response(401)));
            }
            @Override
            public CompletableFuture<GatewayResponse> onResponse(GatewayExchange exchange, GatewayResponse response) {
                calls.add("onResponse-abort");
                return CompletableFuture.completedFuture(response);
            }
        };
        Filter neverCalled = recordingFilter(calls, "never");
        UpstreamClient upstream = (endpoint, request) -> {
            calls.add("upstream");
            return CompletableFuture.completedFuture(response(200));
        };

        GatewayEngine engine = new GatewayEngine(List.of(abortingFilter, neverCalled), upstream);
        GatewayResponse result = engine.execute(exchange()).join();

        assertEquals(401, result.statusCode());
        assertEquals(List.of("onRequest-abort"), calls);
    }

    @Test
    void onRequestCanModifyRequest() {
        List<String> seenUris = new ArrayList<>();
        Filter rewritingFilter = new Filter() {
            @Override
            public CompletableFuture<FilterResult> onRequest(GatewayExchange exchange, GatewayRequest request) {
                GatewayRequest modified = new GatewayRequest(
                    request.method(), "/rewritten", request.headers(), request.body());
                return CompletableFuture.completedFuture(new FilterResult.Next(modified));
            }
            @Override
            public CompletableFuture<GatewayResponse> onResponse(GatewayExchange exchange, GatewayResponse response) {
                return CompletableFuture.completedFuture(response);
            }
        };
        UpstreamClient upstream = (endpoint, request) -> {
            seenUris.add(request.uri());
            return CompletableFuture.completedFuture(response(200));
        };

        GatewayEngine engine = new GatewayEngine(List.of(rewritingFilter), upstream);
        engine.execute(exchange()).join();

        assertEquals(List.of("/rewritten"), seenUris);
    }

    @Test
    void onRequestExceptionPropagates() {
        Filter failing = new Filter() {
            @Override
            public CompletableFuture<FilterResult> onRequest(GatewayExchange exchange, GatewayRequest request) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
            @Override
            public CompletableFuture<GatewayResponse> onResponse(GatewayExchange exchange, GatewayResponse response) {
                return CompletableFuture.completedFuture(response);
            }
        };
        List<String> calls = new ArrayList<>();
        UpstreamClient upstream = (endpoint, request) -> {
            calls.add("upstream");
            return CompletableFuture.completedFuture(response(200));
        };

        GatewayEngine engine = new GatewayEngine(List.of(failing), upstream);

        assertThrows(Exception.class, () -> engine.execute(exchange()).join());
        assertEquals(List.of(), calls);
    }

    private static Filter recordingFilter(List<String> calls, String name) {
        return new Filter() {
            @Override
            public CompletableFuture<FilterResult> onRequest(GatewayExchange exchange, GatewayRequest request) {
                calls.add("onRequest-" + name);
                return CompletableFuture.completedFuture(new FilterResult.Next(request));
            }
            @Override
            public CompletableFuture<GatewayResponse> onResponse(GatewayExchange exchange, GatewayResponse response) {
                calls.add("onResponse-" + name);
                return CompletableFuture.completedFuture(response);
            }
        };
    }

    private static GatewayExchange exchange() {
        GatewayRequest request = new GatewayRequest("GET", "/echo", GatewayHeaders.empty(), GatewayBody.EMPTY);
        return new GatewayExchange(request, ROUTE);
    }

    private static GatewayResponse response(int statusCode) {
        return new GatewayResponse(statusCode, GatewayHeaders.empty(), GatewayBody.EMPTY);
    }
}
