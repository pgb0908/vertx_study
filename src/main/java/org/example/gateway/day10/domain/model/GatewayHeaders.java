package org.example.gateway.day10.domain.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Vert.x MultiMap 대체. 대소문자 무시(HTTP 헤더 관례) + 값 다중 보유를 지원하는
 * 최소한의 자체 타입 — domain이 io.vertx.core.MultiMap을 몰라도 되게 한다.
 *
 * 진짜 불변 값 객체다(feedback 4절: GatewayRequest/GatewayResponse는 record라서
 * "불변"이라고 부르는데, 예전엔 그 안에 담긴 이 클래스가 add()로 자기 자신을
 * 변경하고 반환하는 mutable 클래스였다 — record는 필드 참조만 불변으로 만들어줄
 * 뿐, 그 참조가 가리키는 객체 내부까지 불변으로 만들어주지는 않는다는 흔한 함정).
 * 생성 후에는 절대 내부 상태가 바뀌지 않는다 — "값 하나 추가"는 {@link #withAdded}로
 * 새 인스턴스를 만들고, "여러 건을 모아서 짓기"는 {@link Builder}를 쓴다.
 */
public final class GatewayHeaders {

    private final Map<String, List<String>> values;

    private GatewayHeaders(Map<String, List<String>> values) {
        this.values = values;
    }

    public static GatewayHeaders empty() {
        return new GatewayHeaders(Map.of());
    }

    /** 이 헤더에 값 하나를 더한 새 GatewayHeaders를 반환한다 (자기 자신은 안 바뀜). */
    public GatewayHeaders withAdded(String name, String value) {
        return builder().addAll(this).add(name, value).build();
    }

    public List<String> get(String name) {
        // values는 TreeMap(CASE_INSENSITIVE_ORDER) 기반이라 대소문자와 무관하게 찾는다.
        return values.getOrDefault(name, List.of());
    }

    public Map<String, List<String>> asMap() {
        return new LinkedHashMap<>(values);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 여러 헤더를 모아서 한 번에 GatewayHeaders를 짓는 용도(예: 어댑팅 시 반복문). */
    public static final class Builder {

        private final Map<String, List<String>> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        private Builder() {
        }

        public Builder add(String name, String value) {
            values.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            return this;
        }

        public Builder addAll(GatewayHeaders source) {
            source.values.forEach((name, list) -> list.forEach(v -> add(name, v)));
            return this;
        }

        public GatewayHeaders build() {
            Map<String, List<String>> frozen = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            values.forEach((name, list) -> frozen.put(name, Collections.unmodifiableList(new ArrayList<>(list))));
            return new GatewayHeaders(Collections.unmodifiableMap(frozen));
        }
    }
}
