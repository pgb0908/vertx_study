package org.example.gateway.day9.filter;

import io.vertx.core.buffer.Buffer;

/**
 * RequestBodyFilter가 처리를 마쳤을 때 부르는 콜백. forward()는 (필요하면 변형한) 바디를
 * 들고 다음 단계로 진행하고, stopWithResponse()는 FilterCallback과 동일한 의미다.
 */
public interface RequestBodyCallback {

    void forward(Buffer body);

    void stopWithResponse();
}
