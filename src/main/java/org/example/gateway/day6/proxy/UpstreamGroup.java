package org.example.gateway.day6.proxy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 라운드로빈으로 다음 업스트림을 고른다. 단일 인스턴스 프로토타입이므로
 * AtomicInteger 하나로 충분하다 (여러 인스턴스로 확장 시 이 상태는 인스턴스별로 분리됨).
 */
public class UpstreamGroup {

    private final List<Upstream> upstreams;
    private final AtomicInteger cursor = new AtomicInteger(0);

    public UpstreamGroup(List<Upstream> upstreams) {
        this.upstreams = upstreams;
    }

    public Upstream next() {
        int index = Math.floorMod(cursor.getAndIncrement(), upstreams.size());
        return upstreams.get(index);
    }
}
