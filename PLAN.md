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
  policy로 분리했다. v1은 `RetryPolicy.none()`/`CircuitBreaker.disabled()`/
  `TimeoutPolicy.none()` no-op 구현만 있다 — 실제 Vert.x 래핑 구현체는 2차 항목 1.
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

### 다음 할 일 (2차)

1. **CircuitBreaker 실제 구현** — `domain.upstream.CircuitBreaker` 인터페이스는
   이미 있음. `runtime.vertx`에서 `io.vertx.circuitbreaker.CircuitBreaker`를
   래핑하는 구현체를 만들고 curl로 open/half-open 동작 검증.
2. **다중 라우트 + RouteMatcher** — `RouteTable`은 지금 정확 일치만 지원.
   라우트가 2개 이상이 되면 Vert.x `Router`로 매칭을 위임 (day9
   `GatewayRouterBuilder`와 같은 이유: 매칭 로직 재구현은 실제 배포 동작과
   괴리될 위험).
3. **Retry/Timeout 실제 구현** — `RetryPolicy`/`TimeoutPolicy`도 CircuitBreaker와
   같은 패턴(interface는 domain, 구현은 runtime.vertx 또는 순수 Java 타이머).
   재시도는 body replay 가능 여부(feedback 6절 — streaming body는 replay 불가)를
   먼저 판단해야 함.
4. **JWT / RateLimit Filter** — day9의 `AuthJwtFilter`/`RateLimitFilter`를
   `Filter`로 재구현 (Vert.x `RoutingContext` 대신 `GatewayExchange` 기반).
5. **Config 로딩 + hot-reload** — day4~9의 `ConfigWatcher`/`GatewayConfig` 패턴을
   `RuntimeSnapshot` 원자적 스왑(Arch.md 13절)으로 재적용.
6. **TLS** — `runtime.vertx`의 `HttpServerOptions.setSsl` 적용, day8과 동일한
   자체 서명 인증서 재사용.

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
