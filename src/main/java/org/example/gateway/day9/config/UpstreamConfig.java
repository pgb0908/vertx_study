package org.example.gateway.day9.config;

import org.example.gateway.day9.proxy.Upstream;

import java.util.List;
import java.util.Map;

public record UpstreamConfig(Map<String, List<Upstream>> groups) {

    public List<Upstream> group(String name) {
        List<Upstream> group = groups.get(name);
        if (group == null || group.isEmpty()) {
            throw new IllegalArgumentException("unknown or empty upstream group: " + name);
        }
        return group;
    }
}
