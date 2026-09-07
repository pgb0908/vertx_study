package org.example.gateway.day10.engine;

import org.example.gateway.day10.domain.filter.Filter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * Proxygen의 onBody/sendBody 청크 변환에 대응.
 * GatewayBody(Flow.Publisher)의 각 청크를 필터 체인에 통과시킨다.
 *
 * requestDir=true  → 요청 바디: 필터 순서대로 (0→N) onRequestBody 적용
 * requestDir=false → 응답 바디: 필터 역순으로 (N→0) onResponseBody 적용
 *
 * backpressure: 구독 제어(request/cancel)를 sink에 직접 위임한다.
 */
final class FilterChainBodySubscriber implements Flow.Subscriber<byte[]> {

    private final Flow.Subscriber<? super byte[]> sink;
    private final List<Filter> filters;
    private final boolean requestDir;

    FilterChainBodySubscriber(Flow.Subscriber<? super byte[]> sink, List<Filter> filters, boolean requestDir) {
        this.sink = sink;
        this.filters = filters;
        this.requestDir = requestDir;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
        sink.onSubscribe(s);
    }

    @Override
    public void onNext(byte[] chunk) {
        applyChain(chunk).whenComplete((result, err) -> {
            if (err != null) sink.onError(err);
            else sink.onNext(result);
        });
    }

    private CompletableFuture<byte[]> applyChain(byte[] chunk) {
        CompletableFuture<byte[]> cf = CompletableFuture.completedFuture(chunk);
        if (requestDir) {
            for (Filter f : filters) {
                cf = cf.thenCompose(f::onRequestBody);
            }
        } else {
            for (int i = filters.size() - 1; i >= 0; i--) {
                final Filter f = filters.get(i);
                cf = cf.thenCompose(f::onResponseBody);
            }
        }
        return cf;
    }

    @Override public void onError(Throwable t) { sink.onError(t); }
    @Override public void onComplete() { sink.onComplete(); }
}
