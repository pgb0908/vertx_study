package org.example.gateway.day10.domain.model;

public record GatewayResponse(int statusCode, GatewayHeaders headers, GatewayBody body) {
}
