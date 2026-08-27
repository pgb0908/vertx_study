package org.example.gateway.day1;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Day 1 실습: Verticle 하나로 뜨는 최소 HTTP 서버.
 * - /hello           : 가장 단순한 핸들러
 * - /orders/:userId  : Future 합성(compose)으로 두 번의 비동기 호출을 연결
 * - /blocking-bad    : 이벤트 루프를 직접 블로킹하는 잘못된 예시
 * - /blocking-good   : executeBlocking으로 워커 스레드에 격리한 올바른 예시
 */
public class MainVerticle extends AbstractVerticle {

    @Override
    public void start(Promise<Void> startPromise) {
        Router router = Router.router(vertx);

        router.get("/hello").handler(this::handleHello);
        router.get("/orders/:userId").handler(this::handleOrders);
        router.get("/blocking-bad").handler(this::handleBlockingBad);
        router.get("/blocking-good").handler(this::handleBlockingGood);

        vertx.createHttpServer()
            .requestHandler(router)
            .listen(8080)
            .onSuccess(server -> {
                System.out.println("Gateway listening on port " + server.actualPort());
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }

    private void handleHello(RoutingContext ctx) {
        ctx.response().end("hello from vert.x gateway");
    }

    // fetchUser -> fetchOrdersForUser 순으로 이어지는 비동기 체인.
    // RxJava의 flatMap / WebFlux의 flatMap과 같은 자리에 Future#compose가 온다.
    private void handleOrders(RoutingContext ctx) {
        String userId = ctx.pathParam("userId");

        fetchUser(userId)
            .compose(this::fetchOrdersForUser)
            .onSuccess(orders -> ctx.response()
                .putHeader("content-type", "application/json")
                .end(orders.encode()))
            .onFailure(err -> ctx.response()
                .setStatusCode(502)
                .end("upstream error: " + err.getMessage()));
    }

    // 실제로는 WebClient로 다른 서버를 부르는 자리. 지금은 setTimer로 비동기 지연만 흉내낸다.
    private Future<JsonObject> fetchUser(String userId) {
        Promise<JsonObject> promise = Promise.promise();
        vertx.setTimer(100, id -> promise.complete(
            new JsonObject().put("id", userId).put("name", "user-" + userId)));
        return promise.future();
    }

    private Future<JsonArray> fetchOrdersForUser(JsonObject user) {
        Promise<JsonArray> promise = Promise.promise();
        vertx.setTimer(100, id -> promise.complete(new JsonArray()
            .add(new JsonObject().put("orderId", 1).put("owner", user.getString("name")))
            .add(new JsonObject().put("orderId", 2).put("owner", user.getString("name")))));
        return promise.future();
    }

    // 절대 하면 안 되는 예시: 이 스레드는 이벤트 루프 스레드 자체다.
    // 이 핸들러가 도는 동안 같은 이벤트 루프에 배정된 다른 모든 요청이 멈춘다.
    private void handleBlockingBad(RoutingContext ctx) {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ctx.response().end("blocked the event loop for 3s (bad)");
    }

    // 같은 3초 지연이지만 워커 스레드 풀에서 실행되므로 이벤트 루프는 계속 다른 요청을 처리한다.
    private void handleBlockingGood(RoutingContext ctx) {
        vertx.<String>executeBlocking(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done";
        }).onSuccess(result -> ctx.response().end("isolated the 3s block on a worker thread (good)"))
          .onFailure(err -> ctx.response().setStatusCode(500).end(err.getMessage()));
    }
}
