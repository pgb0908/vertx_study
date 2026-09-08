package org.example.gateway.day10.engine;

import org.example.gateway.day10.domain.filter.Filter;
import org.example.gateway.day10.domain.filter.FilterResult;
import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayHeaders;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.route.GatewayRoute;
import org.example.gateway.day10.domain.upstream.CircuitBreaker;
import org.example.gateway.day10.domain.upstream.EgressGroup;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.RetryPolicy;
import org.example.gateway.day10.domain.upstream.RoundRobinLoadBalancer;
import org.example.gateway.day10.domain.upstream.TimeoutPolicy;
import org.example.gateway.day10.domain.upstream.UpstreamClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GatewayEngine이 Vert.x 없이(순수 JUnit5) route match -> Filter 체인 ->
 * upstream 순서를 지키는지 검증.
 */
class GatewayEngineTest {

    private static final Endpoint ENDPOINT = new Endpoint("localhost", 9999);

    @Test
    void runsOnRequestInOrderThenUpstreamThenOnResponseInReverse() {
        List<String> calls = new ArrayList<>();
        Filter first = recordingFilter(calls, "1");
        Filter second = recordingFilter(calls, "2");
        UpstreamClient upstream = recordingUpstream(calls);

        GatewayEngine engine = engine("/echo", List.of(first, second), upstream);
        GatewayResponse result = engine.execute(request("/echo")).join();

        assertEquals(200, result.statusCode());
        assertEquals(List.of("onRequest-1", "onRequest-2", "upstream", "onResponse-2", "onResponse-1"), calls);
    }

    @Test
    void noRouteMatchReturns404WithoutCallingUpstream() {
        List<String> calls = new ArrayList<>();
        UpstreamClient upstream = recordingUpstream(calls);

        GatewayEngine engine = engine("/echo", List.of(), upstream);
        GatewayResponse result = engine.execute(request("/nope")).join();

        assertEquals(404, result.statusCode());
        assertEquals(List.of(), calls);
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
        UpstreamClient upstream = recordingUpstream(calls);

        GatewayEngine engine = engine("/echo", List.of(abortingFilter, neverCalled), upstream);
        GatewayResponse result = engine.execute(request("/echo")).join();

        assertEquals(401, result.statusCode());
        assertEquals(List.of("onRequest-abort"), calls);
    }

    @Test
    void onRequestCanModifyRequestSeenByUpstream() {
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
        UpstreamClient upstream = (endpoint, req) -> {
            seenUris.add(req.uri());
            return CompletableFuture.completedFuture(response(200));
        };

        GatewayEngine engine = engine("/echo", List.of(rewritingFilter), upstream);
        engine.execute(request("/echo")).join();

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
        UpstreamClient upstream = recordingUpstream(calls);

        GatewayEngine engine = engine("/echo", List.of(failing), upstream);

        assertThrows(Exception.class, () -> engine.execute(request("/echo")).join());
        assertEquals(List.of(), calls);
    }

    private static GatewayEngine engine(String path, List<Filter> filters, UpstreamClient upstream) {
        EgressGroup group = new EgressGroup("test-upstream", List.of(ENDPOINT));
        GatewayRoute route = new GatewayRoute(path, group, filters);
        RuntimeRoute runtimeRoute = new RuntimeRoute(route, new RoundRobinLoadBalancer());
        RuntimeSnapshot snapshot = new RuntimeSnapshot(new RouteTable(List.of(runtimeRoute)));
        UpstreamExecutor executor = new UpstreamExecutor(
            upstream, RetryPolicy.none(), CircuitBreaker.disabled(), TimeoutPolicy.none());
        return new GatewayEngine(() -> snapshot, executor);
    }

    private static UpstreamClient recordingUpstream(List<String> calls) {
        return (endpoint, req) -> {
            calls.add("upstream");
            return CompletableFuture.completedFuture(response(200));
        };
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

    private static GatewayRequest request(String uri) {
        return new GatewayRequest("GET", uri, GatewayHeaders.empty(), GatewayBody.EMPTY);
    }

    private static GatewayResponse response(int statusCode) {
        return new GatewayResponse(statusCode, GatewayHeaders.empty(), GatewayBody.EMPTY);
    }
}
