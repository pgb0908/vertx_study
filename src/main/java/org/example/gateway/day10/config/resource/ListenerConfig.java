package org.example.gateway.day10.config.resource;

/**
 * doc/Listener.md spec의 최소 실행 가능 부분집합. tls/connection/allowedHostnames는
 * 지금 전혀 읽지 않는다 — TLS 종단이 day10에 아직 없어서(PLAN.md 2차 항목), 그
 * 필드를 파싱해봐야 쓸 데가 없다. HTTPS/TCP/GRPC를 protocol로 주면 ConfigLoader가
 * 부팅 시점에 명확히 거부한다(조용히 HTTP로 대체하지 않는다).
 */
public record ListenerConfig(String id, String name, String protocol, int port, String host) {
}
