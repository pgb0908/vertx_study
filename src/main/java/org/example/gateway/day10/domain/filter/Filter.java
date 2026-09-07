package org.example.gateway.day10.domain.filter;

import org.example.gateway.day10.domain.model.GatewayExchange;
import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;

import java.util.concurrent.CompletableFuture;

/**
 * Proxygen Filter에 대응하는 Gateway 필터 추상 클래스.
 *
 * onRequest  ↔ Proxygen onRequest  — 요청 헤더 처리, 수정된 request 반환 또는 Abort
 * onResponse ↔ Proxygen sendHeaders — 응답 헤더 처리, 수정된 response 반환
 * onRequestBody  ↔ Proxygen onBody(요청 방향) — 요청 바디 청크 변환
 * onResponseBody ↔ Proxygen sendBody(응답 방향) — 응답 바디 청크 변환
 *
 * upstream/downstream 포인터는 GatewayEngine이 체인 구성 시 주입한다.
 * 현재 실행 흐름은 Engine이 인덱스로 오케스트레이션하고, 포인터는 향후
 * 동적 삽입/제거 시 활용한다.
 */
public abstract class Filter {

    /** 요청 방향 다음 필터 — GatewayEngine이 설정 */
    private Filter upstream;

    /** 응답 방향 이전 필터 — GatewayEngine이 설정 */
    private Filter downstream;

    public final void linkUpstream(Filter upstream) { this.upstream = upstream; }
    public final void linkDownstream(Filter downstream) { this.downstream = downstream; }

    /**
     * 요청 헤더 처리.
     * Next(request) 반환 → 체인 계속, 수정된 request가 다음 필터로 전달됨.
     * Abort(response) 반환 → 체인 중단, upstream 호출 없이 즉시 응답.
     */
    public abstract CompletableFuture<FilterResult> onRequest(GatewayExchange exchange, GatewayRequest request);

    /**
     * 요청 바디 청크 변환. 기본값: 청크 그대로 통과.
     * GatewayEngine이 요청 바디의 각 청크를 필터 순서(0→N)로 통과시킨다.
     */
    public CompletableFuture<byte[]> onRequestBody(byte[] chunk) {
        return CompletableFuture.completedFuture(chunk);
    }

    /**
     * 응답 헤더 처리. 수정된 GatewayResponse를 반환하면 다음 필터로 전달된다.
     */
    public abstract CompletableFuture<GatewayResponse> onResponse(GatewayExchange exchange, GatewayResponse response);

    /**
     * 응답 바디 청크 변환. 기본값: 청크 그대로 통과.
     * GatewayEngine이 응답 바디의 각 청크를 역순(N→0)으로 통과시킨다.
     */
    public CompletableFuture<byte[]> onResponseBody(byte[] chunk) {
        return CompletableFuture.completedFuture(chunk);
    }
}
