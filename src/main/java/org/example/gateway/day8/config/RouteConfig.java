package org.example.gateway.day8.config;

import java.util.List;

/**
 * day6와 다른 점: resilience(회로차단기/타임아웃/재시도)가 추가됐다. rateLimit과
 * 같은 이유로 별도 필드로 뒀다 — 라우트마다 임계치·타임아웃·재시도 횟수가 다르다.
 */
public record RouteConfig(String path, List<String> filters, String upstreamGroup, RateLimitConfig rateLimit,
                           ResilienceConfig resilience) {
}
