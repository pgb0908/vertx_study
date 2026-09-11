package org.example.gateway.day11;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

/**
 * Lesson 1 — 왜 Future/Promise가 필요한가.
 *
 * Vert.x는 절대 스레드를 블로킹하지 않는다. 네트워크 I/O처럼 시간이 걸리는 작업의
 * 결과는 "지금 당장"이 아니라 "나중에" 온다. 그 결과를 받는 가장 원시적인 방법이
 * 콜백(Handler)이다 — Vert.x 3 시절 실제로 이렇게 썼다.
 *
 * 문제는 async 작업을 2개, 3개 이어붙이면 콜백이 콜백 안에 중첩되는
 * "콜백 피라미드(callback hell)"가 생긴다는 것 — 들여쓰기가 계단처럼 깊어지고,
 * 에러 처리(if r.succeeded() else ...)를 매 단계마다 반복해야 한다.
 */
final class L1_CallbackStyle {

    private L1_CallbackStyle() {
    }

    static Future<Void> run(Vertx vertx) {
        Promise<Void> done = Promise.promise();

        System.out.println("[L1] 콜백 스타일로 3단계 비동기 작업을 이어붙여본다:");

        // 콜백 기반 async 메서드 — 시그니처가 Handler<AsyncResult<T>>를 받는다.
        // (지금 Vert.x 4는 이 스타일 대신 Future를 리턴하는 스타일을 기본으로 쓴다.
        //  옛 스타일을 굳이 재현하는 이유는 "무엇이 불편해서" Future가 생겼는지 보기 위해서다.)
        simulateAsyncCall(vertx, "step1", 80, r1 -> {
            if (r1.succeeded()) {
                System.out.println("[L1]   step1 완료: " + r1.result());
                simulateAsyncCall(vertx, "step2", 60, r2 -> {
                    if (r2.succeeded()) {
                        System.out.println("[L1]     step2 완료: " + r2.result());
                        simulateAsyncCall(vertx, "step3", 40, r3 -> {
                            if (r3.succeeded()) {
                                System.out.println("[L1]       step3 완료: " + r3.result());
                                System.out.println("[L1] 들여쓰기가 계단처럼 깊어지는 게 보인다 — 이게 '콜백 피라미드'다.");
                                System.out.println("[L1] 다음 레슨에서 Future/Promise가 이 문제를 어떻게 평평하게 펴는지 본다.");
                                done.complete();
                            } else {
                                done.fail(r3.cause());
                            }
                        });
                    } else {
                        done.fail(r2.cause());
                    }
                });
            } else {
                done.fail(r1.cause());
            }
        });

        return done.future();
    }

    /** 콜백 기반 async 작업을 흉내낸다: delayMs 뒤에 handler를 성공으로 호출. */
    private static void simulateAsyncCall(Vertx vertx, String name, long delayMs, Handler<AsyncResult<String>> handler) {
        vertx.setTimer(delayMs, id -> handler.handle(io.vertx.core.Future.succeededFuture(name + "-result")));
    }
}
