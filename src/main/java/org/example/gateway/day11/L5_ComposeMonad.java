package org.example.gateway.day11;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

/**
 * Lesson 5 — compose(): Monad 패턴.
 *
 * map()에 "다음 비동기 작업을 시작하는 함수"(A -> Future<B>)를 넣으면 무슨 일이
 * 생기는지 먼저 보고, 그게 왜 문제인지, compose()가 그걸 어떻게 고치는지 순서대로 본다.
 */
final class L5_ComposeMonad {

    private L5_ComposeMonad() {
    }

    static Future<Void> run(Vertx vertx) {
        System.out.println("[L5] 문제 상황: map()에 '비동기 작업을 시작하는 함수'를 넣으면?");

        // callAsyncService: String -> Future<String> (또 다른 비동기 작업을 시작함)
        // 이걸 그대로 map()에 넣으면 Future<Future<String>>이 생겨버린다 —
        // 상자 안에 상자가 하나 더 생긴 것. 바깥 상자가 열려도 안쪽 상자는 아직
        // 안 열렸을 수 있어서 값을 바로 꺼내 쓸 수가 없다.
        Future<Future<String>> nested = Future.succeededFuture("input")
            .map(v -> callAsyncService(vertx, v, 30));
        System.out.println("[L5]   map()으로 이어붙인 결과 타입: Future<Future<String>> (상자가 두 겹!)");
        nested.onSuccess(innerFuture ->
            System.out.println("[L5]   바깥 상자는 열렸지만 값은 여전히 Future: " + innerFuture));

        // compose(): A -> Future<B>를 넣으면, 결과를 Future<Future<B>>가 아니라
        // Future<B>로 "평탄화(flatten)"해준다. 이게 map과 compose의 유일한 차이다.
        Future<String> flattened = Future.succeededFuture("input")
            .compose(v -> callAsyncService(vertx, v, 30));
        flattened.onSuccess(v -> System.out.println("[L5]   compose()로 이어붙인 결과: 상자 한 겹, 값 = " + v));

        // 실전에서는 이렇게 여러 비동기 단계를 사슬처럼 잇는다 —
        // day10 Main.java의 vertx.deployVerticle(...).compose(id -> server.start(8080))와
        // 완전히 같은 패턴이다.
        return callAsyncService(vertx, "step1", 20)
            .compose(r1 -> {
                System.out.println("[L5] step1 완료(" + r1 + ") -> step2로 이어붙임");
                return callAsyncService(vertx, r1 + "+step2", 20);
            })
            .compose(r2 -> {
                System.out.println("[L5] step2 완료(" + r2 + ") -> step3로 이어붙임");
                return callAsyncService(vertx, r2 + "+step3", 20);
            })
            .onSuccess(r3 -> System.out.println("[L5] 전체 체인 완료: " + r3
                + " — Lesson 1의 콜백 피라미드와 같은 일을 하지만 들여쓰기가 안 깊어진다"))
            .mapEmpty();
    }

    private static Future<String> callAsyncService(Vertx vertx, String input, long delayMs) {
        io.vertx.core.Promise<String> promise = io.vertx.core.Promise.promise();
        vertx.setTimer(delayMs, id -> promise.complete(input + "-processed"));
        return promise.future();
    }
}
