package org.example.gateway.day6.config;

public record RateLimitConfig(double requestsPerSecond, int burstSize) {
}
