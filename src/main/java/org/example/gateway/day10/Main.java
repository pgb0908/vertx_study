package org.example.gateway.day10;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.example.gateway.day10.config.ConfigLoader;
import org.example.gateway.day10.config.GatewayConfig;
import org.example.gateway.day10.config.GatewayConfigException;
import org.example.gateway.day10.config.RuntimeSnapshotCompiler;
import org.example.gateway.day10.config.resource.ListenerConfig;
import org.example.gateway.day10.engine.GatewayEngine;
import org.example.gateway.day10.engine.route.RuntimeSnapshot;
import org.example.gateway.day10.runtime.vertx.VertxGatewayServer;
import org.example.gateway.day9.upstream.DummyUpstreamVerticle;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 파일 기반 config로 부팅한다 — {@code -Dgateway.configDir}(기본
 * {@code config/day10})의 listeners/connectors/routers 하위 디렉토리에서
 * doc/*.md 스펙의 JSON 리소스를 읽어({@link ConfigLoader}) 실행 가능한
 * {@link RuntimeSnapshot}으로 컴파일한다({@link RuntimeSnapshotCompiler}).
 * hot-reload는 없다 — 부팅 시 한 번만 읽는다(PLAN.md 2차 항목).
 */
public class Main {

    private static final long DRAIN_TIMEOUT_MS = 10_000;

    public static void main(String[] args) {
        String configDir = System.getProperty("gateway.configDir", "config/day10");

        Vertx vertx = Vertx.vertx();
        AtomicReference<VertxGatewayServer> serverRef = new AtomicReference<>();

        // graceful shutdown: in-flight 요청이 끝날 때까지 먼저 기다린 뒤 vertx.close().
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            VertxGatewayServer server = serverRef.get();
            Future<Void> shutdown = server != null
                ? server.stop(DRAIN_TIMEOUT_MS).compose(v -> vertx.close())
                : vertx.close();
            shutdown.toCompletionStage().toCompletableFuture().join();
        }));

        // 로컬 테스트 편의를 위해 더미 백엔드도 같이 띄운다(day7~9와 동일 관례) — 실무
        // 배포라면 이 줄 자체가 없고, config의 Connector가 이미 떠 있는 실제 백엔드를
        // 가리킨다. config/day10/connectors/echo-connector.json이 localhost:9001을
        // 가리키도록 맞춰뒀다.
        vertx.deployVerticle(new DummyUpstreamVerticle(9001))
            .compose(id -> ConfigLoader.load(vertx, configDir))
            .compose(config -> {
                ListenerConfig listener = requireSingleListener(config);
                RuntimeSnapshot snapshot = RuntimeSnapshotCompiler.compile(vertx, config);
                GatewayEngine engine = new GatewayEngine(() -> snapshot);
                VertxGatewayServer server = new VertxGatewayServer(vertx, engine);
                serverRef.set(server);
                return server.start(listener.port());
            })
            .onFailure(err -> {
                err.printStackTrace();
                System.exit(1);
            });
    }

    /** v1은 Listener를 1개만 지원한다 — 0개나 2개 이상이면 무엇을 골라야 할지
     *  애매하게 넘어가지 않고 부팅 자체를 막는다. */
    private static ListenerConfig requireSingleListener(GatewayConfig config) {
        if (config.listeners().isEmpty()) {
            throw new GatewayConfigException("config의 listeners/ 디렉토리에 Listener가 하나도 없습니다");
        }
        if (config.listeners().size() > 1) {
            throw new GatewayConfigException("Listener는 지금 1개만 지원합니다 ("
                + config.listeners().size() + "개 발견 — 다중 Listener는 PLAN.md 2차 항목)");
        }
        return config.listeners().get(0);
    }
}
