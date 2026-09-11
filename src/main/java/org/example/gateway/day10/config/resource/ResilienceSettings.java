package org.example.gateway.day10.config.resource;

/**
 * doc/Connector.md spec.retry/circuitBreaker/timeout을 지금 day10이 실제로
 * 갖고 있는 단순한 실행 모델(VertxCircuitBreaker/VertxRetryPolicy — maxFailures/
 * timeoutMs/resetTimeoutMs/maxRetries)로 매핑한 결과. retryOn 조건, retryBackoff,
 * perTryTimeout, timeout.connect/timeout.send는 지금 파싱만 하고 반영하지 않는다
 * (PLAN.md에 기록된 의도적 단순화 — 나중에 VertxRetryPolicy/VertxCircuitBreaker
 * 자체를 확장해야 온전히 쓸 수 있다).
 *
 * timeoutMs 기본값이 day7/8/9(500ms)와 다른 5000ms인 이유: day10은 요청 바디를
 * 항상 스트리밍 그대로 통과시켜서, 대용량 업로드 자체가 500ms보다 오래 걸릴 수
 * 있다(실측 확인됨) — 이 프로젝트의 기존 결정을 그대로 기본값에 반영한다.
 */
public record ResilienceSettings(int maxFailures, long timeoutMs, long resetTimeoutMs, int maxRetries) {

    public static ResilienceSettings defaults() {
        return new ResilienceSettings(2, 5000, 3000, 3);
    }
}
