package org.example.gateway.day7.config;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.example.gateway.day7.proxy.Upstream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static Future<GatewayConfig> load(Vertx vertx, String routesPath, String upstreamsPath) {
        Future<List<RouteConfig>> routesFuture = vertx.fileSystem().readFile(routesPath).map(ConfigLoader::parseRoutes);
        Future<UpstreamConfig> upstreamsFuture = vertx.fileSystem().readFile(upstreamsPath).map(ConfigLoader::parseUpstreams);

        return Future.all(routesFuture, upstreamsFuture)
            .map(compositeFuture -> new GatewayConfig(routesFuture.result(), upstreamsFuture.result()));
    }

    private static List<RouteConfig> parseRoutes(Buffer buffer) {
        JsonObject root = buffer.toJsonObject();
        JsonArray routesJson = root.getJsonArray("routes", new JsonArray());

        List<RouteConfig> routes = new ArrayList<>();
        for (int i = 0; i < routesJson.size(); i++) {
            JsonObject r = routesJson.getJsonObject(i);
            List<String> filters = new ArrayList<>();
            for (Object f : r.getJsonArray("filters", new JsonArray())) {
                filters.add((String) f);
            }

            // day5와 다른 점: rateLimit 객체가 있으면 파싱, 없으면(대부분의 라우트) null.
            JsonObject rateLimitJson = r.getJsonObject("rateLimit");
            RateLimitConfig rateLimit = rateLimitJson == null ? null
                : new RateLimitConfig(rateLimitJson.getDouble("requestsPerSecond"), rateLimitJson.getInteger("burstSize"));

            // day6와 다른 점: resilience 객체가 있으면 파싱, 없으면 null (day6까지의 방식 그대로 프록시).
            JsonObject resilienceJson = r.getJsonObject("resilience");
            ResilienceConfig resilience = resilienceJson == null ? null
                : new ResilienceConfig(resilienceJson.getInteger("maxFailures"),
                    resilienceJson.getLong("timeoutMs"), resilienceJson.getLong("resetTimeoutMs"),
                    resilienceJson.getInteger("maxRetries"));

            routes.add(new RouteConfig(r.getString("path"), filters, r.getString("upstreamGroup"), rateLimit, resilience));
        }
        return routes;
    }

    private static UpstreamConfig parseUpstreams(Buffer buffer) {
        JsonObject root = buffer.toJsonObject();
        JsonObject groupsJson = root.getJsonObject("groups", new JsonObject());

        Map<String, List<Upstream>> groups = new LinkedHashMap<>();
        for (String groupName : groupsJson.fieldNames()) {
            List<Upstream> upstreams = new ArrayList<>();
            for (Object entry : groupsJson.getJsonArray(groupName)) {
                JsonObject u = (JsonObject) entry;
                upstreams.add(new Upstream(u.getString("host"), u.getInteger("port")));
            }
            groups.put(groupName, upstreams);
        }
        return new UpstreamConfig(groups);
    }
}
