package org.example.gateway.day9.config;

public record RateLimitConfig(double requestsPerSecond, int burstSize) {
}
