package org.example.gateway.day10.config;

/**
 * 파일 기반 config가 doc/*.md 스펙과 맞지 않을 때(필수 필드 누락, 지원 안 하는
 * enum 값, 참조 깨짐 등) 던진다 — "config-loader가 정의한 config대로 동작해야
 * 한다"는 원칙에 따라 애매하게 기본값으로 얼버무리지 않고 부팅을 막는다.
 */
public final class GatewayConfigException extends RuntimeException {

    public GatewayConfigException(String message) {
        super(message);
    }
}
