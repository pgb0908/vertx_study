package org.example.gateway.day9.filter;

/**
 * onRequestHeaders가 체인의 다음 단계로 넘어가는 방법을 이 콜백으로만 표현하게 해서,
 * 필터 구현체가 Vert.x의 ctx.next()를 직접 알 필요가 없게 한다 — "vertx를 한 단계
 * 감싸는 인터페이스"의 핵심. 실제 ctx.next() 호출은 GatewayFilterAdapter 안에 숨어있다.
 */
public interface FilterCallback {

    /** 검증/처리를 통과했으니 다음 필터(또는 프록시)로 진행한다. */
    void continueRequest();

    /**
     * 이 필터가 이미 ctx.response()에 응답을 다 써서(예: 401/429) 체인을 여기서 끝냈다는
     * 표시. 실제로 응답을 쓰는 것은 필터의 책임이고, 이 메서드는 "더 이상 다음 필터를
     * 호출하지 마라"는 신호만 준다 (Envoy의 FilterHeadersStatus.StopIteration과 동일한 의도).
     */
    void stopWithResponse();
}
