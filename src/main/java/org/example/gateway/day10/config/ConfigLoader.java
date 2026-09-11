package org.example.gateway.day10.config;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.example.gateway.day10.config.resource.ConnectorConfig;
import org.example.gateway.day10.config.resource.ConnectorTarget;
import org.example.gateway.day10.config.resource.ListenerConfig;
import org.example.gateway.day10.config.resource.LoadBalancingConfig;
import org.example.gateway.day10.config.resource.ResilienceSettings;
import org.example.gateway.day10.config.resource.RouterConfig;
import org.example.gateway.day10.config.resource.RouterDestination;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;

/**
 * {@code configDir}/listeners, connectors, routers 세 하위 디렉토리에서 리소스
 * 파일(각 파일 = 리소스 하나, doc/*.md 스펙의 JSON)을 읽는다. 파일 변경 감지(hot
 * reload)는 없다 — 부팅 시 한 번만 읽는다. 디렉토리가 없으면 그 kind는 0개로
 * 취급한다(예: 아직 Policy 리소스를 안 쓰면 policies/ 디렉토리 자체가 없어도 됨).
 *
 * 필수 필드가 없거나 아직 지원하지 않는 값(예: protocol=HTTPS, algorithm=RANDOM)이면
 * {@link GatewayConfigException}으로 즉시 실패한다 — "config-loader가 정의한
 * config대로 동작해야 한다"는 원칙에 따라 조용히 기본값으로 얼버무리지 않는다.
 */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static Future<GatewayConfig> load(Vertx vertx, String configDir) {
        Future<List<ListenerConfig>> listeners = loadResources(vertx, configDir + "/listeners", ConfigLoader::parseListener);
        Future<List<ConnectorConfig>> connectors = loadResources(vertx, configDir + "/connectors", ConfigLoader::parseConnector);
        Future<List<RouterConfig>> routers = loadResources(vertx, configDir + "/routers", ConfigLoader::parseRouter);

        return Future.all(listeners, connectors, routers)
            .map(cf -> new GatewayConfig(listeners.result(), connectors.result(), routers.result()));
    }

    private static <T> Future<List<T>> loadResources(Vertx vertx, String dir, BiFunction<String, JsonObject, T> parser) {
        return vertx.fileSystem().exists(dir)
            .compose(exists -> exists ? vertx.fileSystem().readDir(dir, ".*\\.json") : Future.succeededFuture(List.<String>of()))
            .compose(paths -> {
                List<String> sorted = new ArrayList<>(paths);
                sorted.sort(Comparator.naturalOrder());
                List<Future<T>> parsed = sorted.stream()
                    .map(path -> vertx.fileSystem().readFile(path).map(buf -> parser.apply(path, buf.toJsonObject())))
                    .toList();
                return Future.all(parsed).map(cf -> {
                    List<T> result = new ArrayList<>(parsed.size());
                    for (int i = 0; i < parsed.size(); i++) {
                        result.add(cf.resultAt(i));
                    }
                    return result;
                });
            });
    }

    // ---- Listener ----

    private static ListenerConfig parseListener(String path, JsonObject root) {
        requireKind(path, root, "Listener");
        JsonObject spec = requireObject(path, root, "spec");

        String protocol = requireString(path, spec, "protocol");
        if (!"HTTP".equals(protocol)) {
            throw new GatewayConfigException(path + ": protocol=" + protocol
                + "는 아직 지원하지 않습니다 (HTTP만 지원 — HTTPS 종단은 PLAN.md 2차 항목)");
        }

        return new ListenerConfig(
            requireString(path, root, "id"),
            requireString(path, root, "name"),
            protocol,
            requireInt(path, spec, "port"),
            spec.getString("host", "0.0.0.0")
        );
    }

    // ---- Connector ----

    private static ConnectorConfig parseConnector(String path, JsonObject root) {
        requireKind(path, root, "Connector");
        JsonObject spec = requireObject(path, root, "spec");

        String protocol = requireString(path, spec, "protocol");
        if (!"HTTP".equals(protocol)) {
            throw new GatewayConfigException(path + ": protocol=" + protocol
                + "는 아직 지원하지 않습니다 (HTTP만 지원 — 업스트림 TLS/GRPC/TCP는 PLAN.md 2차 항목)");
        }

        JsonObject lb = requireObject(path, spec, "loadBalancing");
        String algorithm = lb.getString("algorithm", "ROUND_ROBIN");
        if (!"ROUND_ROBIN".equals(algorithm)) {
            throw new GatewayConfigException(path + ": loadBalancing.algorithm=" + algorithm
                + "는 아직 지원하지 않습니다 (ROUND_ROBIN만 지원)");
        }
        JsonArray targetsJson = lb.getJsonArray("targets");
        if (targetsJson == null || targetsJson.isEmpty()) {
            throw new GatewayConfigException(path + ": spec.loadBalancing.targets는 최소 1개 필요합니다");
        }
        List<ConnectorTarget> targets = new ArrayList<>();
        for (int i = 0; i < targetsJson.size(); i++) {
            JsonObject t = targetsJson.getJsonObject(i);
            targets.add(new ConnectorTarget(
                requireString(path, t, "host"),
                requireInt(path, t, "port"),
                t.getInteger("weight", 1)
            ));
        }

        JsonObject retry = spec.getJsonObject("retry", new JsonObject());
        JsonObject circuitBreaker = spec.getJsonObject("circuitBreaker", new JsonObject());
        JsonObject timeout = spec.getJsonObject("timeout", new JsonObject());
        ResilienceSettings defaults = ResilienceSettings.defaults();
        ResilienceSettings resilience = new ResilienceSettings(
            circuitBreaker.getInteger("failureThreshold", defaults.maxFailures()),
            timeout.getLong("read", defaults.timeoutMs()),
            circuitBreaker.getLong("resetTimeout", defaults.resetTimeoutMs()),
            retry.getInteger("numRetries", defaults.maxRetries())
        );

        return new ConnectorConfig(
            requireString(path, root, "id"),
            requireString(path, root, "name"),
            protocol,
            requireString(path, spec, "proxyPath"),
            requireString(path, spec, "method"),
            new LoadBalancingConfig(algorithm, targets),
            resilience
        );
    }

    // ---- Router ----

    private static RouterConfig parseRouter(String path, JsonObject root) {
        requireKind(path, root, "Router");
        JsonObject spec = requireObject(path, root, "spec");
        JsonObject rule = requireObject(path, spec, "rule");
        JsonObject match = requireObject(path, rule, "match");

        JsonArray destinationsJson = spec.getJsonArray("destinations");
        if (destinationsJson == null || destinationsJson.isEmpty()) {
            throw new GatewayConfigException(path + ": spec.destinations는 최소 1개 필요합니다");
        }
        boolean multiple = destinationsJson.size() > 1;
        List<RouterDestination> destinations = new ArrayList<>();
        for (int i = 0; i < destinationsJson.size(); i++) {
            JsonObject d = destinationsJson.getJsonObject(i);
            JsonObject ref = requireObject(path, d, "destinationRef");
            String kind = requireString(path, ref, "kind");
            if (!"Connector".equals(kind)) {
                throw new GatewayConfigException(path + ": destinationRef.kind=" + kind
                    + "는 아직 지원하지 않습니다 (Connector만 지원 — Flow는 스펙 미정의)");
            }
            Integer weight = d.getInteger("weight");
            if (weight == null) {
                if (multiple) {
                    throw new GatewayConfigException(path + ": destinations가 2개 이상이면 각 항목에 weight가 필요합니다");
                }
                weight = 1;
            }
            destinations.add(new RouterDestination(requireString(path, ref, "id"), weight));
        }

        return new RouterConfig(
            requireString(path, root, "id"),
            requireString(path, root, "name"),
            requireString(path, match, "path"),
            requireString(path, match, "methods"),
            destinations
        );
    }

    // ---- helpers ----

    private static void requireKind(String path, JsonObject root, String expectedKind) {
        String kind = root.getString("kind");
        if (!expectedKind.equals(kind)) {
            throw new GatewayConfigException(path + ": kind은 " + expectedKind + "여야 하는데 " + kind + "입니다");
        }
    }

    private static JsonObject requireObject(String path, JsonObject parent, String field) {
        JsonObject value = parent.getJsonObject(field);
        if (value == null) {
            throw new GatewayConfigException(path + ": 필수 필드 " + field + "가 없습니다");
        }
        return value;
    }

    private static String requireString(String path, JsonObject parent, String field) {
        String value = parent.getString(field);
        if (value == null || value.isBlank()) {
            throw new GatewayConfigException(path + ": 필수 필드 " + field + "가 없습니다");
        }
        return value;
    }

    private static int requireInt(String path, JsonObject parent, String field) {
        Integer value = parent.getInteger(field);
        if (value == null) {
            throw new GatewayConfigException(path + ": 필수 필드 " + field + "가 없습니다");
        }
        return value;
    }
}
