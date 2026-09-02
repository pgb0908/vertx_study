package org.example.gateway.day7.proxy;

import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.OpenCircuitException;
import io.vertx.circuitbreaker.TimeoutException;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.RoutingContext;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 요청을 받으면 그룹에서 라운드로빈으로 업스트림 하나를 골라, 요청/응답 바디를
 * 모두 버퍼링하지 않고 스트리밍으로 그대로 전달(pipe)하는 handler를 만든다.
 *
 * day6와 다른 점: resilience 설정이 있는 라우트는 forResilientGroup으로 CircuitBreaker를
 * 통해 호출한다 (없으면 day6과 동일하게 forGroup으로 바로 프록시). breaker가
 * open/timeout으로 실패시키면 503/504를 즉시 응답하고, 멱등 메서드(GET/HEAD/OPTIONS)에
 * 한해서만 다른 업스트림으로 재시도한다.
 *
 * 재시도를 멱등 메서드로만 제한하는 이유: 요청 바디는 ReadStream이라 한 번 업스트림으로
 * .send()하고 나면 다시 보낼 수 없다(day3에서 확인한 스트리밍의 근본적 제약) — 실제로
 * ctx.request()를 재시도 때 그대로 다시 .send()해봤더니 이미 끝난 스트림이라
 * IllegalStateException이 그 자리에서(비동기 실패가 아니라 동기 throw로!) 발생했다.
 * 그래서 재시도 대상(GET/HEAD/OPTIONS, 관례상 바디가 없음)은 애초에 클라이언트 바디를
 * 스트리밍하지 않고 빈 바디로 보낸다 — ctx.request() 스트림을 아예 건드리지 않으므로
 * 몇 번을 재시도해도 "이미 소비된 스트림" 문제가 생기지 않는다. POST/PUT처럼 진짜 바디가
 * 있을 수 있는 메서드는 애초에 재시도하지 않으므로 이 단순화가 안전하다.
 *
 * 또한 재시도는 "업스트림 응답 헤더를 받기 전"까지만 허용한다 — 클라이언트에 응답 헤더를
 * 이미 흘려보내기 시작한 뒤에는 되돌릴 수 없기 때문에, 그 이후의 실패(pipe 중 끊김)는
 * 재시도하지 않고 그냥 연결을 끊는다(day6과 동일).
 */
public final class ProxyHandlerFactory {

    private static final Set<HttpMethod> IDEMPOTENT_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private final HttpClient client;

    public ProxyHandlerFactory(Vertx vertx) {
        this.client = vertx.createHttpClient();
    }

    public Handler<RoutingContext> forGroup(UpstreamGroup group) {
        return ctx -> {
            ctx.request().pause();
            // resilience 미설정 라우트: day6과 동일하게 breaker/재시도 없이 그냥 한 번만 시도,
            // 바디도 그대로 스트리밍한다 (재시도가 없으니 "두 번 소비" 문제 자체가 없음).
            forwardOnce(ctx, group, Promise.promise(), new AtomicBoolean(false), true);
        };
    }

    public Handler<RoutingContext> forResilientGroup(UpstreamGroup group, CircuitBreaker breaker, int maxRetries) {
        return ctx -> {
            ctx.request().pause();
            AtomicBoolean responded = new AtomicBoolean(false);
            int retriesLeft = IDEMPOTENT_METHODS.contains(ctx.request().method()) ? maxRetries : 0;
            attempt(ctx, group, breaker, retriesLeft, responded);
        };
    }

    private void attempt(RoutingContext ctx, UpstreamGroup group, CircuitBreaker breaker, int retriesLeft, AtomicBoolean responded) {
        // streamBody=false: 재시도 가능 경로는 클라이언트 바디를 절대 스트리밍하지 않는다
        // (멱등 메서드 한정이라 관례상 바디가 없고, ctx.request() 스트림을 아예 안 건드리므로
        // 몇 번을 재시도해도 "이미 소비된 스트림" 문제가 생기지 않는다).
        breaker.<Void>execute(promise -> forwardOnce(ctx, group, promise, responded, false))
            .onFailure(err -> {
                if (responded.get()) {
                    return; // 이미 업스트림 응답 헤더를 흘려보내기 시작한 뒤의 실패 — 되돌릴 수 없다.
                }
                boolean retryable = retriesLeft > 0 && !(err instanceof OpenCircuitException);
                if (retryable) {
                    attempt(ctx, group, breaker, retriesLeft - 1, responded);
                } else if (responded.compareAndSet(false, true)) {
                    respondError(ctx, err);
                }
            });
    }

    private void forwardOnce(RoutingContext ctx, UpstreamGroup group, Promise<Void> promise, AtomicBoolean responded,
                              boolean streamBody) {
        Upstream upstream = group.next();

        RequestOptions options = new RequestOptions()
            .setHost(upstream.host())
            .setPort(upstream.port())
            .setMethod(ctx.request().method())
            .setURI(ctx.request().uri());

        client.request(options)
            .compose(clientRequest -> {
                copyHeaders(ctx.request().headers(), clientRequest.headers());
                return streamBody ? clientRequest.send(ctx.request()) : clientRequest.send();
            })
            .onSuccess(clientResponse -> {
                // 재시도 경로(!streamBody)에서는 5xx도 breaker 입장에서 "실패"로 취급한다.
                // 안 그러면 업스트림이 계속 500만 내려줘도 breaker는 계속 CLOSED로 남아
                // "매번 성공적으로 500을 전달"한 셈이 되어, 회로차단기가 무용지물이 된다.
                if (!streamBody && clientResponse.statusCode() >= 500) {
                    clientResponse.body(); // 응답 바디를 드레인해서 커넥션을 풀에 정상 반납
                    promise.tryFail(new RuntimeException("upstream returned " + clientResponse.statusCode()));
                    return;
                }
                if (!responded.compareAndSet(false, true)) {
                    // breaker 타임아웃으로 이미 에러 응답을 보낸 뒤 뒤늦게 도착한 성공 — 무시.
                    promise.tryComplete();
                    return;
                }
                ctx.response().setStatusCode(clientResponse.statusCode());
                copyHeaders(clientResponse.headers(), ctx.response().headers());
                ctx.response().setChunked(true);
                clientResponse.pipeTo(ctx.response()).onFailure(err -> ctx.response().reset());
                promise.tryComplete();
            })
            .onFailure(promise::tryFail);
    }

    private void respondError(RoutingContext ctx, Throwable err) {
        if (err instanceof OpenCircuitException) {
            ctx.response().setStatusCode(503).putHeader("Retry-After", "1").end("circuit open, upstream unavailable");
        } else if (err instanceof TimeoutException) {
            ctx.response().setStatusCode(504).end("upstream timeout");
        } else {
            ctx.response().setStatusCode(502).end("upstream error: " + err.getMessage());
        }
    }

    private void copyHeaders(MultiMap from, MultiMap to) {
        for (var entry : from) {
            if (!HopByHopHeaders.NAMES.contains(entry.getKey().toLowerCase())) {
                to.add(entry.getKey(), entry.getValue());
            }
        }
    }
}
