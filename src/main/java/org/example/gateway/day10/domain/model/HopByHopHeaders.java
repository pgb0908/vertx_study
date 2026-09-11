package org.example.gateway.day10.domain.model;

import java.util.HashSet;
import java.util.Set;

/**
 * 프록시가 다음 hop으로 그대로 넘기면 안 되는 헤더들. day9의
 * {@code proxy.HopByHopHeaders}와 동일한 고정 목록에서 시작하되, RFC 7230 §6.1이
 * 정의하는 "Connection 헤더 값 자체가 추가 hop-by-hop 헤더 이름을 나열할 수 있다"는
 * 규칙까지 반영한다 — day9엔 없던 부분이다.
 */
public final class HopByHopHeaders {

    private static final Set<String> FIXED_NAMES = Set.of(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
        "content-length",
        "host"
    );

    private HopByHopHeaders() {
    }

    /**
     * 고정 목록 + 이 요청/응답의 {@code Connection} 헤더 값이 추가로 지정한 헤더 이름들을
     * 합친 집합. 예: {@code Connection: close, X-Internal-Trace-Id}이면 "close"는 연결
     * 재사용 여부 지시고, "x-internal-trace-id"는 이 요청에 한해 hop-by-hop 취급하라는 뜻.
     */
    public static Set<String> namesFor(GatewayHeaders headers) {
        Set<String> names = new HashSet<>(FIXED_NAMES);
        for (String connectionValue : headers.get("connection")) {
            for (String token : connectionValue.split(",")) {
                String trimmed = token.trim().toLowerCase();
                if (!trimmed.isEmpty()) {
                    names.add(trimmed);
                }
            }
        }
        return names;
    }
}
