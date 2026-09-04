package org.example.gateway.day10.engine;

import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayHeaders;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.policy.GatewayPolicy;
import org.example.gateway.day10.domain.route.GatewayRoute;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.UpstreamClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * GatewayEngine이 Vert.x 없이(순수 JUnit5, Vertx 인스턴스 기동 없음) 정책 순서를
 * 지키는지 검증한다 — grill-me에서 합의한 "engine은 순수 unit test로 검증 가능해야
 * 한다"(원칙 6: 테스트 용이성)를 실제로 증명하는 테스트.
 */
class GatewayEngineTest {

    private static final GatewayRoute ROUTE = new GatewayRoute("/echo", new Endpoint("localhost", 9999));

    @Test
    void runsBeforePoliciesInOrderThenUpstreamThenAfterPoliciesInReverse() {
        List<String> calls = new ArrayList<>();
        GatewayPolicy first = recordingPolicy(calls, "1");
        GatewayPolicy second = recordingPolicy(calls, "2");
        UpstreamClient upstreamClient = (endpoint, request) -> {
            calls.add("upstream");
            return CompletableFuture.completedFuture(response(200));
        };

        GatewayEngine engine = new GatewayEngine(List.of(first, second), upstreamClient);
        GatewayResponse result = engine.execute(exchange()).join();

        assertEquals(200, result.statusCode());
        assertEquals(List.of("before-1", "before-2", "upstream", "after-2", "after-1"), calls);
    }

    @Test
    void beforePolicyFailureSkipsUpstreamCall() {
        List<String> calls = new ArrayList<>();
        GatewayPolicy failing = new GatewayPolicy() {
            @Override
            public CompletableFuture<Void> before(GatewayExchange exchange) {
                return CompletableFuture.failedFuture(new IllegalStateException("blocked"));
            }
        };
        UpstreamClient upstreamClient = (endpoint, request) -> {
            calls.add("upstream");
            return CompletableFuture.completedFuture(response(200));
        };

        GatewayEngine engine = new GatewayEngine(List.of(failing), upstreamClient);

        assertThrows(Exception.class, () -> engine.execute(exchange()).join());
        assertEquals(List.of(), calls);
    }

    private static GatewayPolicy recordingPolicy(List<String> calls, String name) {
        return new GatewayPolicy() {
            @Override
            public CompletableFuture<Void> before(GatewayExchange exchange) {
                calls.add("before-" + name);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<GatewayResponse> after(GatewayExchange exchange, GatewayResponse response) {
                calls.add("after-" + name);
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
