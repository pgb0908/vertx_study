package org.example.gateway.day8.config;

public record RateLimitConfig(double requestsPerSecond, int burstSize) {
}
