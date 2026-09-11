package org.example.gateway.day11;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * Lesson 4 — map(): Functor 패턴.
 *
 * "상자 안의 값을 동기적으로 변환한다"는 개념은 Future뿐 아니라 Optional, Stream에도
 * 똑같이 있다 — 전부 같은 이름(map)을 쓰는 이유는 개념이 같기 때문이다(Functor).
 *
 * 규칙: A -> B로 바꾸는 순수 함수를 넣으면 Container<A> -> Container<B>가 된다.
 * 넣는 함수 자체는 절대 새 상자를 만들지 않는다 — 그냥 값 하나를 다른 값으로 바꿀 뿐.
 */
final class L4_MapFunctor {

    private L4_MapFunctor() {
    }

    static Future<Void> run(Vertx vertx) {
        Promise<Void> done = Promise.promise();

        System.out.println("[L4] 같은 map() 개념이 세 군데서 똑같이 동작한다:");

        // Optional.map — 값이 있으면 변환, 없으면 그대로 빈 채로.
        Optional<String> opt = Optional.of("hello").map(String::toUpperCase);
        System.out.println("[L4]   Optional<String>.map(toUpperCase) -> " + opt.get());

        // Stream.map — 각 원소를 변환.
        String joined = Stream.of("a", "b", "c").map(String::toUpperCase).reduce("", (a, b) -> a + b);
        System.out.println("[L4]   Stream<String>.map(toUpperCase) -> " + joined);

        // Future.map — 나중에 도착할 값을 변환. 지금 당장 실행되는 게 아니라
        // "값이 도착하면 이 변환을 적용해라"는 예약이다.
        Promise<String> promise = Promise.promise();
        Future<Integer> lengthFuture = promise.future().map(String::length);

        lengthFuture.onSuccess(len -> System.out.println("[L4]   Future<String>.map(String::length) -> " + len));

        vertx.setTimer(30, id -> {
            promise.complete("day10-gateway");
            done.complete();
        });

        return done.future();
    }
}
