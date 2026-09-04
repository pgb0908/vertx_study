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

## 진행 중: Day 10 — Vert.x를 네트워크 런타임으로 격리 (`day9/Arch.md` 적용)

grill-me 세션에서 범위를 합의: day9는 레퍼런스로 남기고, `day10` 패키지에
`domain`/`engine`/`runtime.vertx` 계층으로 처음부터 새로 만든다. 목표는 기능
이식이 아니라 "GatewayEngine이 Vert.x 없이 독립적으로 동작·테스트되는가"를
검증하는 것.

### v1 완료

- `domain/` — `GatewayBody`(`java.util.concurrent.Flow.Publisher<byte[]>` 기반,
  backpressure 포함), `GatewayRequest`/`GatewayResponse`/`GatewayExchange`,
  `GatewayRoute`, `Endpoint`, `UpstreamClient`, `GatewayPolicy` — Vert.x import 0개
- `engine/GatewayEngine.java` — before(순서대로) → upstream → after(역순)
  파이프라인, `CompletableFuture` 기반(Arch.md 예시의 `io.vertx.core.Future` 대신 —
  그러면 원칙 1을 문서 자신이 어기게 되어서)
- `policy/LoggingPolicy.java` — 첫 `GatewayPolicy` 구현체
- `runtime/vertx/` — `VertxReadStreamPublisher`/`VertxWriteStreamSubscriber`
  (Vert.x 4.5.10에는 `ReadStream↔Flow.Publisher` 내장 변환이 없어서, `fetch(n)`/
  `writeQueueFull()`/`drainHandler()`로 직접 backpressure 브리지를 구현),
  `VertxRequestAdapter`, `VertxResponseWriter`, `VertxUpstreamClient`,
  `VertxGatewayServer`
- 검증: `GatewayEngineTest`(순수 JUnit5, Vertx 인스턴스 없이 정책 순서/에러 전파
  확인) + 실제 기동 후 curl 3종(정상 요청, 404, 5MB 스트리밍 POST) 통과 —
  `day10/TESTING.md` 참고
- `[server]`/`[engine]`/`[upstream]`/`[response-writer]` 단계별 로그를 넣어서,
  요청 하나가 계층을 어떻게 통과하는지(어디서 매칭 실패로 끝나는지, before/after
  정책이 언제 도는지) 콘솔에서 그대로 추적 가능

의도적으로 이번 단계에서 만들지 않은 것: CircuitBreaker, 다중 라우트용
RouteMatcher, LoadBalancer, JWT/RateLimit policy, config 로딩/hot-reload, TLS.
v1에서 안 쓰는 추상화를 미리 깔지 않기 위한 선택 (아래 2차 목록).

### 다음 할 일 (2차)

grill-me에서 이미 방향까지 합의된 것부터 순서대로:

1. **CircuitBreaker** — domain에 순수 Java 인터페이스, `runtime.vertx`에서
   `io.vertx.circuitbreaker.CircuitBreaker`를 래핑하는 구현체. 재발명하지 않음.
2. **다중 라우트 + RouteMatcher** — 라우트가 2개 이상이 되는 시점에 Vert.x
   `Router`로 매칭을 위임 (day9 `GatewayRouterBuilder`와 같은 이유: 매칭 로직
   재구현은 실제 배포 동작과 괴리될 위험). 각 `route.handler()`가 이미 알고
   있는 `GatewayRoute`를 그대로 engine에 넘기는 방식이라 `VertxGatewayServer`의
   `handle()` 아래 절반은 거의 안 바뀜.
3. **LoadBalancer** — `EgressGroup` 내 라운드로빈 등, domain 인터페이스로.
4. **JWT / RateLimit policy** — day9의 `AuthJwtFilter`/`RateLimitFilter`를
   `GatewayPolicy`로 재구현 (Vert.x `RoutingContext` 대신 `GatewayExchange` 기반).
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
