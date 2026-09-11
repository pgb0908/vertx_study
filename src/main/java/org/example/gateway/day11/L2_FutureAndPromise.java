package org.example.gateway.day11;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

/**
 * Lesson 2 — Future/Promise 기본기.
 *
 * Promise<T> = "나중에 채워질 상자"를 만드는 쪽(생산자)이 쥐는 손잡이.
 * Future<T>  = 그 상자를 읽는 쪽(소비자)이 보는 뷰. promise.future()로 얻는다.
 *
 * 생산자는 promise.complete(value) / promise.fail(err)로 상자를 채우고,
 * 소비자는 future.onSuccess(...) / future.onFailure(...)로 상자가 열리길 기다린다.
 *
 * 이미 완성된 값이 있으면 Future.succeededFuture(value) / Future.failedFuture(err)로
 * Promise 없이 바로 "이미 채워진 상자"를 만들 수도 있다.
 */
final class L2_FutureAndPromise {

    private L2_FutureAndPromise() {
    }

    static Future<Void> run(Vertx vertx) {
        Promise<Void> done = Promise.promise();

        // 1. Promise를 만들고 future()로 소비자용 뷰를 뽑는다.
        Promise<String> promise = Promise.promise();
        Future<String> future = promise.future();

        System.out.println("[L2] Promise를 만들었다 — 아직 값은 없다 (isComplete=" + future.isComplete() + ")");

        // 2. 소비자는 onSuccess/onFailure로 "나중에 열리면 할 일"을 등록해둔다.
        //    이 시점엔 아직 아무 것도 실행되지 않는다 — 등록만 하는 것.
        future.onSuccess(value -> System.out.println("[L2] 상자가 열렸다 -> " + value));
        future.onFailure(err -> System.out.println("[L2] 상자가 실패로 열렸다 -> " + err));

        // 3. 50ms 뒤에 생산자가 상자를 채운다 (실제로는 네트워크 응답 도착 같은 이벤트).
        vertx.setTimer(50, id -> {
            promise.complete("hello");

            // 이미 완성된 Future를 즉시 만드는 방법도 있다 (테스트/기본값에 유용).
            Future<String> already = Future.succeededFuture("already-done");
            System.out.println("[L2] Future.succeededFuture()는 등록 즉시 콜백이 실행된다: ");
            already.onSuccess(v -> System.out.println("[L2]   -> " + v));

            done.complete();
        });

        return done.future();
    }
}
