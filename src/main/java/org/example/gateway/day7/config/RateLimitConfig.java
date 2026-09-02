package org.example.gateway.day7.config;

public record RateLimitConfig(double requestsPerSecond, int burstSize) {
}
