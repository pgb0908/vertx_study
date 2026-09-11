package org.example.gateway.day11;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.concurrent.CompletableFuture;

/**
 * Lesson 7 — Vert.x Future ↔ JDK CompletableFuture 대응표.
 *
 * day10의 domain/engine 계층은 Vert.x Future를 쓰지 않고 JDK CompletableFuture만
 * 쓴다(Arch.md 원칙 1: domain/engine에 Vert.x 타입이 보이면 안 됨). 그래서 지금까지
 * 배운 개념을 JDK 쪽 이름으로도 알아둬야 한다 — 개념은 동일하고 이름만 다르다.
 *
 *   Vert.x Future            | JDK CompletableFuture      | 하는 일
 *   --------------------------|------------------------------|------------------------------
 *   .onSuccess() / .onFailure()| .thenAccept() / .exceptionally() | 값은 안 바꾸고 부수효과만
 *   .map()                    | .thenApply()                | 값을 동기적으로 변환 (Functor)
 *   .compose()                | .thenCompose()               | 다음 비동기 작업으로 체이닝 (Monad)
 *   .onComplete()             | .whenComplete()              | 성공/실패를 한 콜백에서
 *   .recover()                | .exceptionally() / .handle() | 실패를 대체 값으로 복구
 *
 * day10의 runtime.vertx 경계(예: VertxUpstreamClient)에서 이 둘을 서로 변환한다 —
 * Vert.x가 주는 Future를 domain/engine이 기대하는 CompletableFuture로 옮겨 담는 것.
 */
final class L7_CompletableFutureEquivalents {

    private L7_CompletableFutureEquivalents() {
    }

    static Future<Void> run(Vertx vertx) {
        System.out.println("[L7] 같은 체이닝을 JDK CompletableFuture로 그대로 다시 써본다:");

        CompletableFuture<String> cf = CompletableFuture.supplyAsync(() -> "input");

        CompletableFuture<String> transformed = cf
            .thenApply(String::toUpperCase)            // Vert.x의 map()과 동일
            .thenCompose(v -> CompletableFuture.completedFuture(v + "-composed")); // Vert.x의 compose()와 동일

        transformed.whenComplete((value, err) -> {       // Vert.x의 onComplete()와 동일
            if (err != null) {
                System.out.println("[L7]   실패: " + err);
            } else {
                System.out.println("[L7]   thenApply+thenCompose 결과: " + value);
            }
        });

        System.out.println("[L7] day10/runtime/vertx/VertxUpstreamClient.java의 실제 방식:");
        System.out.println("[L7]   Vert.x Future의 결과를 '수동으로' CompletableFuture에 옮겨 담는다 —");
        System.out.println("[L7]   응답이 여러 콜백(onSuccess/onFailure)에 걸쳐 도착하는 경우라");
        System.out.println("[L7]   JDK가 제공하는 future.toCompletionStage().toCompletableFuture() 변환만으론");
        System.out.println("[L7]   부족해서, CompletableFuture를 직접 만들고 각 콜백에서 complete()/completeExceptionally()를 부른다.");

        // 실제로 재현: Vert.x Future -> CompletableFuture로 브리징
        Future<String> vertxFuture = Future.succeededFuture("vertx-value");
        CompletableFuture<String> bridged = new CompletableFuture<>();
        vertxFuture
            .onFailure(bridged::completeExceptionally)
            .onSuccess(bridged::complete);
        bridged.thenAccept(v -> System.out.println("[L7]   브리징 결과: " + v));

        return Future.succeededFuture();
    }
}
