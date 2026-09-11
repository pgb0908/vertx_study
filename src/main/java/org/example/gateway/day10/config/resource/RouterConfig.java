package org.example.gateway.day10.config.resource;

import java.util.List;

/**
 * doc/Router.md spec의 최소 실행 가능 부분집합. destinations가 2개 이상이면
 * Router.md 예시1처럼 weight로 서로 다른 Connector에 분산한다(engine.ConnectorSelector).
 * requestMsgTpl/responseMsgTpl은 지금 읽지 않는다.
 */
public record RouterConfig(String id, String name, String path, String method, List<RouterDestination> destinations) {
}
