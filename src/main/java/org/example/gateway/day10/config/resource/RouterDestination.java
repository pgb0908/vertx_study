package org.example.gateway.day10.config.resource;

/**
 * doc/Router.md spec.destinations[] 항목을 해석한 결과 — destinationRef.kind가
 * Connector인 것만 지원한다(Flow는 스펙이 없어서 ConfigLoader가 거부한다).
 * connectorId는 destinationRef.id를 그대로 쓴다(uid/name은 정합성 검증 없이 무시).
 */
public record RouterDestination(String connectorId, int weight) {
}
