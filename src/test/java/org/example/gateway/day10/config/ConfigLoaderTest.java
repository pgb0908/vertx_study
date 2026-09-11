package org.example.gateway.day10.config;

import io.vertx.core.Vertx;
import org.example.gateway.day10.config.resource.RouterConfig;
import org.example.gateway.day10.config.resource.RouterDestination;
import org.example.gateway.day10.engine.route.RuntimeSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 config/day10 예시 파일(Main.java가 부팅 시 읽는 것과 동일)을 로딩해서
 * 검증 + 컴파일까지 되는지 확인하고, 스펙 위반 config는 GatewayConfigException으로
 * 명확히 거부되는지 확인한다.
 */
class ConfigLoaderTest {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void loadsRealExampleConfig() throws Exception {
        GatewayConfig config = load("config/day10");

        assertEquals(1, config.listeners().size());
        assertEquals(8080, config.listeners().get(0).port());
        assertEquals(1, config.connectors().size());
        assertEquals("echo-connector", config.connectors().get(0).id());
        assertEquals(2, config.routers().size());
    }

    @Test
    void compilesExampleConfigIntoMatchableSnapshot() throws Exception {
        GatewayConfig config = load("config/day10");
        RuntimeSnapshot snapshot = RuntimeSnapshotCompiler.compile(vertx, config);

        assertTrue(snapshot.routeTable().match("GET", "/echo").isPresent());
        assertTrue(snapshot.routeTable().match("POST", "/echo").isPresent());
        assertTrue(snapshot.routeTable().match("DELETE", "/echo").isEmpty());
    }

    @Test
    void rejectsUnsupportedListenerProtocol(@TempDir Path dir) throws IOException {
        writeJson(dir, "listeners", "l1", """
            {"apiVersion":"iip.gateway/v1alpha1","kind":"Listener","uid":"u","workspaceId":"dev",
             "id":"l1","name":"l1","version":"v1","description":"d","metadata":{"name":"l1"},
             "spec":{"protocol":"HTTPS","port":8443}}
            """);

        assertThrows(GatewayConfigException.class, () -> load(dir.toString()));
    }

    @Test
    void rejectsUnsupportedLoadBalancingAlgorithm(@TempDir Path dir) throws IOException {
        writeJson(dir, "connectors", "c1", """
            {"apiVersion":"iip.gateway/v1alpha1","kind":"Connector","uid":"u","workspaceId":"dev",
             "id":"c1","name":"c1","version":"v1","description":"d","metadata":{"name":"c1"},
             "spec":{"protocol":"HTTP","proxyPath":"/x","method":"GET",
                     "loadBalancing":{"algorithm":"RANDOM","targets":[{"host":"h","port":1}]}}}
            """);

        assertThrows(GatewayConfigException.class, () -> load(dir.toString()));
    }

    @Test
    void rejectsFlowDestination(@TempDir Path dir) throws IOException {
        writeJson(dir, "routers", "r1", """
            {"apiVersion":"iip.gateway/v1alpha1","kind":"Router","uid":"u","workspaceId":"dev",
             "id":"r1","name":"r1","version":"v1","description":"d","metadata":{"name":"r1"},
             "spec":{"rule":{"protocol":"HTTP","match":{"path":"/x","methods":"GET"}},
                     "destinations":[{"destinationRef":{"kind":"Flow","uid":"u","id":"f1","name":"f1"}}]}}
            """);

        assertThrows(GatewayConfigException.class, () -> load(dir.toString()));
    }

    @Test
    void rejectsMultipleDestinationsWithoutWeight(@TempDir Path dir) throws IOException {
        writeJson(dir, "routers", "r1", """
            {"apiVersion":"iip.gateway/v1alpha1","kind":"Router","uid":"u","workspaceId":"dev",
             "id":"r1","name":"r1","version":"v1","description":"d","metadata":{"name":"r1"},
             "spec":{"rule":{"protocol":"HTTP","match":{"path":"/x","methods":"GET"}},
                     "destinations":[
                       {"destinationRef":{"kind":"Connector","uid":"u","id":"c1","name":"c1"}},
                       {"destinationRef":{"kind":"Connector","uid":"u","id":"c2","name":"c2"}}
                     ]}}
            """);

        assertThrows(GatewayConfigException.class, () -> load(dir.toString()));
    }

    @Test
    void compileFailsOnDanglingConnectorReference() {
        GatewayConfig config = new GatewayConfig(
            List.of(),
            List.of(),
            List.of(new RouterConfig("r1", "r1", "/x", "GET",
                List.of(new RouterDestination("does-not-exist", 1))))
        );

        assertThrows(GatewayConfigException.class, () -> RuntimeSnapshotCompiler.compile(vertx, config));
    }

    private GatewayConfig load(String dir) throws Exception {
        try {
            return ConfigLoader.load(vertx, dir).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private void writeJson(Path root, String kindDir, String fileName, String json) throws IOException {
        Path dir = root.resolve(kindDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName + ".json"), json);
    }
}
