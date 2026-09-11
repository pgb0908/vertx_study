package org.example.gateway.day10.engine.connector;

import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayHeaders;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.route.GatewayRoute;
import org.example.gateway.day10.domain.upstream.EgressGroup;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.UpstreamClient;
import org.example.gateway.day10.domain.upstream.balance.RoundRobinLoadBalancer;
import org.example.gateway.day10.domain.upstream.resilience.CircuitBreaker;
import org.example.gateway.day10.domain.upstream.resilience.RetryPolicy;
import org.example.gateway.day10.domain.upstream.resilience.TimeoutPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * "해석 B"(고정 API 호출 템플릿) 핵심 규칙: Connector로 라우팅되면 클라이언트가
 * 실제로 보낸 method/path와 무관하게 항상 spec.method + spec.proxyPath로 백엔드를
 * 부른다. 쿼리스트링만 유지한다.
 */
class RuntimeConnectorTest {

    @Test
    void alwaysCallsBackendWithFixedMethodAndProxyPathRegardlessOfClientRequest() {
        GatewayRequest[] seen = new GatewayRequest[1];
        UpstreamClient client = (endpoint, request) -> {
            seen[0] = request;
            return CompletableFuture.completedFuture(response(200));
        };

        RuntimeConnector connector = connector("GET", "/products", client);
        GatewayRequest clientRequest = new GatewayRequest("POST", "/testapi1", GatewayHeaders.empty(), GatewayBody.EMPTY);

        connector.execute(exchange(clientRequest), clientRequest).join();

        assertEquals("GET", seen[0].method());
        assertEquals("/products", seen[0].uri());
    }

    @Test
    void preservesClientQueryStringWhenRewritingPath() {
        GatewayRequest[] seen = new GatewayRequest[1];
        UpstreamClient client = (endpoint, request) -> {
            seen[0] = request;
            return CompletableFuture.completedFuture(response(200));
        };

        RuntimeConnector connector = connector("GET", "/products", client);
        GatewayRequest clientRequest = new GatewayRequest("GET", "/testapi1?color=red&size=L",
            GatewayHeaders.empty(), GatewayBody.EMPTY);

        connector.execute(exchange(clientRequest), clientRequest).join();

        assertEquals("/products?color=red&size=L", seen[0].uri());
    }

    @Test
    void queryStringOmittedWhenClientSentNone() {
        GatewayRequest[] seen = new GatewayRequest[1];
        UpstreamClient client = (endpoint, request) -> {
            seen[0] = request;
            return CompletableFuture.completedFuture(response(200));
        };

        RuntimeConnector connector = connector("GET", "/products", client);
        GatewayRequest clientRequest = new GatewayRequest("GET", "/testapi1", GatewayHeaders.empty(), GatewayBody.EMPTY);

        connector.execute(exchange(clientRequest), clientRequest).join();

        assertEquals("/products", seen[0].uri());
    }

    @Test
    void headersAndBodyPassThroughUnchangedForBodyfulMethod() {
        GatewayRequest[] seen = new GatewayRequest[1];
        UpstreamClient client = (endpoint, request) -> {
            seen[0] = request;
            return CompletableFuture.completedFuture(response(200));
        };

        RuntimeConnector connector = connector("POST", "/products", client);
        GatewayHeaders headers = GatewayHeaders.builder().add("x-trace", "abc").build();
        GatewayBody body = GatewayBody.of("hello".getBytes());
        GatewayRequest clientRequest = new GatewayRequest("POST", "/testapi1", headers, body);

        connector.execute(exchange(clientRequest), clientRequest).join();

        assertEquals(List.of("abc"), seen[0].headers().get("x-trace"));
        assertEquals(body, seen[0].body());
    }

    @Test
    void getAndHeadFixedMethodsAlwaysForceEmptyBody() {
        // 실제로 겪은 버그: Vert.x HttpClient가 method=GET에 청크 바디를 실어 보내면
        // 응답이 즉시 와버리고 업로드가 전혀 진행되지 않았다(curl uploaded=0). 그래서
        // 고정 method가 GET/HEAD면 원본 바디가 뭐였든 항상 빈 바디로 바꿔치기한다.
        GatewayRequest[] seen = new GatewayRequest[1];
        UpstreamClient client = (endpoint, request) -> {
            seen[0] = request;
            return CompletableFuture.completedFuture(response(200));
        };

        RuntimeConnector connector = connector("GET", "/products", client);
        GatewayBody realBody = GatewayBody.of("this should be dropped".getBytes());
        GatewayRequest clientRequest = new GatewayRequest("POST", "/testapi1", GatewayHeaders.empty(), realBody);

        connector.execute(exchange(clientRequest), clientRequest).join();

        assertEquals(GatewayBody.EMPTY, seen[0].body());
    }

    private static RuntimeConnector connector(String method, String proxyPath, UpstreamClient client) {
        EgressGroup group = new EgressGroup("g", List.of(new Endpoint("localhost", 9001)));
        UpstreamExecutor executor = new UpstreamExecutor(
            client, RetryPolicy.none(), CircuitBreaker.disabled(), TimeoutPolicy.none());
        return new RuntimeConnector("connector-1", group, new RoundRobinLoadBalancer(), executor, method, proxyPath);
    }

    private static GatewayExchange exchange(GatewayRequest request) {
        return new GatewayExchange(request, new GatewayRoute("/testapi1", request.method(), List.of()));
    }

    private static GatewayResponse response(int statusCode) {
        return new GatewayResponse(statusCode, GatewayHeaders.empty(), GatewayBody.EMPTY);
    }
}
