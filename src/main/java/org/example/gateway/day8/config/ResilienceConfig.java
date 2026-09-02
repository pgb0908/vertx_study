package org.example.gateway.day8.config;

/**
 * day6에는 없던 신규 파일. rateLimit과 같은 이유로 라우트 전용 필드로 둔다 —
 * 회로차단기 파라미터(임계치, 타임아웃, 재시도 횟수)도 라우트마다 다르기 때문이다.
 */
public record ResilienceConfig(int maxFailures, long timeoutMs, long resetTimeoutMs, int maxRetries) {
}
