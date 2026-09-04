package org.example.gateway.day10.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Vert.x MultiMap 대체. 대소문자 무시(HTTP 헤더 관례) + 값 다중 보유만 지원하는
 * 최소한의 자체 타입 — domain이 io.vertx.core.MultiMap을 몰라도 되게 한다.
 */
public final class GatewayHeaders {

    private final Map<String, List<String>> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public static GatewayHeaders empty() {
        return new GatewayHeaders();
    }

    public GatewayHeaders add(String name, String value) {
        values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        return this;
    }

    public List<String> get(String name) {
        return values.getOrDefault(name, List.of());
    }

    public Map<String, List<String>> asMap() {
        return new LinkedHashMap<>(values);
    }
}
