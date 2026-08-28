package org.example.gateway.day3;

import io.vertx.core.Vertx;
import org.example.gateway.day3.upstream.DummyUpstreamVerticle;

/**
 * 더미 업스트림 2개(9001, 9002) + 게이트웨이(8080)를 한 프로세스에 같이 띄운다.
 * `./gradlew runDay3` 하나로 프록시 대상까지 자체적으로 준비되어 바로 curl 테스트 가능.
 */
public class Main {

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();

        vertx.deployVerticle(new DummyUpstreamVerticle(9001))
            .compose(id -> vertx.deployVerticle(new DummyUpstreamVerticle(9002)))
            .compose(id -> vertx.deployVerticle(new MainVerticle()))
            .onFailure(Throwable::printStackTrace);
    }
}
