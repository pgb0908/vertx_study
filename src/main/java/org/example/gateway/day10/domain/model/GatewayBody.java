package org.example.gateway.day10.domain.model;

import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * Gateway 요청/응답 바디. Vert.x Buffer/ReadStream이 아니라 JDK 표준
 * java.util.concurrent.Flow.Publisher로 정의해서, domain/engine이 Vert.x를
 * 몰라도 바디를 스트리밍으로 다룰 수 있게 한다 (grill-me에서 합의: 나중에
 * 버퍼링에서 스트리밍으로 바꾸는 두 번째 마이그레이션을 피하기 위해 처음부터
 * Publisher 인터페이스로 시작).
 *
 * runtime-vertx의 VertxReadStreamPublisher가 실제 스트리밍 구현체이고,
 * of(byte[])는 테스트/단순 케이스를 위한 "이미 다 준비된" 구현체다.
 */
public interface GatewayBody extends Flow.Publisher<byte[]> {

    static GatewayBody of(byte[] data) {
        return subscriber -> {
            SubmissionPublisher<byte[]> publisher = new SubmissionPublisher<>(Runnable::run, 1);
            publisher.subscribe(subscriber);
            if (data.length > 0) {
                publisher.submit(data);
            }
            publisher.close();
        };
    }

    GatewayBody EMPTY = of(new byte[0]);
}
