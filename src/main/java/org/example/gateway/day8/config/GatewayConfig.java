package org.example.gateway.day8.config;

import java.util.List;

public record GatewayConfig(List<RouteConfig> routes, UpstreamConfig upstreams) {
}
