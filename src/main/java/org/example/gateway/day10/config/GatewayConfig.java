package org.example.gateway.day10.config;

import org.example.gateway.day10.config.resource.ConnectorConfig;
import org.example.gateway.day10.config.resource.ListenerConfig;
import org.example.gateway.day10.config.resource.RouterConfig;

import java.util.List;

/** 파일에서 읽은 리소스 전체(kind별로 아직 서로 연결(참조 해석)되지 않은 상태). */
public record GatewayConfig(List<ListenerConfig> listeners, List<ConnectorConfig> connectors, List<RouterConfig> routers) {
}
