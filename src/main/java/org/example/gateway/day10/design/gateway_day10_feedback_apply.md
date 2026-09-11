# Gateway day10 피드백 적용 정리

## 1. 기본 원칙

Vert.x는 Gateway 자체의 아키텍처가 아니라 **네트워크 런타임 구현체**로 둔다.

```text
gateway-domain
      ↑
gateway-engine
      ↑
gateway-runtime-vertx
```

Core에서는 `RoutingContext`, `HttpServerRequest`, `HttpServerResponse`, `HttpClient`, `Router`, `Route` 같은 Vert.x 타입이 보이지 않도록 한다.

---

## 2. GatewayEngine이 실행 흐름을 소유

Gateway 처리 순서는 Vert.x Handler Chain이 아니라 `GatewayEngine`에서 명시적으로 보여야 한다.

```text
Request
  ↓
Downstream Filter Chain
  ↓
Endpoint Selection
  ↓
Retry / CircuitBreaker / Timeout
  ↓
Upstream
  ↓
Upstream Filter Chain
  ↓
Response
```

`GatewayEngine`은 가능한 한 stateless하게 두고, 거래별 구성은 `GatewayExchange -> RuntimeRoute`에서 가져간다.

---

## 3. Filter의 upstream / downstream 개념은 유지

요청/응답 양방향 흐름을 코드에서 명확히 표현하는 것은 유지한다.

```text
Client
  ↓
Filter A
  ↓
Filter B
  ↓
Filter C
  ↓
Backend
  ↓
Filter C
  ↓
Filter B
  ↓
Filter A
  ↓
Client
```

다만 Filter 객체끼리 mutable pointer로 연결하지 않는다.

### 지양

```text
Filter A
 ├─ downstream -> Filter B
 └─ upstream   -> null
```

### 권장

```text
FilterChain
 ├─ executeDownstream(): 0 -> N
 └─ executeUpstream():   N -> 0
```

즉 **Filter는 자신의 동작만 알고, 실행 topology는 FilterChain이 소유**한다.

---

## 4. Request / Response는 immutable

`GatewayRequest`, `GatewayResponse`는 불변 객체로 유지한다.

```java
public record GatewayRequest(...) {}
public record GatewayResponse(...) {}
```

필터에서 내부 값을 직접 수정하지 않고 변경된 새 객체를 만든다.

```java
GatewayRequest updated =
    request.withHeader("X-Trace-Id", traceId);

exchange.request(updated);
```

즉 **변경은 허용하지만 mutation은 새 객체 교체 방식으로 통제**한다.

---

## 5. GatewayExchange는 mutable execution context

Request/Response 자체는 immutable이지만 `GatewayExchange`는 현재 처리 상태를 가리키도록 mutable하게 둔다.

```java
public final class GatewayExchange {

    private final GatewayRequest originalRequest;

    private GatewayRequest request;

    private GatewayResponse upstreamResponse;

    private GatewayResponse response;

    private final ExchangeAttributes attributes;
}
```

의미는 다음과 같다.

```text
originalRequest
    = Client가 실제 보낸 요청

request
    = 현재 Filter Chain을 거친 요청

upstreamResponse
    = Backend가 실제 보낸 응답

response
    = 현재 Filter Chain을 거친 응답
```

전체 흐름:

```text
Client
  ↓
originalRequest
  ↓
request
  ↓
Downstream Filters
  ↓
request'
  ↓
Backend
  ↓
upstreamResponse
  ↓
response
  ↓
Upstream Filters
  ↓
response'
  ↓
Client
```

---

## 6. GatewayBody는 별도 취급

`GatewayRequest`가 immutable이어도 Body는 streaming object일 수 있다.

```text
GatewayBody
    =
Flow.Publisher<byte[]>
```

따라서 immutable request가 곧 body replay 가능을 의미하지는 않는다.

```text
Streaming Body
    -> replay 불가
    -> retry 제한

Buffered Body
    -> replay 가능
    -> retry 가능
```

Retry 설계에서는 body replayability를 별도로 판단해야 한다.

---

## 7. 모든 기능을 Filter로 만들지 않는다

Filter에 적합한 기능:

```text
Authentication
Header Rewrite
Parameter Transform
Logging
Access Control
```

별도 execution component가 적합한 기능:

```text
Retry
Circuit Breaker
Timeout
Load Balancing
Endpoint Health
```

권장 구조:

```text
GatewayEngine

├─ Route Match
├─ Downstream FilterChain
├─ EndpointSelector
├─ ExecutionPolicy
│   ├─ Retry
│   ├─ CircuitBreaker
│   └─ Timeout
├─ UpstreamClient
└─ Upstream FilterChain
```

---

## 8. Runtime 구조

실무 구조에서는 다음 형태를 목표로 한다.

```text
RuntimeSnapshot
  │
  ├─ RouteTable
  │    └─ RuntimeRoute
  │         ├─ FilterChain
  │         ├─ EgressGroup
  │         └─ Policy
  │
  ├─ EgressGroups
  └─ EndpointRuntime
```

Config 변경 시 기존 Runtime 객체를 직접 수정하지 않고 새 Snapshot을 만든 뒤 교체한다.

```text
Config
  ↓
Validate
  ↓
Compile
  ↓
RuntimeSnapshot
  ↓
Atomic Replace
```

---

## 9. 우선 적용 순서

1. `FilterChain` 도입
2. Filter의 upstream/downstream pointer 제거
3. `executeDownstream()` / `executeUpstream()` 명시
4. `GatewayRequest`, `GatewayResponse` immutable 유지
5. `GatewayExchange`에 original/current request-response 구분
6. `GatewayEngine` stateless화
7. `RuntimeRoute`에 FilterChain 포함
8. `EndpointSelector`, `UpstreamExecutor` 분리
9. Retry / CircuitBreaker / Timeout을 Filter 밖 execution policy로 분리
10. `RuntimeSnapshot` 도입

---

## 핵심 정리

```text
Vert.x
    = Network Runtime

GatewayEngine
    = Execution Owner

FilterChain
    = Upstream / Downstream Flow Owner

GatewayRequest / GatewayResponse
    = Immutable Value Object

GatewayExchange
    = Mutable Execution Context

RuntimeSnapshot
    = Immutable Runtime Configuration
```

최종 목표 구조:

```text
Client
  ↓
Vert.x Runtime
  ↓
GatewayExchange
  ↓
GatewayEngine
  ↓
Downstream FilterChain
  ↓
EndpointSelector
  ↓
Retry / CircuitBreaker / Timeout
  ↓
UpstreamClient
  ↓
Backend
  ↓
Upstream FilterChain
  ↓
Client
```
