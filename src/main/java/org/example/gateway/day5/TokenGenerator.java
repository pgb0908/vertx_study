package org.example.gateway.day5;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;

/**
 * day4에는 없던 신규 파일.
 * 실제 서비스라면 별도의 인증 서버가 토큰을 발급하지만, 이 프로토타입은
 * 게이트웨이의 JWT 검증 로직만 테스트하면 되므로 같은 비밀키로 서명된 토큰을
 * 커맨드라인에서 바로 뽑아주는 개발용 도구를 둔다.
 *
 * `./gradlew genDay5Token` 으로 실행하면 유효한 토큰 하나를 stdout에 출력한다.
 */
public class TokenGenerator {

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        JWTAuth jwtAuth = JwtConfig.createProvider(vertx);

        String token = jwtAuth.generateToken(new JsonObject().put("sub", "test-user"));
        System.out.println(token);

        vertx.close();
    }
}
