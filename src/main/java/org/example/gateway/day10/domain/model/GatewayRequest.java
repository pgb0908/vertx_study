package org.example.gateway.day10.domain.model;

public record GatewayRequest(String method, String uri, GatewayHeaders headers, GatewayBody body) {
}
