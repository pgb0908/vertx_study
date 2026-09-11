package org.example.gateway.day11;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import java.util.concurrent.CompletableFuture;

/**
 * Lesson 8 — 응용: day10 VertxUpstreamClient와 같은 모양의 3단계 파이프라인을
 * 직접 조립해본다.
 *
 *   connect(endpoint) -> send(request) -> receive() -> CompletableFuture로 브리징
 *
 * 지금까지 배운 걸 전부 쓴다: compose(다음 비동기 단계로 체이닝), map(값 변환),
 * onFailure(부수효과로 로그), 마지막엔 CompletableFuture로 브리징(day10 domain/engine
 * 경계). 실제 파일과 나란히 비교해보면 좋다:
 * src/main/java/org/example/gateway/day10/runtime/vertx/VertxUpstreamClient.java
 */
final class L8_AppliedExercise {

    private L8_AppliedExercise() {
    }

    record FakeConnection(String host, int port) {
    }

    record FakeResponse(int statusCode, String body) {
    }

    static Future<Void> run(Vertx vertx) {
        System.out.println("[L8] connect -> send -> receive 파이프라인을 조립한다");

        CompletableFuture<FakeResponse> bridged = new CompletableFuture<>();

        connect(vertx, "localhost", 9001)
            .onFailure(err -> System.out.println("[L8] 연결 실패: " + err.getMessage()))
            .compose(conn -> {
                System.out.println("[L8] 연결 성공: " + conn + " -> 요청 전송");
                return send(vertx, conn, "GET /echo");
            })
            .compose(sent -> {
                System.out.println("[L8] 요청 전송 완료(" + sent + ") -> 응답 수신 대기");
                return receive(vertx);
            })
            .map(response -> {
                System.out.println("[L8] 응답 수신, map()으로 로깅용 요약 문자열도 같이 만들어본다");
                return response;
            })
            .onSuccess(bridged::complete)
            .onFailure(bridged::completeExceptionally);

        Promise<Void> done = Promise.promise();
        bridged.whenComplete((response, err) -> {
            if (err != null) {
                System.out.println("[L8] 파이프라인 실패: " + err);
                done.fail(err);
            } else {
                System.out.println("[L8] CompletableFuture로 브리징된 최종 결과: status=" + response.statusCode()
                    + " body=" + response.body());
                System.out.println("[L8] 여기서부터는 day10의 GatewayEngine이 이어받는다 — Vert.x Future가 아니라");
                System.out.println("[L8] CompletableFuture만 보게 되는 지점이 바로 이 브리징이다.");
                done.complete();
            }
        });

        return done.future();
    }

    private static Future<FakeConnection> connect(Vertx vertx, String host, int port) {
        Promise<FakeConnection> promise = Promise.promise();
        vertx.setTimer(30, id -> promise.complete(new FakeConnection(host, port)));
        return promise.future();
    }

    private static Future<String> send(Vertx vertx, FakeConnection conn, String request) {
        Promise<String> promise = Promise.promise();
        vertx.setTimer(20, id -> promise.complete(request + " -> " + conn.host() + ":" + conn.port()));
        return promise.future();
    }

    private static Future<FakeResponse> receive(Vertx vertx) {
        Promise<FakeResponse> promise = Promise.promise();
        vertx.setTimer(20, id -> promise.complete(new FakeResponse(200, "{\"ok\":true}")));
        return promise.future();
    }
}
