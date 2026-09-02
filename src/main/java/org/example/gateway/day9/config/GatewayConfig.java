package org.example.gateway.day9.config;

import java.util.List;

public record GatewayConfig(List<RouteConfig> routes, UpstreamConfig upstreams) {
}
