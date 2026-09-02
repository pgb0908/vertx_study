package org.example.gateway.day9.filter;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

/**
 * GatewayFilter(우리 인터페이스)를 Router.route().handler()가 받는 Handler<RoutingContext>
 * (Vert.x 인터페이스)로 변환하는 유일한 지점. 이 어댑터 밖에서는 어떤 필터 구현체도
 * ctx.next()나 addHeadersEndHandler를 직접 호출하지 않는다 — Vert.x 종속성이 이 파일
 * 하나로 격리된다.
 */
public final class GatewayFilterAdapter {

    private GatewayFilterAdapter() {
    }

    public static Handler<RoutingContext> toRouteHandler(GatewayFilter filter) {
        return ctx -> {
            // 응답 단계(encodeHeaders) 훅은 요청이 들어온 시점에 미리 걸어둔다. 이 필터
            // 자신이 응답을 만드는 게 아니어도(대개는 훨씬 뒤에 프록시 핸들러가 만듦)
            // addHeadersEndHandler는 "누가 응답을 썼든" 헤더가 나가기 직전에 호출되므로
            // 여기서 등록만 해두면 충분하다. 또한 여러 필터가 각자 등록해도 Vert.x가
            // 등록 역순으로 호출해주므로(RoutingContextImpl.headersEndHandlers.invokeInReverseOrder),
            // 응답은 자동으로 "요청의 역순" 온ion 모델이 된다 — 우리가 따로 구현할 필요 없음.
            ctx.addHeadersEndHandler(v -> filter.onResponseHeaders(ctx));

            filter.onRequestHeaders(ctx, new FilterCallback() {
                @Override
                public void continueRequest() {
                    // grill-me에서 합의: 이 필터가 RequestBodyFilter도 구현했다면, 헤더
                    // 단계를 통과한 뒤 바로 next()로 넘기지 않고 (이미 BodyHandler가
                    // 버퍼링해둔) ctx.body()를 읽어서 onRequestBody도 호출한다.
                    if (filter instanceof RequestBodyFilter bodyFilter) {
                        // GET처럼 바디가 없는 요청은 BodyHandler가 실행돼도 ctx.body().buffer()가
                        // null을 반환한다(실제로 NPE로 확인했다) — 필터 작성자가 매번 null 체크를
                        // 하지 않도록, 여기서 빈 Buffer로 정규화해서 넘긴다.
                        Buffer body = ctx.body().buffer();
                        bodyFilter.onRequestBody(ctx, body != null ? body : Buffer.buffer(), new RequestBodyCallback() {
                            @Override
                            public void forward(Buffer body) {
                                ctx.next();
                            }

                            @Override
                            public void stopWithResponse() {
                                // no-op — FilterCallback.stopWithResponse()와 같은 이유.
                            }
                        });
                    } else {
                        ctx.next();
                    }
                }

                @Override
                public void stopWithResponse() {
                    // 아무 것도 안 함 — 필터가 이미 ctx.response()를 끝냈으므로 ctx.next()를
                    // 호출하지 않는 것 자체가 "체인 중단"이다.
                }
            });
        };
    }
}
