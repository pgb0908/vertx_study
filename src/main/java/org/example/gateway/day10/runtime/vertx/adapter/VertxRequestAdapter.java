package org.example.gateway.day10.runtime.vertx.adapter;

import io.vertx.core.http.HttpServerRequest;
import org.example.gateway.day10.domain.model.GatewayBody;
import org.example.gateway.day10.domain.model.GatewayHeaders;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.runtime.vertx.stream.VertxReadStreamPublisher;

import java.util.List;

/**
 * HttpServerRequest -> GatewayRequest. 이 파일과 VertxResponseWriter가 이
 * 서브시스템에서 Vert.x HttpServerRequest/Response 타입을 직접 만지는 유일한
 * 지점이다 (Arch.md 8절: Router는 Adapter 역할만).
 *
 * 프록시를 거치면 백엔드 입장에서는 접속해온 게 게이트웨이지 원래 클라이언트가
 * 아니다. 그래서 원본 정보를 X-Forwarded-* 헤더로 실어준다 — 이미 앞선 프록시를
 * 거쳐 X-Forwarded-For가 붙어 있으면 덮어쓰지 않고 콤마로 이어붙인다(표준 프록시
 * 체인 관례).
 */
public final class VertxRequestAdapter {

    private VertxRequestAdapter() {
    }

    public static GatewayRequest adapt(HttpServerRequest request) {
        GatewayHeaders.Builder builder = GatewayHeaders.builder();
        request.headers().forEach(entry -> builder.add(entry.getKey(), entry.getValue()));
        GatewayHeaders original = builder.build();

        String clientIp = request.remoteAddress() != null ? request.remoteAddress().host() : "unknown";
        List<String> existingForwardedFor = original.get("x-forwarded-for");
        String forwardedFor = existingForwardedFor.isEmpty()
            ? clientIp
            : String.join(", ", existingForwardedFor) + ", " + clientIp;
        // 원본 Host 헤더는 이미 파싱해둔 헤더 목록에 있으니, deprecated된
        // HttpServerRequest.host()를 부르는 대신 그걸 그대로 쓴다.
        List<String> hostHeader = original.get("host");

        GatewayHeaders.Builder finalHeaders = GatewayHeaders.builder();
        original.asMap().forEach((name, values) -> {
            if (!"x-forwarded-for".equalsIgnoreCase(name)) {
                values.forEach(v -> finalHeaders.add(name, v));
            }
        });
        finalHeaders.add("x-forwarded-for", forwardedFor);
        finalHeaders.add("x-forwarded-proto", request.isSSL() ? "https" : "http");
        if (!hostHeader.isEmpty()) {
            finalHeaders.add("x-forwarded-host", hostHeader.get(0));
        }

        GatewayBody body = new VertxReadStreamPublisher(request)::subscribe;
        return new GatewayRequest(request.method().name(), request.uri(), finalHeaders.build(), body);
    }
}
