package org.example.gateway.day10.config.resource;

/**
 * doc/Connector.md spec의 최소 실행 가능 부분집합.
 *
 * method/proxyPath는 "해석 B"로 쓴다 — 이 Connector로 라우팅된 요청은 클라이언트가
 * 실제로 보낸 method/path와 무관하게 항상 method + proxyPath로 백엔드를 호출한다
 * (고정 API 호출 템플릿, 투명 프록시 아님). 클라이언트 쿼리스트링은 유지해서
 * proxyPath 뒤에 그대로 붙인다 — RuntimeConnector.rewriteForFixedBackendCall 참고.
 *
 * upstreamTls/healthCheck/maxRequestBodySize/maxResponseBodySize/requestMsgTpl/
 * responseMsgTpl은 지금 전혀 읽지 않는다 — 능동 헬스체크, 바디 크기 강제, 업스트림
 * TLS, 메시지 템플릿 전부 아직 미구현(PLAN.md 참고).
 */
public record ConnectorConfig(String id, String name, String protocol, String proxyPath, String method,
                               LoadBalancingConfig loadBalancing, ResilienceSettings resilience) {
}
