package org.example.gateway.day10.engine.route;

/**
 * 요청 처리 시점에 사용하는, 이미 컴파일된 불변 런타임 구성 (feedback 13절).
 * Config가 바뀌면 새 RuntimeSnapshot을 통째로 만들고 원자적으로 교체한다 —
 * 처리 중인 요청이 신/구 설정을 섞어 쓰는 일이 없게 하기 위해서다.
 *
 * v1은 원자적 교체(hot-reload)를 하지 않는다 — Main.java가 부팅 시 한 번만
 * 만들어서 고정한다. Config 파일 로딩과 원자적 스왑은 PLAN.md 2차 항목 5.
 */
public final class RuntimeSnapshot {

    private final RouteTable routeTable;

    public RuntimeSnapshot(RouteTable routeTable) {
        this.routeTable = routeTable;
    }

    public RouteTable routeTable() {
        return routeTable;
    }
}
