# Vert.x를 Gateway 아키텍처가 아닌 네트워크 런타임으로 격리하는 설계

## 1. 핵심 원칙

Vert.x를 이용해 API Gateway를 구현할 때 가장 중요한 설계 원칙은 다음과 같다.

> **Vert.x를 Gateway 자체의 아키텍처로 삼지 않고, 네트워크 I/O를 담당하는 런타임 구현체로 격리한다.**

즉, Gateway의 핵심 개념과 실행 흐름을 Vert.x의 `Router`, `RoutingContext`, `Handler`, `HttpClient`에 직접 의존시키지 않는다.

Gateway가 가져야 할 아키텍처는 별도로 정의하고, Vert.x는 그 아키텍처를 실제 HTTP 네트워크 I/O와 연결하는 Adapter/Runtime 역할만 수행하게 한다.

---

## 2. 왜 이 원칙이 필요한가

Vert.x는 다음과 같은 기능을 제공한다.

- 비동기 HTTP Server
- 비동기 HTTP Client
- Event Loop
- Router / Route
- Handler
- Streaming
- TCP / TLS
- WebSocket
- HTTP/1.1, HTTP/2

이 기능들은 API Gateway 구현에 매우 적합하다.

그러나 Vert.x가 제공하는 실행 모델을 그대로 Gateway의 실행 모델로 사용하기 시작하면 Gateway의 핵심 구조가 Vert.x 프레임워크에 종속된다.

예를 들어 다음과 같은 구조이다.

```text
Vert.x Router
    ↓
AuthenticationHandler
    ↓
RateLimitHandler
    ↓
RewriteHandler
    ↓
LoadBalanceHandler
    ↓
RetryHandler
    ↓
ProxyHandler
```

초기에는 단순하고 직관적이다.

그러나 기능이 증가하면 Gateway의 실행 의미 자체가 Vert.x Handler Chain에 의해 결정되기 시작한다.

```text
Gateway Architecture
        =
Vert.x Handler Architecture
```

이 상태에서는 Gateway가 독립적인 제품 아키텍처를 가지는 것이 아니라, Vert.x의 실행 모델 위에 기능을 계속 추가하는 형태가 된다.

---

## 3. Handler Chain 중심 구조의 문제

### 3.1 Gateway 실행 흐름이 프레임워크 내부에 숨는다

예를 들어 다음과 같이 구현할 수 있다.

```java
router.route()
    .handler(authenticationHandler)
    .handler(rateLimitHandler)
    .handler(routeHandler)
    .handler(rewriteHandler)
    .handler(loadBalanceHandler)
    .handler(retryHandler)
    .handler(proxyHandler);
```

이 경우 실제 Gateway 요청 처리 흐름은 Vert.x Router가 제어한다.

Gateway 관점에서 보면 다음과 같은 중요한 실행 의미가 명시적으로 존재하지 않는다.

```text
Route Match
    ↓
Request Policy
    ↓
Endpoint Selection
    ↓
Retry / Circuit Breaker
    ↓
Upstream Execution
    ↓
Response Policy
```

대신 이 의미가 여러 Handler의 순서와 `ctx.next()` 호출 관계에 분산된다.

결과적으로 Gateway의 동작을 이해하려면 Gateway 코드보다 Vert.x Handler Chain을 먼저 이해해야 한다.

---

### 3.2 RoutingContext가 사실상의 Gateway Context가 된다

Handler 기반 구조에서는 흔히 다음과 같은 코드가 증가한다.

```java
ctx.put("route", route);
ctx.put("endpoint", endpoint);
ctx.put("retryCount", retryCount);
ctx.put("traceId", traceId);
```

그리고 다른 Handler가 이를 꺼낸다.

```java
GatewayRoute route = ctx.get("route");
Endpoint endpoint = ctx.get("endpoint");
```

이 구조가 확장되면 `RoutingContext`가 사실상 Gateway의 거래 상태 저장소가 된다.

```text
RoutingContext
 ├─ request
 ├─ route
 ├─ endpoint
 ├─ retry state
 ├─ authentication
 ├─ rate-limit state
 ├─ tracing
 └─ custom attributes
```

문제는 `RoutingContext`가 Gateway 도메인 객체가 아니라 Vert.x 객체라는 점이다.

결국 Gateway Core 전체가 Vert.x에 종속된다.

---

### 3.3 Request와 Response 처리 모델이 비대칭이 되기 쉽다

Request Filter는 Handler Chain으로 자연스럽게 연결할 수 있다.

```text
Request
 ↓
Filter A
 ↓
Filter B
 ↓
Proxy
```

그러나 Proxy 이후의 Response Filter는 같은 방식으로 처리하기 어렵다.

```text
Proxy
 ↓
Backend Response
 ↓
Response Filter
 ↓
Client
```

결국 Proxy 내부에서 Response Filter를 별도로 호출하거나, Vert.x response hook을 추가하게 된다.

기능이 증가할수록 다음과 같이 실행 모델이 분산될 수 있다.

```text
Request 처리
    → Router Handler Chain

Response 처리
    → Proxy 내부 로직

Error 처리
    → Failure Handler

Retry
    → Proxy 내부

Circuit Breaker
    → 별도 callback
```

이 시점부터 Gateway의 전체 transaction 흐름을 하나의 코드 경로에서 파악하기 어려워진다.

---

## 4. 권장 구조

권장하는 기본 구조는 다음과 같다.

```text
Client
  │
  ▼
┌─────────────────────┐
│ VertxHttpListener   │
│                     │
│ HttpServer          │
│ Router              │
└──────────┬──────────┘
           │
           │ adapt
           ▼
┌─────────────────────┐
│ GatewayExchange     │
└──────────┬──────────┘
           │
           ▼
════════════════════════════════════
           Gateway Core
════════════════════════════════════
           │
           ▼
       RouteMatcher
           │
           ▼
    RequestPolicyChain
           │
           ▼
     EndpointSelector
           │
           ▼
      Retry / Circuit
           │
           ▼
    UpstreamExecutor
           │
           ▼
   ResponsePolicyChain
           │
════════════════════════════════════
           Vert.x Runtime
════════════════════════════════════
           │
           ▼
┌──────────────────────┐
│ VertxUpstreamClient  │
│ Vert.x HttpClient    │
└──────────┬───────────┘
           │
           ▼
        Backend
```

핵심은 다음 경계이다.

```text
Vert.x
  ↓
Adapter
  ↓
Gateway Core
```

Gateway Core가 Vert.x를 호출하는 구조가 아니라, Vert.x Runtime이 Gateway Core를 호출하는 구조로 만든다.

---

## 5. 의존성 방향

권장 의존성은 다음과 같다.

```text
gateway-domain
       ▲
       │
gateway-engine
       ▲
       │
gateway-runtime-vertx
```

즉,

```text
gateway-runtime-vertx
        ↓
gateway-engine
        ↓
gateway-domain
```

방향으로만 의존한다.

반대 방향은 허용하지 않는다.

```text
gateway-domain
        X
        ↓
io.vertx.*
```

Gateway Core 내부에서 다음 타입이 보이지 않는 것이 이상적이다.

```text
RoutingContext
HttpServerRequest
HttpServerResponse
HttpClientRequest
HttpClientResponse
Router
Route
```

---

## 6. Gateway Domain

Gateway 자체의 개념은 Vert.x와 무관하게 정의한다.

예를 들어 다음과 같다.

```text
GatewayRoute
Listener
EgressGroup
Endpoint
LoadBalancer
GatewayPolicy
RetryPolicy
TimeoutPolicy
GatewayRequest
GatewayResponse
GatewayExchange
```

예:

```java
public final class GatewayExchange {

    private final GatewayRequest request;

    private GatewayRoute route;
    private EgressGroup egressGroup;
    private Endpoint endpoint;

    private int retryCount;

    private final GatewayAttributes attributes;
}
```

이 객체는 Gateway에서 처리 중인 거래 한 건의 실행 상태를 표현한다.

중요한 것은 다음 관계이다.

```text
GatewayExchange
        ≠
RoutingContext
```

`GatewayExchange`는 Gateway가 소유하고, `RoutingContext`는 Vert.x Runtime이 소유한다.

---

## 7. Gateway Engine

Gateway 요청 한 건의 전체 실행 흐름은 명시적인 Engine이 소유해야 한다.

예:

```java
public class GatewayEngine {

    public Future<GatewayResponse> execute(
            GatewayExchange exchange) {

        return routeMatcher.match(exchange)
            .compose(route -> executeRequestPolicies(exchange, route))
            .compose(v -> selectEndpoint(exchange))
            .compose(v -> upstreamExecutor.execute(exchange))
            .compose(response -> executeResponsePolicies(exchange, response));
    }
}
```

이 구조의 가장 큰 장점은 코드에서 Gateway 실행 의미가 그대로 보인다는 점이다.

```text
GatewayEngine.execute()

Route Match
    ↓
Request Policy
    ↓
Endpoint Selection
    ↓
Upstream Execution
    ↓
Response Policy
```

Gateway의 동작을 이해하기 위해 Vert.x Router의 Handler 등록 순서를 추적할 필요가 없다.

---

## 8. Vert.x Router의 역할

Router는 최대한 얇게 유지한다.

권장 형태:

```java
Router router = Router.router(vertx);

router.route().handler(this::handleRequest);
```

그리고 Adapter 역할만 수행한다.

```java
private void handleRequest(RoutingContext ctx) {

    GatewayExchange exchange =
        requestAdapter.adapt(ctx);

    gatewayEngine.execute(exchange)
        .onSuccess(response ->
            responseWriter.write(ctx, response)
        )
        .onFailure(error ->
            errorWriter.write(ctx, error)
        );
}
```

따라서 Router의 역할은 다음과 같다.

```text
HTTP Request
    ↓
RoutingContext
    ↓
GatewayExchange 변환
    ↓
GatewayEngine 호출
```

Gateway 기능을 Router에 구현하지 않는다.

---

## 9. Upstream Client 추상화

Gateway Core는 Vert.x `HttpClient`를 직접 사용하지 않는다.

Core에는 인터페이스만 존재한다.

```java
public interface UpstreamClient {

    Future<GatewayResponse> execute(
        Endpoint endpoint,
        GatewayRequest request
    );
}
```

Vert.x Runtime에서 이를 구현한다.

```java
public final class VertxUpstreamClient
        implements UpstreamClient {

    private final HttpClient client;

    @Override
    public Future<GatewayResponse> execute(
            Endpoint endpoint,
            GatewayRequest request) {

        // Vert.x HttpClient 사용
    }
}
```

구조:

```text
GatewayEngine
     │
     ▼
UpstreamClient
  interface
     ▲
     │
VertxUpstreamClient
     │
     ▼
Vert.x HttpClient
```

이렇게 하면 Gateway Core는 Vert.x HTTP Client 구현 세부사항을 알 필요가 없다.

---

## 10. Policy도 Vert.x Handler와 분리한다

다음 형태는 피하는 것이 좋다.

```java
public interface GatewayFilter {

    void filter(RoutingContext ctx);
}
```

이미 Gateway Filter가 Vert.x에 종속되기 때문이다.

대신 다음과 같이 정의한다.

```java
public interface GatewayPolicy {

    Future<Void> before(
        GatewayExchange exchange
    );

    Future<GatewayResponse> after(
        GatewayExchange exchange,
        GatewayResponse response
    );
}
```

구현체:

```text
ApiKeyPolicy
OAuthPolicy
RateLimitPolicy
CorsPolicy
RequestTransformPolicy
TimeoutPolicy
RetryPolicy
AccessLogPolicy
```

이들은 Vert.x가 아니라 Gateway의 실행 모델에 속한다.

---

## 11. Load Balancer 역시 Gateway Domain에 둔다

Vert.x의 네트워크 기능과 Endpoint 선택 정책은 별개의 문제이다.

```java
public interface LoadBalancer {

    Endpoint select(
        EgressGroup group,
        GatewayExchange exchange
    );
}
```

구현 예:

```text
RoundRobinLoadBalancer
WeightedRoundRobinLoadBalancer
LeastConnectionLoadBalancer
ConsistentHashLoadBalancer
RandomLoadBalancer
```

Vert.x는 선택된 Endpoint로 실제 HTTP 요청을 보내는 역할만 담당한다.

```text
LoadBalancer
      ↓
Endpoint
      ↓
VertxUpstreamClient
      ↓
HTTP Connection
```

---

## 12. Config와 Runtime도 분리한다

Gateway 설정 객체와 실행 객체를 하나로 만들지 않는다.

예를 들어 다음 구조는 피한다.

```java
class Endpoint {

    String host;
    int port;

    AtomicInteger activeConnections;
    CircuitBreaker breaker;
    HttpClient client;
}
```

설정과 runtime state가 혼합되어 있기 때문이다.

대신 다음과 같이 분리한다.

```text
EndpointConfig
 ├─ host
 ├─ port
 └─ weight


EndpointRuntime
 ├─ EndpointConfig
 ├─ health
 ├─ activeConnections
 ├─ circuit state
 └─ metrics
```

전체적으로는:

```text
Configuration World

GatewayConfig
ListenerConfig
RouteConfig
PolicyConfig
EgressGroupConfig
EndpointConfig

          │
          │ compile
          ▼

Runtime World

RuntimeSnapshot
RouteTable
PolicyChain
EgressGroupRuntime
EndpointRuntime
```

로 구분한다.

---

## 13. RuntimeSnapshot

Config는 요청 처리 시점에 해석하지 않는다.

```text
JSON / YAML
     ↓
Parse
     ↓
Validation
     ↓
Compile
     ↓
RuntimeSnapshot
```

요청 처리 시에는 이미 만들어진 Runtime 객체만 사용한다.

```java
RuntimeSnapshot runtime = snapshot.get();
```

Config 변경 시 새로운 Snapshot을 완성한 후 원자적으로 교체한다.

```text
                    RuntimeSnapshot

                 ┌───────────────┐
old request ────►│ Snapshot v32  │
                 └───────────────┘

                       config reload

                 ┌───────────────┐
new request ────►│ Snapshot v33  │
                 └───────────────┘
```

이 방식은 요청 처리 중 설정이 변경되어 서로 다른 버전의 설정을 섞어 사용하는 문제를 방지한다.

---

## 14. Event Loop의 역할

Vert.x Event Loop에서는 가능한 한 다음 작업만 수행한다.

```text
socket read
    ↓
route lookup
    ↓
policy execution
    ↓
endpoint selection
    ↓
async upstream I/O
    ↓
socket write
```

다음과 같은 blocking 작업은 Event Loop에서 수행하지 않는다.

```text
JDBC blocking query
Thread.sleep()
blocking file I/O
blocking SDK
긴 synchronized section
CPU-intensive processing
대용량 변환 작업
```

Vert.x를 네트워크 Runtime으로 제한하면 이러한 Event Loop 원칙도 구조적으로 관리하기 쉬워진다.

---

## 15. 최종 모듈 구조 예시

```text
gateway/
│
├── gateway-domain
│   ├── route/
│   │   ├── GatewayRoute.java
│   │   ├── RouteMatcher.java
│   │   └── RouteTable.java
│   │
│   ├── upstream/
│   │   ├── EgressGroup.java
│   │   ├── Endpoint.java
│   │   ├── LoadBalancer.java
│   │   └── EndpointSelector.java
│   │
│   ├── policy/
│   │   ├── GatewayPolicy.java
│   │   ├── PolicyChain.java
│   │   ├── RetryPolicy.java
│   │   └── TimeoutPolicy.java
│   │
│   └── model/
│       ├── GatewayRequest.java
│       ├── GatewayResponse.java
│       └── GatewayExchange.java
│
├── gateway-engine
│   ├── GatewayEngine.java
│   ├── RequestProcessor.java
│   ├── UpstreamExecutor.java
│   └── ResponseProcessor.java
│
├── gateway-runtime-vertx
│   ├── VertxGatewayServer.java
│   ├── VertxListener.java
│   ├── VertxRequestAdapter.java
│   ├── VertxResponseWriter.java
│   ├── VertxUpstreamClient.java
│   └── VertxSslFactory.java
│
├── gateway-config
│   ├── GatewayConfig.java
│   ├── GatewayConfigLoader.java
│   ├── GatewayConfigValidator.java
│   ├── RuntimeSnapshot.java
│   └── RuntimeSnapshotBuilder.java
│
└── gateway-bootstrap
    └── GatewayApplication.java
```

---

## 16. Spring Cloud Gateway와의 근본적인 차이

이 설계 관점에서 중요한 차이는 프레임워크 자체가 아니라 **Gateway 실행 모델의 소유권**이다.

Spring Cloud Gateway는 기본적으로 다음 구조이다.

```text
Spring Cloud Gateway
       │
       ├─ Route
       ├─ Filter Chain
       ├─ Load Balancer
       ├─ Retry
       └─ Proxy Runtime

사용자 구현
       ↓
Spring Gateway 실행 모델 안에 삽입
```

반면 Vert.x를 Runtime으로만 사용할 경우:

```text
Our Gateway Architecture
       │
       ├─ Route
       ├─ Policy
       ├─ EgressGroup
       ├─ Endpoint
       ├─ LoadBalancer
       ├─ Retry
       └─ Execution Model

              ↓

         Vert.x Runtime
              │
              ├─ HTTP Server
              ├─ HTTP Client
              ├─ Event Loop
              └─ Network I/O
```

가 된다.

즉 Vert.x를 선택하는 핵심 목적은 단순히 Spring Cloud Gateway보다 빠르기 때문이 아니다.

> **Gateway의 실행 모델과 제품 아키텍처를 직접 소유하면서, Vert.x의 고성능 비동기 네트워크 기능만 활용하기 위해서이다.**

---

## 17. 설계 원칙 요약

### 원칙 1. Vert.x 타입을 Gateway Core로 침투시키지 않는다.

```text
RoutingContext
HttpServerRequest
HttpClientRequest
Router
Route
```

등은 `gateway-runtime-vertx` 내부에 한정한다.

### 원칙 2. Router를 Gateway 실행 엔진으로 사용하지 않는다.

Router는 HTTP 요청을 Gateway Engine에 연결하는 Adapter 역할만 수행한다.

### 원칙 3. Gateway는 자체 Execution Model을 가진다.

```text
Route
 → Request Policy
 → Endpoint Selection
 → Retry / Circuit
 → Upstream
 → Response Policy
```

의 흐름을 Gateway Engine에서 명시적으로 정의한다.

### 원칙 4. Gateway 거래 상태는 GatewayExchange가 가진다.

`RoutingContext`를 Gateway 거래 Context로 사용하지 않는다.

### 원칙 5. Config와 Runtime State를 분리한다.

```text
Config
 ↓
Compile
 ↓
RuntimeSnapshot
```

구조를 사용한다.

### 원칙 6. Vert.x는 Network Runtime 역할에 집중한다.

```text
HTTP Server
HTTP Client
Streaming
TLS
Event Loop
Socket I/O
```

등을 담당한다.

---

## 18. 결론

Vert.x 기반 API Gateway를 설계할 때 중요한 것은 **Vert.x를 얼마나 많이 사용하는가가 아니라, 어디까지 사용하도록 제한하는가**이다.

잘못된 방향은 다음과 같다.

```text
Gateway
   =
Vert.x Router
 + Handler Chain
 + RoutingContext
```

권장 방향은 다음과 같다.

```text
Gateway Architecture
      │
      ├─ Domain
      ├─ Engine
      ├─ Policy
      ├─ Routing
      ├─ Load Balancing
      └─ Runtime Model
              │
              ▼
       Vert.x Adapter
              │
              ▼
       Network Runtime
```

이 구조에서는 Vert.x가 Gateway를 지배하지 않는다.

Gateway가 자신의 도메인과 실행 모델을 소유하고, Vert.x는 이를 실제 네트워크에서 수행하는 고성능 Runtime으로 사용된다.

이를 통해 다음과 같은 장점을 얻을 수 있다.

- Gateway 실행 흐름의 명확성
- Vert.x 프레임워크 종속성 최소화
- 독립적인 Gateway Domain Model
- 기능 확장 용이성
- 테스트 용이성
- Runtime 교체 가능성
- Config / Runtime 분리
- Listener 및 Egress 구조의 자유로운 확장
- Streaming / Retry / Circuit Breaker semantics에 대한 직접적인 제어

따라서 제품 수준의 Gateway를 설계한다면 최종 목표는 다음과 같이 정리할 수 있다.

> **Vert.x 위에 Gateway를 만드는 것이 아니라, 독립적인 Gateway를 만들고 Vert.x를 그 Gateway의 네트워크 Runtime으로 사용한다.**