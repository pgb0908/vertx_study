# Vert.x 게이트웨이 학습 로드맵

Nginx/Kong을 대체할 사내 게이트웨이 프로토타입을 목표로, Vert.x 4.x의 핵심
패턴만 골라 실습 중심으로 익힌다. 리액티브 경험(RxJava/WebFlux)은 있으므로
개념 재설명 대신 Vert.x 고유의 실행 모델과 게이트웨이 아키텍처에 시간을
집중한다.

- **Vert.x**: 4.5.10
- **빌드**: Gradle (`./gradlew runDayN`으로 day별 실행)
- **배포 가정**: 단일 인스턴스
- **설정**: 파일 기반 + 파일시스템 watch로 무중단 반영 (day1~9)
- **프로토콜**: HTTP/1.1 + HTTPS(TLS 종단)

## 학습 범위

### 포함
- 필터 체인 아키텍처 (on/off 가능한 모듈형 필터)
- 경로 기반 라우팅 + 라운드로빈 로드밸런싱
- 파일 기반 라우팅/업스트림 설정 + 무중단 hot-reload
- JWT 인증/인가 필터
- 레이트리미팅 필터
- 회로차단기 · 타임아웃 · 재시도
- TLS 종단 (HTTPS)
- **Vert.x를 네트워크 런타임으로 격리하는 아키텍처 재설계** (day10~, `day9/Arch.md` 참고)

### 다음 단계로 미룸
- 관측성 — 로깅 / 메트릭 / 트레이싱
- WebSocket / gRPC 프록시
- 클러스터링 / 멀티 인스턴스 설정 동기화
- 동적 플러그인(스크립트/클래스로더) 로딩

## 완료: Day 1–9 (Vert.x Handler Chain 기반 프로토타입)

당초 계획한 10일 로드맵 중 필터 체인/라우팅/hot-reload/인증/레이트리밋/
회로차단기/TLS는 계획보다 하루 당겨져 day8까지 전부 끝났고, day9에서
필터 인터페이스를 Envoy 스타일(`decodeHeaders`/`encodeHeaders` 분리)로
한 번 더 다듬었다. 각 day 패키지의 `TESTING.md`가 실제 curl 검증 시나리오다.

| Day | 결과물 | 핵심 개념 |
|---|---|---|
| 1 | Router 기반 최소 HTTP 서버 (`day1/`) | Verticle, Event Loop, Future/Promise 합성 |
| 2 | 필터 체인 뼈대 (`day2/filter/`) | Handler 체이닝, 필터 레지스트리 |
| 3 | 리버스 프록시 코어 (`day3/proxy/`) | 요청/응답 바디 스트리밍(pipe), hop-by-hop 헤더 제거 |
| 4 | 파일 설정 + 무중단 hot-reload (`day4/reload/ConfigWatcher.java`) | WatchService, 원자적 설정 스왑 |
| 5 | JWT 인증 필터 (`day5/JwtConfig.java`) | `vertx-auth-jwt`, 401 short-circuit |
| 6 | 레이트리밋 필터 (`day6/ratelimit/RateLimiter.java`) | 토큰 버킷, 429 + Retry-After |
| 7 | 회로차단기·타임아웃·재시도 (`day7/`) | `vertx-circuit-breaker`, 멱등 요청 한정 재시도 |
| 8 | TLS 종단 (`day8/MainVerticle.java`) | `HttpServerOptions.setSsl`, 자체 서명 인증서 |
| 9 | Envoy 스타일 `GatewayFilter` 재설계 (`day9/filter/GatewayFilter.java`) | request/response 필터 분리, `RetryPolicy` 순수 함수 추출, **`Arch.md` 작성** — 다음 단계의 설계 문서 |

day9까지의 구조적 한계 (day9 `Arch.md` 1~3절에서 상술): 필터가 `RoutingContext`를
직접 받고, `ProxyHandlerFactory`가 `Handler<RoutingContext>`를 리턴한다 — Gateway의
실행 흐름 전체가 Vert.x Router의 handler 등록 순서에 암묵적으로 의존한다.

## 진행 중: Day 10 — Vert.x를 네트워크 런타임으로 격리 (`Arch.md` 적용)

grill-me 세션에서 범위를 합의: day9는 레퍼런스로 남기고, `day10` 패키지에
`domain`/`engine`/`runtime.vertx` 계층으로 처음부터 새로 만든다. 목표는 기능
이식이 아니라 "GatewayEngine이 Vert.x 없이 독립적으로 동작·테스트되는가"를
검증하는 것. `Arch.md`는 이후 `day10` 패키지로 옮겨졌다(레퍼런스 문서가 실제
적용 대상과 같은 패키지에 있는 게 자연스러워서).

### v1 (PoC)

- `domain/` — `GatewayBody`(`Flow.Publisher<byte[]>` 기반, backpressure 포함),
  `GatewayRequest`/`GatewayResponse`/`GatewayExchange`, `GatewayRoute`,
  `Endpoint`, `UpstreamClient`, `GatewayPolicy` — Vert.x import 0개
- `engine/GatewayEngine.java` — before(순서대로) → upstream → after(역순)
  파이프라인, `CompletableFuture` 기반
- `runtime/vertx/` — `VertxReadStreamPublisher`/`VertxWriteStreamSubscriber`
  (Vert.x 4.5.10에는 `ReadStream↔Flow.Publisher` 내장 변환이 없어서 `fetch(n)`/
  `writeQueueFull()`/`drainHandler()`로 직접 backpressure 브리지를 구현)
- 검증: `GatewayEngineTest`(순수 JUnit5) + curl 3종(정상 요청, 404, 5MB 스트리밍)

### 실무 구조 반영 (`gateway_day10_feedback_apply.md` 적용 — proxygen 스타일)

PoC를 넘어 실무에서 쓸 수 있는 구조로 한 단계 더 진행했다. `GatewayPolicy`(before
반환이 `Void`라 요청을 변형할 방법이 없었음)를 Proxygen Filter 스타일의
`GatewayFilter`(`onRequest`가 `FilterResult.Next(request)`/`Abort(response)`를
반환)로 교체하면서 시작해, `gateway_day10_feedback_apply.md` 9절 "우선 적용
순서" 10개 항목을 전부 반영했다:

- **FilterChain 분리** — `Filter`는 자신의 동작(`onRequest`/`onResponse`/
  `onRequestBody`/`onResponseBody`)만 알고, 0→N/N→0 순회는 `engine/FilterChain`이
  전담한다. `Filter`끼리 서로를 아는 `upstream_`/`downstream_` 포인터는 제거했다
  (feedback 3절이 명시적으로 "지양"하는 패턴이었음).
- **GatewayExchange 4단계 구분** — `originalRequest`(불변)/`request`(현재)/
  `upstreamResponse`(backend 원본)/`response`(필터 체인을 거친 최종)로 나눴다.
  이전에는 `response()`가 1회만 세팅되고 아무도 안 읽는 죽은 상태였는데, 이
  구분으로 각 필드가 실제로 다른 시점의 값을 정확히 대표하게 됐다.
- **Route Match가 GatewayEngine의 첫 스텝** — 이전에는 `VertxGatewayServer`가
  경로 문자열 비교로 404를 직접 처리했는데, 이제 `GatewayEngine.execute()`가
  `RouteTable.match()`로 라우트를 찾고 없으면 404를 반환한다 — 매칭 결과에 따라
  달라지는 동작(어떤 필터/업스트림을 쓸지)이 전부 engine 소유가 됐다.
- **라우트별 FilterChain** — `GatewayRoute`(config)가 `List<Filter>`를 갖고,
  `RuntimeRoute`(컴파일된 실행 형태)가 이를 `FilterChain`으로 컴파일한다. 이전
  처럼 `GatewayEngine` 하나에 전역 필터 목록을 고정하지 않는다.
- **EndpointSelector / EgressGroup / LoadBalancer** — `GatewayRoute`가 Endpoint
  하나 대신 `EgressGroup`(Endpoint 목록)을 갖고, `LoadBalancer` 인터페이스 +
  `RoundRobinLoadBalancer` 구현체로 실제 여러 Endpoint 중 하나를 고른다. 라운드
  로빈 카운터는 config 객체(`EgressGroup`)가 아니라 구현체가 들고 있다 —
  config와 runtime state를 분리하기 위해서(feedback 12절).
- **UpstreamExecutor + Retry/CircuitBreaker/Timeout 인터페이스** — 재시도/회로
  차단/타임아웃을 Filter가 아니라 업스트림 호출을 감싸는 별도 execution
  policy로 분리했다. 이 시점엔 `RetryPolicy.none()`/`CircuitBreaker.disabled()`/
  `TimeoutPolicy.none()` no-op 구현만 있었다 — 실제 Vert.x 래핑 구현체는 바로
  다음 절("실무 관점 상위 6개 보완")에서 채웠다.
- **RuntimeSnapshot / RouteTable** — 요청 처리 시점에는 이미 컴파일된
  `RuntimeSnapshot`만 참조한다. v1은 부팅 시 한 번만 만들고 교체하지 않는다 —
  config 파일 로딩 + 원자적 스왑은 2차 항목 5.
- 검증: `GatewayEngineTest`에 route-match/404 케이스 추가, curl 3종 재확인
  (`day10/TESTING.md`), `[server]`→`[engine]`→`[filter-chain]`→`[upstream]`
  순서의 rid 기반 추적 로그 갱신.

**리뷰로 잡은 버그**: `FilterChainBodySubscriber`가 upstream `Subscription`을
sink에 그대로 넘기고(`sink.onSubscribe(s)`) `onNext()`에서 `applyChain(chunk)`를
기다리지 않고 반환하고 있었다 — chunk별 필터 처리 시간이 다르면(A=30ms,
B=5ms, C=10ms) 처리 순서가 완료 순서로 뒤바뀌어 HTTP body 청크가 재정렬될 수
있는 구조였다. `FilterChainBodySubscriber`가 upstream 요청을 직접 소유해서
한 번에 최대 1개 chunk만 요청·처리·전달하도록 고쳤다. 고치는 과정에서 두
번째 버그(빈 바디 요청에서 `onComplete`가 영원히 전달되지 않는 hang)를
만들었다가 실제 curl로 재현·수정했다 — "처리 중인 chunk가 있을 때만
onComplete를 미룬다"는 상태 구분(`AWAITING_UPSTREAM` vs `PROCESSING`)이
빠져 있었던 것. `FilterChainBodySubscriberTest`에 순서 보장 + 빈 바디 회귀
테스트를 추가했다.

### 실무 관점 상위 6개 보완 (코드 리뷰 → 우선순위 1~6번 적용)

day10 코드 리뷰에서 "실무 관점으로 부족한 것"을 11개 순위로 정리했고, 1~6순위를
적용했다. 조사해보니 상당수가 day7/8/9(`ProxyHandlerFactory`/`proxy.RetryPolicy`/
`proxy.HopByHopHeaders`/`GatewayRouteResolver`/`Main.java`)에서 이미 검증된
패턴이었다 — 새로 설계하지 않고 day10의 계층 구조(domain 인터페이스 + runtime.vertx
구현체)에 맞게 이식했다.

- **Timeout + CircuitBreaker (`VertxCircuitBreaker`)** — `io.vertx.circuitbreaker.
  CircuitBreaker`를 래핑. `CircuitBreakerOptions.setTimeout`이 곧 타임아웃 구현이라
  둘이 한 컴포넌트로 해결된다. 5xx도 실패로 취급(day9와 동일 이유 — 안 그러면
  회로가 절대 안 열림).
- **Retry (`VertxRetryPolicy`)** — 멱등 메서드(GET/HEAD/OPTIONS)만 재시도 예산을
  받고, 재시도 대상은 처음부터 `GatewayBody.EMPTY`로 보낸다(day10의 요청 바디는
  `Flow.Publisher`라 한 번만 구독 가능 — `VertxReadStreamPublisher`가 두 번째
  구독을 거부하는 걸 확인함). `OpenCircuitException`이면 예산이 남아도 중단.
- **합성 순서 정정** — `UpstreamExecutor`는 CircuitBreaker가 가장 바깥인 줄
  알았는데, day9 `ProxyHandlerFactory.attempt()`를 보니 실제로는 **Retry가
  가장 바깥(루프)이고 매 시도마다 CircuitBreaker를 다시 태운다** — 안 그러면
  재시도 루프 전체가 breaker 입장에서 "시도 1번"으로만 집계된다. 순서를
  Retry→CircuitBreaker→Timeout(no-op)→client로 고쳤다.
- **Hop-by-hop 헤더 + X-Forwarded-\*/Via (`domain.model.HopByHopHeaders`)** —
  day9 `proxy.HopByHopHeaders`의 고정 목록을 이식하고, `Connection` 헤더 값이
  동적으로 추가 지정하는 헤더 이름까지 반영(day9엔 없던 부분). `X-Forwarded-For`
  (체인이면 콤마로 append)/`-Proto`/`-Host`는 `VertxRequestAdapter`가, `Via`는
  `VertxUpstreamClient`가 추가 — 둘 다 day9에도 없던 신규 기능.
- **에러 응답 정제 (`VertxGatewayServer.respondError`)** — `err.getMessage()`를
  클라이언트에 그대로 노출하던 걸 없애고, day9 `respondError`와 동일한 매핑
  (`OpenCircuitException`→503+`Retry-After`, `TimeoutException`→504, 그 외→502,
  본문은 고정 문구만)을 적용. 상세는 로그에만 남긴다.
- **`GatewayHeaders` 완전 불변화** — record 안에 있으면서도 `add()`가 자기 자신을
  변경하던 mutable 클래스였다(record는 필드 참조만 불변으로 만들지, 그 참조가
  가리키는 객체 내부까지 불변으로 만들진 않는다는 함정). `withAdded()`(단건 추가,
  새 인스턴스)와 `Builder`(여러 건 조립용)로 재작성. mutable이던 지점은 정확히
  2곳(`VertxRequestAdapter`, `VertxUpstreamClient`의 응답 헤더 빌드)뿐이었다.
- **Graceful shutdown** — 처음엔 day9 `Main.java`와 똑같이 `vertx.close()`만
  걸었는데, **실제로 curl로 검증해보니 in-flight 요청이 응답 없이 그냥 끊겼다**
  (`HttpServer.close()`의 Vert.x javadoc이 "Any open HTTP connections will be
  closed"라고 명시 — 확인함). day9는 애초에 "이건 시작점일 뿐" 이라고 정직하게
  범위를 한정했었는데, 사용자가 원래 지적한 문제("처리 중이던 요청이 그냥
  끊깁니다")를 실제로 고치려면 진짜 draining이 필요했다. `VertxGatewayServer`에
  in-flight 카운터를 추가하고, `stop(drainTimeoutMs)`가 **카운트가 0이 될 때까지
  먼저 기다린 뒤에** `HttpServer.close()`를 부르도록 순서를 뒤집었다(먼저 닫으면
  draining이 무의미해짐 — 실제로 겪고 고침). SIGTERM 중 2초 지연 응답을 기다리는
  요청이 끝까지 200으로 완료되는 걸 curl로 확인했다. 다만 draining 도중 새 연결을
  거부하지는 못한다(Vert.x에 그런 API가 없음) — 실제 배포에선 로드밸런서가 먼저
  라우팅을 끊어준다는 전제.
- **검증 중 발견해서 같이 고친 버그 2개**:
  1. `RouteTable.match()`가 쿼리스트링까지 포함한 전체 URI로 라우트를 비교하고
     있었다 — `?delayMs=1000` 같은 쿼리파라미터가 붙으면 무조건 404. 매칭 전에
     `?` 이후를 잘라내도록 고침(`GatewayEngineTest`에 회귀 테스트 추가).
  2. `timeoutMs`를 day7/8/9와 같은 500ms로 재사용하려 했다가, **5MB 업로드가
     정상적으로도 약 1.2초 걸린다는 걸 실측**하고 타임아웃과 충돌한다는 걸
     발견했다 — day9는 resilience 라우트에서 요청 바디를 항상 버퍼링/비움
     처리해서 이 문제가 없었지만, day10은 항상 스트리밍이라 그대로 재사용하면
     안 됐다. `timeoutMs=5000`으로 올려서 대용량 업로드는 통과시키면서 진짜
     멈춘 백엔드는 여전히 잡아내도록 조정.
- 신규 테스트: `VertxRetryPolicyTest`(순수 JUnit, 멱등/비멱등/OpenCircuitException
  중단 검증), `VertxCircuitBreakerTest`(실제 `Vertx.vertx()` 인스턴스로 open/
  timeout/5xx-as-failure 검증 — 소켓 통신 없이 가능해서 curl 대신 JUnit으로 커버).
  `day10/TESTING.md`에 회로차단기/타임아웃/재시도/에러정제/hop-by-hop/graceful
  shutdown curl 시나리오 전부 추가.

### 파일 기반 config 로딩 (`doc/*.md` 스펙 적용)

`day10/doc/`에 Listener/Connector/Router/Policy_auth_apikey 4개 리소스 스펙이
K8s CRD 스타일(`apiVersion`/`kind`/`metadata`/`spec`, 리소스 간 `*Ref`로 상호
참조)로 정의돼 있었고, 이번에 그 중 **Listener + Connector + Router**를 실제로
읽어서 부팅하도록 만들었다(Policy_auth_apikey는 다음 단계 — 실제 ApiKey 인증
Filter가 아직 없어서). `config` 신규 패키지(`ConfigLoader`/`RuntimeSnapshotCompiler`
/ 리소스별 record)가 Arch.md 12/13절의 "Config World → compile → Runtime World"
경계를 구현한다.

- **파일 레이아웃**: 리소스당 파일 1개, kind별 디렉토리(`config/day10/listeners/`,
  `connectors/`, `routers/`) — K8s manifest 관례. `-Dgateway.configDir`로 위치
  변경 가능(기본 `config/day10`). hot-reload는 없음 — 부팅 시 1회만 읽는다.
- **Router → 여러 Connector 가중치 분산** (Router.md 예시1: 90/10) — Connector
  내부 endpoint 선택(`LoadBalancer`)과는 별개의 레이어라서, `engine.RuntimeConnector`
  (Connector 하나 = endpoint pool + 그 Connector 전용 Retry/CircuitBreaker/Timeout)
  와 `engine.ConnectorSelector`(여러 Connector 중 가중치로 하나 선택, 정수 누적
  방식)를 새로 도입했다. 이 때문에 `UpstreamExecutor`가 `GatewayEngine`이 아니라
  **Connector 단위**로 옮겨갔다(Connector마다 resilience 설정이 다르므로) —
  `RuntimeRoute`도 `EndpointSelector` 대신 `ConnectorSelector`를 들고 `GatewayRoute`
  에서 `EgressGroup` 필드 자체를 뺐다(하나의 Route가 여러 Connector를 가리킬 수
  있어서 "Route 하나 = 목적지 하나" 가정이 깨짐).
- **`Connector.spec.method`/`proxyPath` = "해석 B"(고정 API 호출 템플릿)** — grill
  형태로 사용자와 확인: 클라이언트가 실제로 보낸 method/path와 무관하게, 그
  Connector로 라우팅되면 항상 spec.method + spec.proxyPath로 백엔드를 호출한다
  (투명 프록시가 아니라 "미리 정의된 API를 대신 호출해주는" 게이트웨이 모델).
  클라이언트 쿼리스트링은 유지해서 proxyPath 뒤에 그대로 붙인다(`RuntimeConnector`).
- **실제로 겪은 버그**: Vert.x `HttpClient`에 method=GET으로 청크 바디를 스트리밍
  했더니 응답이 수 ms 만에 와버리고 클라이언트 업로드가 전혀 진행되지 않았다
  (`uploaded=0`). `RuntimeConnector`가 고정 method GET/HEAD일 때 원본 바디를
  무조건 `GatewayBody.EMPTY`로 바꿔치기하도록 방어 처리해서 해결(day9
  `VertxRetryPolicy`가 멱등 메서드에 항상 빈 바디를 쓰는 것과 같은 패턴).
- **재시도 예산도 "실제로 백엔드에 나가는 method" 기준으로 재해석됨** — 부수
  효과로, `VertxRetryPolicy`가 보는 method는 이제 클라이언트 원본이 아니라
  `RuntimeConnector`가 고정한 method다. Connector의 method가 POST(비멱등)면
  클라이언트가 GET을 보냈어도 재시도가 안 된다 — 재시도 안전성은 실제로 재전송될
  요청이 무엇인지에 달려 있으므로 오히려 더 정확한 동작.
- **검증 범위 결정**(전부 사용자와 확인): RouteMatcher는 여전히 path+method 정확
  일치만, resilience 필드는 richer한 스펙(retryOn/backoff/perTryTimeout 등)을
  기존 단순 구현(maxFailures/timeoutMs/resetTimeoutMs/maxRetries)에 매핑만 하고
  반영 안 함, LoadBalancer는 ROUND_ROBIN만 실제 구현(다른 값이면 부팅 실패),
  healthCheck/바디 크기 제한/TLS는 파싱조차 안 함, Listener는 1개만 지원.
  전부 스펙과 다르면 조용히 폴백하지 않고 `GatewayConfigException`으로 부팅을
  막는다.
- 신규 파일: `config/{ListenerConfig,ConnectorConfig,ConnectorTarget,
  LoadBalancingConfig,ResilienceSettings,RouterConfig,RouterDestination,
  GatewayConfig,GatewayConfigException,ConfigLoader,RuntimeSnapshotCompiler}`,
  `engine/{RuntimeConnector,ConnectorSelector}`(신규), `engine/EndpointSelector`
  삭제(RuntimeConnector로 흡수).
- 신규 테스트: `ConfigLoaderTest`(실제 `config/day10` 예시 로딩+컴파일, 스펙 위반
  케이스별 거부 확인), `RuntimeConnectorTest`(고정 method/proxyPath 재작성,
  쿼리스트링 유지, GET/HEAD 빈 바디 방어), `ConnectorSelectorTest`(단일 목적지/
  가중치 분산 확률적 검증). `Main.java`가 `config/day10/*`을 읽어 기존 `/echo`
  curl 시나리오(GET+POST 5MB 스트리밍 포함)가 전부 그대로 통과하는 걸 재확인
  (`day10/TESTING.md`).

### 다음 할 일 (2차, 남은 것)

1. **다중 라우트 prefix/wildcard 매칭** — `RouteTable`은 지금 path+method 정확
   일치만 지원. Router가 많아지고 prefix 매칭이 필요해지면 Vert.x `Router`로
   매칭을 위임 (day9 `GatewayRouterBuilder`와 같은 이유: 매칭 로직 재구현은
   실제 배포 동작과 괴리될 위험).
2. **JWT / RateLimit Filter + Policy 리소스 연동** — day9의 `AuthJwtFilter`/
   `RateLimitFilter`를 `Filter`로 재구현하고, `Policy_auth_apikey` 등 Policy
   리소스를 파싱해서 `RuntimeSnapshotCompiler`가 해당 Router에 실제로 붙이도록
   연결 (지금은 모든 Router에 `LoggingFilter`만 기본으로 붙음).
3. **Config hot-reload** — day4~9의 `ConfigWatcher` 패턴으로 파일 변경 감지 +
   `RuntimeSnapshot` 원자적 스왑(Arch.md 13절). 지금은 부팅 시 1회만 읽음.
4. **TLS** — `runtime.vertx`의 `HttpServerOptions.setSsl` 적용, day8과 동일한
   자체 서명 인증서 재사용. `Listener.spec.tls`/`Connector.spec.upstreamTls`도
   이때 같이 파싱하도록.
5. **HttpClient 커넥션 옵션** — `vertx.createHttpClient()`가 기본 옵션 그대로다
   (풀 크기/idle timeout/connect timeout 미설정).
6. **관측성(메트릭)** — 로그만 있고 Micrometer/Prometheus 연동 없음.
7. **richer resilience 매핑** — `retry.retryOn`/`retryBackoff`/`perTryTimeout`,
   `timeout.connect`/`timeout.send`를 실제로 반영하려면 `VertxRetryPolicy`/
   `VertxCircuitBreaker` 자체를 확장해야 함(지금은 단순 매핑만).
8. **LoadBalancer 알고리즘 추가** — LEAST_CONN/IP_HASH/RANDOM (지금은 ROUND_ROBIN만).
9. **healthCheck 능동 헬스체크, maxRequestBodySize/maxResponseBodySize 강제,
   다중 Listener 지원** — 전부 파싱조차 안 하거나(healthCheck/바디크기) 1개로
   제한(Listener)된 상태.

각 항목이 끝날 때마다 `day10/TESTING.md`에 시나리오를 추가하고, engine 쪽
로직이 늘어나면 `GatewayEngineTest`에 순수 unit test를 먼저 추가한다
(runtime-vertx는 curl 통합 테스트, domain/engine은 JUnit5 — grill-me에서 합의한
테스트 전략).

## 학습 중 유의할 결정 사항

- 필터는 클래스로더/스크립트로 동적 로딩하지 않는다 — 미리 배포된 필터를
  **설정으로 on/off**하는 것으로 충분하다는 합의.
- 단일 인스턴스 가정이므로 레이트리밋 상태를 로컬 메모리에 두어도 무방하다.
  다중 인스턴스로 갈 때는 이 부분을 Redis 등 공유 저장소로 옮겨야 한다.
- 파일 기반 설정은 인스턴스가 하나일 때만 단순하다 — 나중에 여러 인스턴스로
  확장하면 설정 배포 방식(공유 스토리지/외부 설정 서버)을 다시 논의해야 한다.
- **day10 이후**: domain/engine/runtime 경계는 Gradle 서브프로젝트가 아니라
  패키지 분리로 강제한다 (지금은 학습용 단일 모듈 프로젝트라서 — 코드리뷰로
  원칙을 지키다가, 필요해지면 멀티모듈로 승격).
- **day10 이후**: `GatewayBody`는 처음부터 완전한 `Flow.Publisher` 기반
  backpressure로 구현했다 — 나중에 버퍼링에서 스트리밍으로 바꾸는 두 번째
  마이그레이션(모든 Policy/Engine 시그니처 재작업)을 피하기 위한 선택.
