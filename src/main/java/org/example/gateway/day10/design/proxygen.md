# Proxygen 필터 구조 정리

Facebook의 C++ HTTP 프레임워크. day10 설계 참고용.
GitHub: https://github.com/facebook/proxygen

---

## 핵심 인터페이스

### RequestHandler

```cpp
class RequestHandler {
public:
  // 요청 헤더 파싱 완료 — 첫 번째 진입점
  virtual void onRequest(std::unique_ptr<HTTPMessage> headers) noexcept = 0;

  // 요청 바디 청크 수신마다 호출
  virtual void onBody(std::unique_ptr<folly::IOBuf> body) noexcept = 0;

  // 요청 수신 완료 (End Of Message)
  virtual void onEOM() noexcept = 0;

  // 응답이 클라이언트로 완전히 전송된 후
  virtual void requestComplete() noexcept = 0;

  // 에러 발생 시 — 이후 콜백 없음
  virtual void onError(ProxygenError err) noexcept = 0;

  // HTTP/2 흐름 제어
  virtual void onEgressPaused() noexcept {}
  virtual void onEgressResumed() noexcept {}

protected:
  ResponseHandler* downstream_{nullptr};  // 응답 전송용 포인터
};
```

---

## Filter 구조 — 양방향 체인

Proxygen Filter의 핵심 설계: **RequestHandler이면서 동시에 ResponseHandler**.
`upstream_`(요청 방향)과 `downstream_`(응답 방향) 두 포인터로 양방향 체인을 구성한다.

```
client
  │  onRequest / onBody / onEOM         (요청 방향 →)
  ▼
Filter[0] ──upstream_──► Filter[1] ──upstream_──► ActualHandler
  ▲
  │  sendHeaders / sendBody / sendEOM   (응답 방향 ←)
  │
downstream_
```

```cpp
class Filter : public RequestHandler, public ResponseHandler {
public:
  explicit Filter(RequestHandler* upstream) : ResponseHandler(upstream) {}

  // ── 요청 방향 (client → upstream) ──
  void onRequest(std::unique_ptr<HTTPMessage> headers) noexcept override {
    // headers를 수정한 뒤 upstream으로 전달
    upstream_->onRequest(std::move(headers));
  }
  void onBody(std::unique_ptr<folly::IOBuf> body) noexcept override {
    upstream_->onBody(std::move(body));
  }
  void onEOM() noexcept override {
    upstream_->onEOM();
  }

  // ── 응답 방향 (upstream → client) ──
  void sendHeaders(HTTPMessage& msg) noexcept override {
    // msg를 수정한 뒤 downstream으로 전달
    downstream_->sendHeaders(msg);
  }
  void sendBody(std::unique_ptr<folly::IOBuf> body) noexcept override {
    downstream_->sendBody(std::move(body));
  }
  void sendEOM() noexcept override {
    downstream_->sendEOM();
  }

private:
  RequestHandler*  upstream_{nullptr};    // 요청 방향 다음 핸들러
  ResponseHandler* downstream_{nullptr};  // 응답 방향 이전 핸들러 (클라이언트 방향)
};
```

---

## request / response 수정 방법

### 요청 헤더 수정 (onRequest override)

```cpp
void MyFilter::onRequest(std::unique_ptr<HTTPMessage> headers) noexcept override {
  // 헤더 추가
  headers->getHeaders().add("X-Request-Id", generateId());
  // 헤더 제거 (Authorization 스트리핑 등)
  headers->getHeaders().remove(HTTP_HEADER_AUTHORIZATION);
  // 수정된 headers를 upstream으로 forwarding
  upstream_->onRequest(std::move(headers));
}
```

### 응답 헤더/바디 수정 (CompressionFilter 예시)

```cpp
void CompressionFilter::sendHeaders(HTTPMessage& msg) noexcept override {
  msg.getHeaders().set(HTTP_HEADER_CONTENT_ENCODING, "gzip");
  if (!chunked_) {
    header_ = msg;  // 비청크 응답: body까지 모은 뒤 content-length 계산
    return;         // downstream에 아직 전달하지 않음
  }
  downstream_->sendHeaders(msg);
}

void CompressionFilter::sendBody(std::unique_ptr<folly::IOBuf> body) noexcept override {
  auto compressed = compressor_->compress(body.get());  // 바디 교체
  downstream_->sendBody(std::move(compressed));
}
```

---

## 필터 체인 구성 — RequestHandlerChain

### RequestHandlerFactory 인터페이스

```cpp
class RequestHandlerFactory {
public:
  // 스레드 시작 시 1회 — DB 커넥션, 캐시 등 스레드 로컬 자원 초기화
  virtual void onServerStart(folly::EventBase* evb) noexcept = 0;

  // 스레드 종료 시 1회
  virtual void onServerStop() noexcept = 0;

  // 요청마다 호출 — 새 Filter 인스턴스를 생성해서 반환
  virtual RequestHandler* onRequest(RequestHandler* upstream,
                                     HTTPMessage* msg) noexcept = 0;
};
```

### 빌더 패턴으로 체인 조립

```cpp
auto chain = RequestHandlerChain()
  .addThen<LoggingFilterFactory>()
  .addThen<AuthFilterFactory>(secret)
  .addThen<CompressionFilterFactory>(params)
  .addThen<MyAppHandlerFactory>()   // 실제 비즈니스 핸들러 (체인 끝)
  .build();

HTTPServerOptions options;
options.handlerFactories = std::move(chain);
HTTPServer server(std::move(options));
```

각 factory의 `onRequest(upstream, msg)`가 자신을 upstream으로 감싼 Filter를 반환하면서
체인이 구성된다. 요청은 factory 등록 순서대로(0→1→2), 응답은 역순(2→1→0)으로 흐른다.

---

## day10 GatewayPolicy와 비교

| 항목 | Proxygen Filter | day10 GatewayPolicy |
|---|---|---|
| **요청 수정** | `onRequest`에서 `HTTPMessage` 직접 수정 후 forwarding | `before()` 반환이 `Void` → **현재 불가** |
| **응답 수정** | `sendHeaders/sendBody` override로 교체 | `after()` 반환값 `GatewayResponse`로 교체 |
| **체인 방향** | `upstream_` / `downstream_` 포인터 이중 연결 | `List<GatewayPolicy>` 인덱스 순회 |
| **실행 모델** | 청크 단위 스트리밍, 요청/응답 교차 가능 | before 완료 → upstream → after 순차 |
| **before/after** | 명시적 구분 없음, 데이터 흐름 방향으로 분리 | `before()` / `after()` 명시적 분리 |
| **런타임 결합** | Proxygen 타입이 Filter 전체에 노출 | domain/engine은 Vert.x 의존 0개 |
| **스레드 모델** | 이벤트 루프, 청크 교차 실행 | CompletableFuture 순차 파이프라인 |

### day10에서 요청 수정이 필요해지면

JWT policy, 헤더 조작, 경로 재작성 등을 구현하려면 `before()`가 변환된
`GatewayRequest`를 반환할 수 있어야 한다.

```java
// 현재 — 변환 결과를 돌려줄 수단 없음
CompletableFuture<Void> before(GatewayExchange exchange)

// Proxygen onRequest에 대응하는 구조로 변경 시
CompletableFuture<GatewayRequest> before(GatewayExchange exchange, GatewayRequest request)
```

`runBefore`가 변환된 `GatewayRequest`를 다음 policy로 넘기도록 바꾸면
Proxygen의 `upstream_->onRequest(std::move(headers))` 패턴과 동일해진다.
