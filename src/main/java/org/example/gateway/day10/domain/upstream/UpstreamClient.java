package org.example.gateway.day10.domain.upstream;

import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;

import java.util.concurrent.CompletableFuture;

/**
 * GatewayEngine이 업스트림 호출을 위해 의존하는 순수 인터페이스. 구현체
 * (VertxUpstreamClient)만 Vert.x HttpClient를 안다 — engine은 이 인터페이스
 * 뒤에서 무슨 네트워크 라이브러리가 쓰이는지 모른다.
 */
public interface UpstreamClient {

    CompletableFuture<GatewayResponse> execute(Endpoint endpoint, GatewayRequest request);
}
