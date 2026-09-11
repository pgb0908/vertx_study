package org.example.gateway.day10.engine;

import org.example.gateway.day10.domain.filter.FilterResult;
import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayHeaders;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.engine.connector.RuntimeConnector;
import org.example.gateway.day10.engine.route.RuntimeRoute;
import org.example.gateway.day10.engine.route.RuntimeSnapshot;
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
 * execute()는 이 5단계를 그대로 나열하고, 각 단계는 private 메서드로 분리했다 —
 * 다이어그램의 박스 하나가 메서드 하나에 대응하도록 해서, 중첩된 람다 안에
 * 흐름이 숨지 않게 하기 위해서다.
 *
 * GatewayEngine 자신은 상태를 갖지 않는다(feedback 6절) — snapshot은 요청마다
 * 바뀌지 않는 설정이고, 거래별 상태는 전부 GatewayExchange가 가진다. 라우트마다
 * 다른 FilterChain/목적지(Connector, 가중치 분산 포함)를 쓸 수 있도록, 이 둘은
 * RuntimeRoute(라우트 매칭 결과)에서 꺼내 쓴다 — Engine 생성자에 고정하지 않는다.
 * Retry/CircuitBreaker/Timeout도 이제 Engine이 아니라 Connector마다 따로 갖는다
 * (Connector.md의 resilience 설정이 Connector 단위이므로) — RuntimeConnector 참고.
 */
public final class GatewayEngine {

    private static final Logger log = LoggerFactory.getLogger(GatewayEngine.class);

    private final Supplier<RuntimeSnapshot> snapshot;

    public GatewayEngine(Supplier<RuntimeSnapshot> snapshot) {
        this.snapshot = snapshot;
    }

    public CompletableFuture<GatewayResponse> execute(GatewayRequest request) {
        Optional<RuntimeRoute> matched = snapshot.get().routeTable().match(request.method(), request.uri());
        if (matched.isEmpty()) {
            log.debug("[engine] no route matches {} {}", request.method(), request.uri());
            return CompletableFuture.completedFuture(notFound());
        }

        RuntimeRoute runtimeRoute = matched.get();
        GatewayExchange exchange = new GatewayExchange(request, runtimeRoute.route());
        log.debug("[engine] rid={} start {} {}", exchange.requestId(), request.method(), request.uri());

        return runRequestFilters(exchange, runtimeRoute)
            .whenComplete((response, err) -> logCompletion(exchange, response, err));
    }

    /**
     * 1단계: Filter.onRequest()를 0→N 순서로 실행(feedback 3절의 downstream 방향).
     * "upstream/downstream"이라는 이름은 UpstreamClient/UpstreamExecutor(백엔드 서버
     * 자체를 가리킴)와 헷갈리기 쉬워서, 여기서는 Filter의 실제 메서드명(onRequest/
     * onResponse)을 따라 request/response로 부른다 — FilterChain.executeDownstream()
     * 은 feedback 문서 용어를 그대로 유지한다(그쪽엔 이 혼동이 없음).
     * Abort면 백엔드 호출 없이 즉시 끝내고, 아니면 2단계로.
     */
    private CompletableFuture<GatewayResponse> runRequestFilters(GatewayExchange exchange, RuntimeRoute runtimeRoute) {
        FilterChain filterChain = runtimeRoute.filterChain();
        return filterChain.executeDownstream(exchange, exchange.request())
            .thenCompose(result -> result instanceof FilterResult.Abort abort
                ? abort(exchange, abort)
                : callUpstream(exchange, runtimeRoute, filterChain, ((FilterResult.Next) result).request()));
    }

    private CompletableFuture<GatewayResponse> abort(GatewayExchange exchange, FilterResult.Abort abort) {
        log.debug("[engine] rid={} aborted -> status={}", exchange.requestId(), abort.response().statusCode());
        exchange.response(abort.response());
        return CompletableFuture.completedFuture(abort.response());
    }

    /** 2단계: Connector 선택(가중치 분산 포함) → 3단계: Upstream 호출(그 Connector 전용
     *  Retry/CircuitBreaker/Timeout 포함, RuntimeConnector가 담당). */
    private CompletableFuture<GatewayResponse> callUpstream(GatewayExchange exchange, RuntimeRoute runtimeRoute,
                                                              FilterChain filterChain, GatewayRequest downstreamResult) {
        exchange.request(downstreamResult);
        log.debug("[engine] rid={} request filters done -> selecting destination", exchange.requestId());

        GatewayRequest withBody = withWrappedRequestBody(downstreamResult, filterChain);
        RuntimeConnector connector = runtimeRoute.connectorSelector().select();
        return connector.execute(exchange, withBody)
            .thenCompose(rawResponse -> runResponseFilters(exchange, filterChain, rawResponse));
    }

    /** 4단계: Filter.onResponse()를 N→0 역순으로 실행(feedback 3절의 upstream 방향). */
    private CompletableFuture<GatewayResponse> runResponseFilters(GatewayExchange exchange, FilterChain filterChain,
                                                                    GatewayResponse rawResponse) {
        log.debug("[engine] rid={} backend responded status={} -> response filters", exchange.requestId(), rawResponse.statusCode());
        exchange.upstreamResponse(rawResponse);

        GatewayResponse withBody = withWrappedResponseBody(rawResponse, filterChain);
        return filterChain.executeUpstream(exchange, withBody)
            .thenApply(finalResponse -> {
                exchange.response(finalResponse);
                return finalResponse;
            });
    }

    private void logCompletion(GatewayExchange exchange, GatewayResponse response, Throwable err) {
        if (err != null) {
            log.error("[engine] rid={} failed: {}", exchange.requestId(), err.getMessage(), err);
        } else {
            log.debug("[engine] rid={} complete status={}", exchange.requestId(), response.statusCode());
        }
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
