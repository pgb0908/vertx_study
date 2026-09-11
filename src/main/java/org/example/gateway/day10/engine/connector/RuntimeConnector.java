package org.example.gateway.day10.engine.connector;

import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.upstream.EgressGroup;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.balance.LoadBalancer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Connector 설정(config)을 컴파일한 실행 가능 형태 — endpoint 선택(LoadBalancer)과
 * 그 Connector 전용 execution policy(UpstreamExecutor: Retry/CircuitBreaker/Timeout)를
 * 함께 갖는다. Connector마다 resilience 설정이 다르므로(Connector.md spec.retry/
 * circuitBreaker/timeout) UpstreamExecutor를 Connector 단위로 따로 둔다 — 예전처럼
 * GatewayEngine 전체가 UpstreamExecutor 하나를 공유하지 않는다.
 *
 * Connector.md의 spec.method/spec.proxyPath는 "해석 B"(고정 API 호출 템플릿)로
 * 적용한다 — 클라이언트가 실제로 보낸 method/path가 무엇이든, 이 Connector로
 * 라우팅되면 항상 spec.method + spec.proxyPath로 백엔드를 호출한다(투명 프록시가
 * 아니다). 클라이언트의 쿼리스트링은 유지해서 proxyPath 뒤에 그대로 붙인다.
 *
 * 고정 method가 GET/HEAD면 원본 바디를 절대 그대로 안 실어 보낸다 — 실제로 겪은
 * 버그: Vert.x HttpClient에 method=GET으로 청크 바디를 스트리밍했더니, 응답이
 * 즉시(수 ms 만에) 와버리고 클라이언트 쪽 업로드는 전혀 진행되지 않았다(curl
 * uploaded=0). Vert.x가 GET을 바디 없는 요청으로 가정하고 프레이밍을 앞당겨
 * 끝내버리는 것으로 보인다. day9/VertxRetryPolicy가 멱등 메서드에 항상 빈 바디를
 * 쓰는 것과 같은 이유로, 여기서도 GET/HEAD면 GatewayBody.EMPTY로 바꿔치기한다.
 */
public final class RuntimeConnector {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConnector.class);
    private static final Set<String> BODYLESS_METHODS = Set.of("GET", "HEAD");

    private final String id;
    private final EgressGroup egressGroup;
    private final LoadBalancer loadBalancer;
    private final UpstreamExecutor upstreamExecutor;
    private final String method;
    private final String proxyPath;

    public RuntimeConnector(String id, EgressGroup egressGroup, LoadBalancer loadBalancer,
                             UpstreamExecutor upstreamExecutor, String method, String proxyPath) {
        this.id = id;
        this.egressGroup = egressGroup;
        this.loadBalancer = loadBalancer;
        this.upstreamExecutor = upstreamExecutor;
        this.method = method;
        this.proxyPath = proxyPath;
    }

    public String id() {
        return id;
    }

    public CompletableFuture<GatewayResponse> execute(GatewayExchange exchange, GatewayRequest request) {
        Endpoint endpoint = loadBalancer.select(egressGroup, exchange);
        GatewayRequest rewritten = rewriteForFixedBackendCall(request);
        log.debug("[connector:{}] rid={} calling backend {} {} -> {}", id, exchange.requestId(),
            rewritten.method(), rewritten.uri(), endpoint);
        return upstreamExecutor.execute(endpoint, rewritten);
    }

    private GatewayRequest rewriteForFixedBackendCall(GatewayRequest request) {
        int queryStart = request.uri().indexOf('?');
        String query = queryStart >= 0 ? request.uri().substring(queryStart) : "";
        GatewayBody body = BODYLESS_METHODS.contains(method) ? GatewayBody.EMPTY : request.body();
        return new GatewayRequest(method, proxyPath + query, request.headers(), body);
    }
}
