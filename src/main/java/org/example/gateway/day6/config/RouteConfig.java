package org.example.gateway.day6.config;

import java.util.List;

/**
 * day5와 다른 점: rateLimit이 추가됐다. upstreamGroup과 같은 이유로 filters
 * 목록(이름으로 조회하는 공유 필터)이 아니라 별도 필드로 뒀다 — rateLimit은
 * 라우트마다 파라미터(requestsPerSecond, burstSize)가 달라서 이름만으로 공유
 * 조회할 수 없기 때문이다.
 */
public record RouteConfig(String path, List<String> filters, String upstreamGroup, RateLimitConfig rateLimit) {
}
