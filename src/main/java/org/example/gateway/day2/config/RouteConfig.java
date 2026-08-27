package org.example.gateway.day2.config;

import java.util.List;

/**
 * 라우트 하나에 대한 설정: 어떤 경로에, 어떤 이름의 필터들을 어떤 순서로 붙일지.
 * 이 객체가 코드의 .handler() 하드코딩을 대체하는 "데이터"다.
 */
public record RouteConfig(String path, List<String> filters) {
}
