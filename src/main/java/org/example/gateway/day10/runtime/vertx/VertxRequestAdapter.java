package org.example.gateway.day10.runtime.vertx;

import io.vertx.core.http.HttpServerRequest;
import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayHeaders;
import org.example.gateway.day10.domain.model.GatewayRequest;

/**
 * HttpServerRequest -> GatewayRequest. 이 파일과 VertxResponseWriter가 이
 * 서브시스템에서 Vert.x HttpServerRequest/Response 타입을 직접 만지는 유일한
 * 지점이다 (Arch.md 8절: Router는 Adapter 역할만).
 */
final class VertxRequestAdapter {

    private VertxRequestAdapter() {
    }

    static GatewayRequest adapt(HttpServerRequest request) {
        GatewayHeaders headers = GatewayHeaders.empty();
        request.headers().forEach(entry -> headers.add(entry.getKey(), entry.getValue()));
        GatewayBody body = new VertxReadStreamPublisher(request)::subscribe;
        return new GatewayRequest(request.method().name(), request.uri(), headers, body);
    }
}
