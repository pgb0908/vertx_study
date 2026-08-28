package org.example.gateway.day4.config;

import java.util.List;

/**
 * upstreamGroup이 있으면 필터 체인 마지막에 리버스 프록시 handler가 붙는다.
 * upstreamGroup이 없으면(null) day2처럼 filters 목록의 마지막 항목이 응답을 끝내야 한다.
 */
public record RouteConfig(String path, List<String> filters, String upstreamGroup) {
}
