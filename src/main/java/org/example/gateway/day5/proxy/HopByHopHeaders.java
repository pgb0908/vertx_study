package org.example.gateway.day5.proxy;

import java.util.Set;

/**
 * 프록시가 그대로 전달하면 안 되는(홉 단위로만 유효한) 헤더들.
 * content-length/transfer-encoding은 새 요청/응답의 바디 전송 방식에 맞춰
 * Vert.x가 다시 계산하도록 제거한다.
 */
final class HopByHopHeaders {

    static final Set<String> NAMES = Set.of(
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
}
