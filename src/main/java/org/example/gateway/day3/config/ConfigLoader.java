package org.example.gateway.day3.config;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.example.gateway.day3.proxy.Upstream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * routes.json + upstreams.json 두 파일을 각각 비동기로 읽고, 둘 다 끝나면
 * 하나의 GatewayConfig로 합친다. (Future.all로 두 읽기를 동시에 진행)
 */
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
            routes.add(new RouteConfig(r.getString("path"), filters, r.getString("upstreamGroup")));
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
