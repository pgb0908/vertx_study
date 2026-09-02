package org.example.gateway.day9.filter.auth;

import io.vertx.core.Vertx;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;

/**
 * day4에는 없던 신규 파일. AuthJwtFilter/TokenGenerator와 함께 filter/auth로 옮겨왔다 —
 * 셋 다 "JWT 인증"이라는 하나의 관심사를 이루는 조각들이라 같은 디렉토리에 둔다.
 *
 * 프로토타입이라 대칭키(HS256) 공유 비밀을 코드에 박아둔다. 실무라면 이 값은
 * 파일/환경변수/비밀관리 시스템에서 읽어와야 한다 (day5의 스코프는 "JWT 검증 메커니즘 자체").
 * TokenGenerator(토큰 발급용 테스트 도구)와 MainVerticle(토큰 검증) 양쪽이 같은 비밀을 써야
 * 서명이 맞아떨어지므로 한 곳에 모아둔다.
 *
 * MainVerticle이 다른 패키지(org.example.gateway.day9)에서 이 클래스를 써야 해서
 * public으로 바꿨다 — 옮기기 전엔 같은 패키지라 package-private으로 충분했다.
 */
public final class JwtConfig {

    static final String SHARED_SECRET = "day5-shared-secret-please-change";

    private JwtConfig() {
    }

    public static JWTAuth createProvider(Vertx vertx) {
        return JWTAuth.create(vertx, new JWTAuthOptions()
            .addPubSecKey(new PubSecKeyOptions()
                .setAlgorithm("HS256")
                .setBuffer(SHARED_SECRET)));
    }
}
