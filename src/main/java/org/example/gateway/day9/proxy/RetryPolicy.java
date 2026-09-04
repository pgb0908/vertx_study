package org.example.gateway.day9.proxy;

import io.vertx.circuitbreaker.OpenCircuitException;
import io.vertx.core.http.HttpMethod;

import java.util.Set;

/**
 * improve-codebase-architecture 세션에서 ProxyHandlerFactory로부터 분리됨. "재시도할지
 * 말지"를 결정하는 로직이 이전엔 세 곳(forResilientGroup의 멱등 메서드 체크, forwardOnce의
 * 5xx 실패 판정, attempt의 OpenCircuitException 제외)에 흩어져 있었다 — 이 클래스가
 * 그 세 조각을 하나로 모은다. 전부 순수 함수(입력값만으로 결정, HTTP/Vert.x 상태를
 * 직접 만지지 않음)라 Promise나 RoutingContext 없이 단위테스트가 가능하다.
 */
public final class RetryPolicy {

    private static final Set<HttpMethod> IDEMPOTENT_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private RetryPolicy() {
    }

    /** 멱등 메서드만 재시도 예산을 받는다 — 비멱등(POST 등)은 처음부터 0(day7에서 합의한 이유:
     * 요청 바디는 한 번만 스트리밍 가능해서 재전송이 위험). */
    public static int initialRetriesLeft(HttpMethod method, int maxRetries) {
        return IDEMPOTENT_METHODS.contains(method) ? maxRetries : 0;
    }

    /** 업스트림이 응답은 했지만(연결 자체는 성공) 회로차단기 입장에서 "실패"로 봐야 하는지. */
    public static boolean isUpstreamFailure(int statusCode) {
        return statusCode >= 500;
    }

    /** 이 실패 이후에도 재시도할 여지가 있는지 — 회로가 이미 열렸으면(OpenCircuitException)
     * 재시도해봐야 즉시 또 실패할 뿐이므로 예산이 남았어도 재시도하지 않는다. */
    public static boolean shouldRetry(int retriesLeft, Throwable err) {
        return retriesLeft > 0 && !(err instanceof OpenCircuitException);
    }
}
