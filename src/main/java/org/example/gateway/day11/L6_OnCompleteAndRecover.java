package org.example.gateway.day11;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

/**
 * Lesson 6 — onComplete/recover: 성공·실패를 한 곳에서 다루기, 실패에서 복구하기.
 *
 * onSuccess/onFailure를 따로 등록하는 대신, 성공/실패를 한 콜백에서 같이 받고
 * 싶을 때는 onComplete(AsyncResult<T>)를 쓴다. 그리고 실패한 체인을 다시 성공으로
 * 되돌리고 싶으면 recover()를 쓴다 — map/compose 체인은 중간에 하나라도 실패하면
 * 그 뒤 map/compose는 전부 건너뛰고 바로 실패가 전파되는데, recover()가 그 실패를
 * "가로채서" 대체 값으로 바꿔주는 지점이다.
 */
final class L6_OnCompleteAndRecover {

    private L6_OnCompleteAndRecover() {
    }

    static Future<Void> run(Vertx vertx) {
        System.out.println("[L6] onComplete: 성공/실패를 한 콜백에서 처리");
        Future.succeededFuture("ok-value").onComplete(result -> {
            if (result.succeeded()) {
                System.out.println("[L6]   성공 -> " + result.result());
            } else {
                System.out.println("[L6]   실패 -> " + result.cause());
            }
        });
        Future.<String>failedFuture(new RuntimeException("boom")).onComplete(result -> {
            if (result.succeeded()) {
                System.out.println("[L6]   성공 -> " + result.result());
            } else {
                System.out.println("[L6]   실패 -> " + result.cause().getMessage());
            }
        });

        System.out.println("[L6] 실패 전파: map/compose 체인 중간에서 실패하면 뒤는 전부 건너뛴다");
        Future<String> failing = Future.<String>failedFuture(new RuntimeException("upstream down"))
            .map(v -> {
                System.out.println("[L6]   이 map은 절대 실행되지 않는다");
                return v.toUpperCase();
            })
            .compose(v -> {
                System.out.println("[L6]   이 compose도 절대 실행되지 않는다");
                return Future.succeededFuture(v);
            });
        failing.onFailure(err -> System.out.println("[L6]   실패가 그대로 끝까지 전파됨: " + err.getMessage()));

        System.out.println("[L6] recover(): 실패를 가로채서 대체 값으로 바꾼다");
        Future<String> recovered = Future.<String>failedFuture(new RuntimeException("upstream down"))
            .recover(err -> {
                System.out.println("[L6]   recover가 실패를 잡았다: " + err.getMessage() + " -> 대체 값 사용");
                return Future.succeededFuture("fallback-value");
            })
            .map(v -> v.toUpperCase()); // recover 뒤로는 다시 정상 체인처럼 이어진다
        recovered.onSuccess(v -> System.out.println("[L6]   recover 이후 체인이 정상 진행됨 -> " + v));

        return Future.succeededFuture();
    }
}
