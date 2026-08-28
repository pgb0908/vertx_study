package org.example.gateway.day6.config;

import java.util.List;

public record GatewayConfig(List<RouteConfig> routes, UpstreamConfig upstreams) {
}
