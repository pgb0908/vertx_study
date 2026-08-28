package org.example.gateway.day6.ratelimit;

import java.util.HashMap;
import java.util.Map;

/**
 * 토큰 버킷(token bucket) 알고리즘. 클라이언트(clientId)마다 버킷을 하나씩 두고,
 * 시간이 지날수록 토큰이 초당 requestsPerSecond만큼 서서히 채워진다. 요청 하나당
 * 토큰 1개를 소모하며, burstSize까지는 순간적으로 몰아 써도 허용된다(버스트 트래픽).
 *
 * MainVerticle의 이벤트 루프 스레드에서만 호출된다 — day4/day5에서 확인했던
 * "쓰는 곳과 읽는 곳이 항상 같은 스레드"와 동일한 이유로, 별도 동기화
 * (synchronized, ConcurrentHashMap) 없이 평범한 HashMap으로 충분히 안전하다.
 */
public class RateLimiter {

    private final double capacity;
    private final double refillPerNano;
    private final Map<String, Bucket> buckets = new HashMap<>();

    public RateLimiter(double requestsPerSecond, int burstSize) {
        this.capacity = burstSize;
        this.refillPerNano = requestsPerSecond / 1_000_000_000.0;
    }

    public boolean tryAcquire(String clientId) {
        Bucket bucket = buckets.computeIfAbsent(clientId, id -> new Bucket(capacity));
        refill(bucket);
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** 429 응답에 Retry-After로 실어보낼, "최소 몇 초 뒤에 다시 시도하면 되는지" 추정치. */
    public int retryAfterSeconds(String clientId) {
        Bucket bucket = buckets.get(clientId);
        if (bucket == null || refillPerNano <= 0) {
            return 1;
        }
        double missingTokens = 1.0 - bucket.tokens;
        double secondsNeeded = missingTokens / (refillPerNano * 1_000_000_000.0);
        return Math.max(1, (int) Math.ceil(secondsNeeded));
    }

    private void refill(Bucket bucket) {
        long now = System.nanoTime();
        double elapsedNanos = now - bucket.lastRefillNanos;
        bucket.tokens = Math.min(capacity, bucket.tokens + elapsedNanos * refillPerNano);
        bucket.lastRefillNanos = now;
    }

    private static final class Bucket {
        double tokens;
        long lastRefillNanos = System.nanoTime();

        Bucket(double initialTokens) {
            this.tokens = initialTokens;
        }
    }
}
