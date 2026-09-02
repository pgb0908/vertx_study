package org.example.gateway.day9;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.example.gateway.day9.upstream.DummyUpstreamVerticle;

/**
 * day8와 다른 점: 더미 백엔드(9001/9002)를 로컬 curl 테스트용으로 계속 띄우는 것은
 * 같지만(실무라면 이 두 줄 자체가 없고 orders-service가 별도 프로세스로 이미 떠 있음),
 * Vertx/배포 설정을 실무 방식으로 바꿨다.
 *  - VertxOptions: setBlockedThreadCheckInterval로 이벤트 루프 블로킹을 자동 감지,
 *    setPreferNativeTransport로 리눅스에서 epoll을 사용 (지원 안 되면 Vert.x가 알아서
 *    JDK NIO로 폴백하므로 다른 OS에서도 안전).
 *  - MainVerticle을 new로 하나 미리 만들어 넘기는 대신 팩토리(MainVerticle::new)와
 *    DeploymentOptions.setInstances(N)로 배포 — Vert.x가 이 팩토리를 N번 호출해 N개의
 *    독립된 인스턴스를 만들고, 서로 다른 event loop에 배정한다 (day7 대화에서 나온
 *    "event loop 풀 크기를 늘리는 것과 Verticle 인스턴스 수를 늘리는 것은 다르다"가
 *    실제로 적용된 지점 — 여러 인스턴스가 같은 포트(8443)에 listen해도 Vert.x가 내부적으로
 *    라운드로빈으로 연결을 분배해준다). N은 하드코딩된 별개의 숫자가 아니라
 *    vertxOptions.getEventLoopPoolSize()로 "방금 이 VertxOptions가 실제로 만들 event
 *    loop 개수"를 그대로 읽어와서 쓴다 — 이러면 event loop가 몇 개든(기본값이든
 *    -Dgateway.eventLoopPoolSize로 바꾼 값이든) 항상 정확히 그 개수만큼 인스턴스가 배포돼서
 *    풀 전체가 활용된다.
 *  - 배포 실패 시 스택트레이스만 찍고 프로세스가 좀비로 남는 대신 System.exit(1)로
 *    명확히 종료해서, 오케스트레이터(k8s 등)가 재시작 여부를 판단할 수 있게 한다.
 *  - SIGTERM에 vertx.close()를 걸어, 종료 시 각 Verticle의 stop()이 호출되도록 한다
 *    (graceful shutdown의 시작점 — 실제 draining 로직은 필요해지면 MainVerticle.stop()에
 *    추가하면 된다).
 */
public class Main {

    public static void main(String[] args) {
        VertxOptions vertxOptions = new VertxOptions()
            .setBlockedThreadCheckInterval(2000)
            .setPreferNativeTransport(true);
        // -Dgateway.eventLoopPoolSize=N으로 명시하지 않으면 Vert.x 기본값(CPU 코어 수 * 2)이 쓰인다.
        Integer eventLoopPoolSize = Integer.getInteger("gateway.eventLoopPoolSize");
        if (eventLoopPoolSize != null) {
            vertxOptions.setEventLoopPoolSize(eventLoopPoolSize);
        }

        Vertx vertx = Vertx.vertx(vertxOptions);

        Runtime.getRuntime().addShutdownHook(new Thread(() ->
            vertx.close().toCompletionStage().toCompletableFuture().join()));

        // 실제로 만들어질 event loop 개수를 그대로 읽어와서, 그 풀 전체를 MainVerticle이 쓰게 한다.
        int instances = vertxOptions.getEventLoopPoolSize();

        vertx.deployVerticle(new DummyUpstreamVerticle(9001))
            .compose(id -> vertx.deployVerticle(new DummyUpstreamVerticle(9002)))
            .compose(id -> vertx.deployVerticle(MainVerticle::new,
                new DeploymentOptions().setInstances(instances)))
            .onFailure(err -> {
                err.printStackTrace();
                System.exit(1);
            });
    }
}
