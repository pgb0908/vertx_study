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
 * HTTP body는 청크 순서가 절대 바뀌면 안 된다. 필터의 onRequestBody/onResponseBody는
 * CompletableFuture를 반환하는 비동기 작업이라, chunk별 처리 시간이 다르면(예:
 * A=30ms, B=5ms, C=10ms) upstream이 여러 chunk를 연달아 밀어넣는 경우 처리가
 * 겹쳐 실행되어 B, C, A 순서로 sink에 도달할 수 있다. 그래서 이 클래스는 upstream
 * Subscription을 sink에 그대로 넘기지 않고 직접 소유한다: 한 번에 최대 1개의
 * chunk만 upstream에 요청하고, 그 chunk의 필터 체인 처리 + sink.onNext() 전달이
 * 끝난 뒤에야 다음 chunk를 요청한다 — backpressure(request/cancel 위임)뿐 아니라
 * 순서 보장(serialization)까지 이 클래스의 책임이다.
 *
 * 상태는 세 가지뿐이다:
 *   IDLE             — 아무 것도 요청하지 않은 상태(downstream demand가 없음)
 *   AWAITING_UPSTREAM — upstream에 1개를 요청해두고 onNext/onComplete/onError 중
 *                       무엇이 올지 기다리는 상태 — 아직 "처리 중인 chunk"는 없다
 *   PROCESSING        — onNext로 chunk를 받아 필터 체인(비동기)을 돌리는 중
 *
 * onComplete/onError를 지금 당장 sink로 넘기지 않고 미뤄야 하는 경우는 오직
 * PROCESSING 상태일 때뿐이다 — 그래야 "아직 필터 처리 중인 chunk"를 추월하지
 * 않는다. AWAITING_UPSTREAM 상태(예: 빈 바디라 애초에 chunk가 하나도 없는 경우)에서
 * onComplete가 오면 기다릴 대상 자체가 없으므로 즉시 전달해야 한다 — 이걸
 * PROCESSING과 구분하지 않으면 빈 바디에서 영원히 onComplete가 전달되지 않는
 * 행(hang) 버그가 생긴다.
 */
final class FilterChainBodySubscriber implements Flow.Subscriber<byte[]> {

    private enum State { IDLE, AWAITING_UPSTREAM, PROCESSING }

    private final Flow.Subscriber<? super byte[]> sink;
    private final List<Filter> filters;
    private final boolean requestDir;

    private final Object lock = new Object();
    private Flow.Subscription upstream;
    private long requested = 0;
    private State state = State.IDLE;
    private boolean upstreamCompleted = false;
    private Throwable upstreamError = null;

    FilterChainBodySubscriber(Flow.Subscriber<? super byte[]> sink, List<Filter> filters, boolean requestDir) {
        this.sink = sink;
        this.filters = filters;
        this.requestDir = requestDir;
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
        this.upstream = s;
        sink.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                if (n <= 0) {
                    sink.onError(new IllegalArgumentException("request(n) must be positive, got " + n));
                    return;
                }
                boolean shouldPull;
                synchronized (lock) {
                    requested += n;
                    shouldPull = state == State.IDLE && requested > 0;
                    if (shouldPull) {
                        state = State.AWAITING_UPSTREAM;
                    }
                }
                if (shouldPull) {
                    upstream.request(1);
                }
            }

            @Override
            public void cancel() {
                upstream.cancel();
            }
        });
    }

    @Override
    public void onNext(byte[] chunk) {
        synchronized (lock) {
            state = State.PROCESSING;
        }
        applyChain(chunk).whenComplete((result, err) -> {
            if (err != null) {
                sink.onError(err);
                return;
            }

            boolean completed;
            Throwable failure;
            boolean pullMore;
            synchronized (lock) {
                requested = Math.max(0, requested - 1);
                completed = upstreamCompleted;
                failure = upstreamError;
                pullMore = !completed && failure == null && requested > 0;
                // completed/failure가 이미 true면 스트림은 여기서 끝나고 이후 어떤
                // 콜백도 state를 다시 보지 않으므로, 이 경우 값은 의미가 없다.
                state = pullMore ? State.AWAITING_UPSTREAM : State.IDLE;
            }

            // 다음 chunk를 요청하기 전에, 이 chunk 하나는 반드시 먼저 sink로 전달한다
            // (그래야 나중 chunk가 앞선 chunk를 추월할 수 없다).
            sink.onNext(result);

            if (failure != null) {
                sink.onError(failure);
            } else if (completed) {
                sink.onComplete();
            } else if (pullMore) {
                upstream.request(1);
            }
        });
    }

    @Override
    public void onError(Throwable t) {
        boolean deferred;
        synchronized (lock) {
            deferred = state == State.PROCESSING;
            if (deferred) {
                upstreamError = t;
            }
        }
        if (!deferred) {
            sink.onError(t);
        }
    }

    @Override
    public void onComplete() {
        boolean deferred;
        synchronized (lock) {
            deferred = state == State.PROCESSING;
            if (deferred) {
                upstreamCompleted = true;
            }
        }
        if (!deferred) {
            sink.onComplete();
        }
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
}
