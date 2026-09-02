package org.example.gateway.day9.filter.bodylogger;

import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import org.example.gateway.day9.filter.RequestBodyCallback;
import org.example.gateway.day9.filter.RequestBodyFilter;
import org.example.gateway.day9.filter.ResponseBodyCallback;
import org.example.gateway.day9.filter.ResponseBodyFilter;

/**
 * Filters.java에서 분리됨. grill-me 세션에서 합의된 RequestBodyFilter/ResponseBodyFilter
 * 마커 인터페이스를 실제로 써보는 예시 필터 — 요청/응답 바디 길이만 로그로 남긴다.
 * 응답 캐싱 같은 실전 용도로 확장하기 전에, 두 훅이 배선대로 호출되는지 검증하는 용도.
 */
public class BodyLoggerFilter implements RequestBodyFilter, ResponseBodyFilter {

    @Override
    public void onRequestBody(RoutingContext ctx, Buffer body, RequestBodyCallback callback) {
        System.out.println("[bodyLogger] request body " + body.length() + " bytes: "
            + (body.length() == 0 ? "(empty)" : body.toString()));
        callback.forward(body);
    }

    @Override
    public void onResponseBody(RoutingContext ctx, Buffer body, ResponseBodyCallback callback) {
        System.out.println("[bodyLogger] response body " + body.length() + " bytes: " + body.toString());
        callback.forward(body);
    }
}
