package org.example.gateway.day5.config;

import java.util.List;

public record GatewayConfig(List<RouteConfig> routes, UpstreamConfig upstreams) {
}
