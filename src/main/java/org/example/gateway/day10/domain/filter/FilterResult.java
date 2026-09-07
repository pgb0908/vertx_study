package org.example.gateway.day10.domain.filter;

import org.example.gateway.day10.domain.model.GatewayRequest;
import org.example.gateway.day10.domain.model.GatewayResponse;

/**
 * onRequest()의 반환값. Proxygen의 "upstream_->onRequest()를 호출하지 않으면 중단"에
 * 대응하는 Java 타입 — Next면 체인 계속, Abort면 즉시 응답.
 */
public sealed interface FilterResult permits FilterResult.Next, FilterResult.Abort {

    /** 체인 계속 — 수정된 request를 다음 필터로 전달 */
    record Next(GatewayRequest request) implements FilterResult {}

    /** 체인 중단 — upstream 호출 없이 이 response를 클라이언트에 즉시 반환 */
    record Abort(GatewayResponse response) implements FilterResult {}
}
