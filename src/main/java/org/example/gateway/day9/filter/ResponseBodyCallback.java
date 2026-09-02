package org.example.gateway.day9.filter;

import io.vertx.core.buffer.Buffer;

/**
 * ResponseBodyFilter가 처리를 마쳤을 때 부르는 콜백. 응답은 이미 업스트림에서 돌아온
 * 뒤라 "중단"이라는 개념이 없다 — 항상 (필요하면 변형한) 바디를 들고 다음 단계로
 * 넘겨야(결국 클라이언트로 나가야) 한다.
 */
public interface ResponseBodyCallback {

    void forward(Buffer body);
}
