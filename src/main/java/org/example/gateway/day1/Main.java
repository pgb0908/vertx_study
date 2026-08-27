package org.example.gateway.day1;

import io.vertx.core.Vertx;

public class Main {

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new MainVerticle())
            .onFailure(Throwable::printStackTrace);
    }
}
