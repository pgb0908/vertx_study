package org.example.gateway.day10.runtime.vertx.resilience;

import io.vertx.circuitbreaker.OpenCircuitException;
import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;
import org.example.gateway.day10.domain.upstream.Endpoint;
import org.example.gateway.day10.domain.upstream.UpstreamClient;
import org.example.gateway.day10.domain.upstream.resilience.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * domain.upstream.RetryPolicy의 실제 구현체. {@link OpenCircuitException}을 직접
 * 참조하므로(회로가 이미 열렸으면 예산이 남았어도 재시도하지 않기 위해) domain이
 * 아니라 runtime.vertx에 둔다 — VertxCircuitBreaker와 같이 쓰일 때만 의미가 있는
 * 조합이라, day10 계층 원칙(도메인은 Vert.x 타입을 모른다)에도 맞는 위치다.
 *
 * 멱등 메서드(GET/HEAD/OPTIONS)만 재시도 예산을 받는다 — 요청 바디(GatewayBody)는
 * VertxReadStreamPublisher로 감싼 실제 클라이언트 업로드일 경우 Flow.Publisher라서
 * 한 번만 구독 가능하다(두 번째 subscribe()는 IllegalStateException). day9의
 * proxy.RetryPolicy가 같은 이유로 겪었던 문제와 동일 — 그래서 재시도 대상 요청은
 * 처음부터 끝까지(첫 시도 포함) GatewayBody.EMPTY로 보낸다. 멱등 메서드는 관례상
 * 바디가 없으므로 이 단순화는 안전하고, "첫 시도는 원본 바디/재시도는 빈 바디"를
 * 구분할 필요 자체가 없어진다.
 */
public final class VertxRetryPolicy implements RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(VertxRetryPolicy.class);
    private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final int maxRetries;

    public VertxRetryPolicy(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    @Override
    public CompletableFuture<GatewayResponse> execute(Endpoint endpoint, GatewayRequest request, UpstreamClient client) {
        int retriesLeft = IDEMPOTENT_METHODS.contains(request.method()) ? maxRetries : 0;
        GatewayRequest toSend = retriesLeft > 0
            ? new GatewayRequest(request.method(), request.uri(), request.headers(), GatewayBody.EMPTY)
            : request;
        return attempt(endpoint, toSend, client, retriesLeft);
    }

    private CompletableFuture<GatewayResponse> attempt(Endpoint endpoint, GatewayRequest request,
                                                         UpstreamClient client, int retriesLeft) {
        CompletableFuture<GatewayResponse> result = new CompletableFuture<>();

        client.execute(endpoint, request).whenComplete((response, err) -> {
            if (err == null) {
                result.complete(response);
                return;
            }
            Throwable cause = unwrap(err);
            if (shouldRetry(retriesLeft, cause)) {
                log.debug("[retry] attempt failed ({}), {} retries left -> retrying", cause.getMessage(), retriesLeft);
                attempt(endpoint, request, client, retriesLeft - 1)
                    .whenComplete((retriedResponse, retriedErr) -> {
                        if (retriedErr != null) {
                            result.completeExceptionally(retriedErr);
                        } else {
                            result.complete(retriedResponse);
                        }
                    });
            } else {
                result.completeExceptionally(cause);
            }
        });

        return result;
    }

    /** 회로가 이미 열렸으면 재시도 예산이 남았어도 재시도하지 않는다 — 즉시 또 실패할 뿐이므로. */
    private boolean shouldRetry(int retriesLeft, Throwable err) {
        return retriesLeft > 0 && !(err instanceof OpenCircuitException);
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }
}
