package org.example.gateway.day2.config;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 프로젝트 루트 기준 상대경로의 JSON 파일을 읽어 RouteConfig 목록으로 파싱한다.
 * Day 3에서는 이 자리가 YAML + vertx-config로, Day 5에서는 파일 watch로 확장된다.
 */
public final class ConfigLoader {

    private ConfigLoader() {
    }

    public static Future<List<RouteConfig>> load(Vertx vertx, String path) {
        return vertx.fileSystem().readFile(path).map(ConfigLoader::parse);
    }

    private static List<RouteConfig> parse(io.vertx.core.buffer.Buffer buffer) {
        JsonObject root = buffer.toJsonObject();
        JsonArray routesJson = root.getJsonArray("routes", new JsonArray());

        List<RouteConfig> routes = new ArrayList<>();
        for (int i = 0; i < routesJson.size(); i++) {
            JsonObject r = routesJson.getJsonObject(i);
            List<String> filters = new ArrayList<>();
            for (Object f : r.getJsonArray("filters", new JsonArray())) {
                filters.add((String) f);
            }
            routes.add(new RouteConfig(r.getString("path"), filters));
        }
        return routes;
    }
}
