package org.example.gateway.day11;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

/**
 * Lesson 3 — onSuccess/onFailure는 "값을 바꾸지 않는다", 그리고 "자기 자신을 리턴한다".
 *
 * day10의 VertxUpstreamClient에 있던 이 코드:
 *
 *   client.request(options)
 *       .onFailure(err -> { ... })
 *       .onSuccess(clientRequest -> { ... });
 *
 * 가 왜 성립하는지는 딱 한 가지 사실만 알면 된다: onSuccess()/onFailure()는
 * 호출된 그 Future 자기 자신을 리턴한다. 그래서 뒤에 .onSuccess()를 또 이어 쓸 수
 * 있는 것 — "다음 값으로 넘어간다"는 뜻이 절대 아니다. 등록된 콜백 중 결과에 맞는
 * 것 하나만 나중에 실행된다.
 */
final class L3_OnSuccessOnFailure {

    private L3_OnSuccessOnFailure() {
    }

    static Future<Void> run(Vertx vertx) {
        Promise<Void> done = Promise.promise();

        Promise<String> promise = Promise.promise();
        Future<String> future = promise.future();

        // onFailure()의 리턴값과 future 자기 자신이 identical(==)한지 확인해본다.
        Future<String> returnedByOnFailure = future.onFailure(err -> System.out.println("[L3] onFailure 콜백 (실행 안 될 것)"));
        System.out.println("[L3] future.onFailure(...)가 리턴한 객체 == future 자기 자신? " + (returnedByOnFailure == future));

        // 그래서 이렇게 체이닝해도 등록되는 콜백은 "둘 다"이고, 실행되는 건 결과에 따라 하나뿐이다.
        future
            .onFailure(err -> System.out.println("[L3] 실패 콜백 실행됨: " + err))
            .onSuccess(v -> System.out.println("[L3] 성공 콜백 실행됨: " + v))
            .onSuccess(v -> System.out.println("[L3] 성공 콜백을 여러 번 등록해도 전부 실행된다: " + v));

        vertx.setTimer(30, id -> {
            promise.complete("day10");
            done.complete();
        });

        return done.future();
    }
}
