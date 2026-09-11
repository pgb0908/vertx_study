package org.example.gateway.day11;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

/**
 * Lesson 0 — "id -> { ... }"에서 id의 타입은 어디서 오는가.
 *
 * 람다 자체는 타입 정보가 없다. 컴파일러는 "이 람다가 어떤 메서드의 어떤 파라미터
 * 자리에 들어가는지"(target type)를 먼저 찾고, 그 파라미터의 함수형 인터페이스가
 * 정의한 추상 메서드 시그니처를 보고 거꾸로 람다 파라미터 타입을 확정한다.
 *
 * 순서: (1) 람다가 어떤 메서드의 인자인지 찾는다 -> (2) 그 파라미터의 선언 타입
 * (함수형 인터페이스)을 본다 -> (3) 그 인터페이스의 유일한 추상 메서드 시그니처에서
 * 람다 파라미터 타입을 위치대로 읽는다.
 */
final class L0_LambdaTypeInference {

    private L0_LambdaTypeInference() {
    }

    static Future<Void> run(Vertx vertx) {
        Promise<Void> done = Promise.promise();

        System.out.println("[L0] vertx.setTimer(delay, id -> ...) 의 id는 어떻게 Long으로 정해지나:");
        System.out.println("[L0]   1) 호출 메서드: Vertx.setTimer(long delay, Handler<Long> handler)");
        System.out.println("[L0]   2) 두 번째 파라미터 타입: Handler<Long>");
        System.out.println("[L0]   3) Handler<T>의 추상 메서드: void handle(T event) -> T=Long");
        System.out.println("[L0]   => id의 타입은 Long. 변수 이름(id)은 그냥 사람이 붙인 라벨일 뿐,");
        System.out.println("[L0]      타입 추론에는 아무 영향이 없다 (x여도, foo여도 결과는 같다).");

        System.out.println("[L0] onSuccess(v -> ...)의 v는 Future<T>의 T를 그대로 물려받는다:");
        Future<String> stringFuture = Future.succeededFuture("hello");
        stringFuture.onSuccess(v -> System.out.println("[L0]   Future<String>.onSuccess -> v는 String: \"" + v + "\""));

        Future<Integer> intFuture = Future.succeededFuture(42);
        intFuture.onSuccess(v -> System.out.println("[L0]   Future<Integer>.onSuccess -> v는 Integer: " + v));

        // 같은 논리를 명시적으로 확인해본다: Handler<Long> 타입을 변수로 뽑아내서 대입.
        // 이 레슨을 완전히 끝낸 뒤에야 다음 레슨으로 넘어가도록, 이 타이머의 완료까지 기다린다.
        Handler<Long> explicit = timerId -> {
            System.out.println("[L0]   명시적으로 뽑아낸 Handler<Long>: " + timerId);
            done.complete();
        };
        vertx.setTimer(10, explicit);

        return done.future();
    }
}
