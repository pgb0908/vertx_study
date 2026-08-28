package org.example.gateway.day5;

import io.vertx.core.Vertx;
import org.example.gateway.day5.upstream.DummyUpstreamVerticle;

public class Main {

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();

        vertx.deployVerticle(new DummyUpstreamVerticle(9001))
            .compose(id -> vertx.deployVerticle(new DummyUpstreamVerticle(9002)))
            .compose(id -> vertx.deployVerticle(new MainVerticle()))
            .onFailure(Throwable::printStackTrace);
    }
}
