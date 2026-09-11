package org.example.gateway.day10.config.resource;

/** doc/Connector.md spec.loadBalancing.targets[] 항목. */
public record ConnectorTarget(String host, int port, int weight) {
}
