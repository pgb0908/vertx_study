package org.example.gateway.day11;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

/**
 * day11 — Vert.x Future/Promise 합성 패턴, 기초부터 응용까지.
 *
 * 각 레슨을 순서대로 compose()로 이어붙여 실행한다 — 이 Main 자체가 이미
 * Lesson 5(compose)의 실전 예제다. 자세한 설명은 각 LessonN 클래스 상단 javadoc과
 * README.md를 참고.
 */
public class Main {

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();

        header("Lesson 0 — 람다 파라미터 타입은 어디서 오는가")
            .compose(v -> L0_LambdaTypeInference.run(vertx))
            .compose(v -> header("Lesson 1 — 콜백 스타일과 그 한계 (콜백 피라미드)"))
            .compose(v -> L1_CallbackStyle.run(vertx))
            .compose(v -> header("Lesson 2 — Future/Promise 기본기"))
            .compose(v -> L2_FutureAndPromise.run(vertx))
            .compose(v -> header("Lesson 3 — onSuccess/onFailure: 값은 안 바꾸고, 자기 자신을 리턴"))
            .compose(v -> L3_OnSuccessOnFailure.run(vertx))
            .compose(v -> header("Lesson 4 — map(): Functor 패턴"))
            .compose(v -> L4_MapFunctor.run(vertx))
            .compose(v -> header("Lesson 5 — compose(): Monad 패턴"))
            .compose(v -> L5_ComposeMonad.run(vertx))
            .compose(v -> header("Lesson 6 — onComplete/recover: 실패 전파와 복구"))
            .compose(v -> L6_OnCompleteAndRecover.run(vertx))
            .compose(v -> header("Lesson 7 — Vert.x Future <-> JDK CompletableFuture 대응표"))
            .compose(v -> L7_CompletableFutureEquivalents.run(vertx))
            .compose(v -> header("Lesson 8 — 응용: connect -> send -> receive 파이프라인 조립"))
            .compose(v -> L8_AppliedExercise.run(vertx))
            .onComplete(result -> {
                if (result.failed()) {
                    result.cause().printStackTrace();
                } else {
                    System.out.println("\n=== 모든 레슨 완료 ===");
                }
                vertx.close();
            });
    }

    private static Future<Void> header(String title) {
        System.out.println("\n=== " + title + " ===");
        return Future.succeededFuture();
    }
}
