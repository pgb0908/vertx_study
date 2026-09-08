# day10 테스트 가이드

day9까지는 필터가 `RoutingContext`를 직접 받고 프록시가 `Handler<RoutingContext>`를
리턴하는, Vert.x Handler Chain에 종속된 구조였다. day10은 `Arch.md`가 제안한 대로
`domain`/`engine`/`runtime.vertx`로 계층을 나눈 버전이다 — `GatewayEngine`은
Vert.x를 전혀 몰라도 되고, `runtime.vertx` 패키지만 실제 소켓을 만진다.

`gateway_day10_feedback_apply.md` 피드백을 반영해 v1에서 한 단계 더 실무 구조로
갔다: Route Match/Endpoint Selection/Retry-CircuitBreaker-Timeout이 `GatewayEngine`
안에 명시적 스텝으로 존재하고, 라우트마다 독립된 `FilterChain`을 가지며,
`GatewayExchange`가 원본/현재 요청과 원본/최종 응답을 구분해서 들고 있다. Retry/
CircuitBreaker/Timeout은 자리(인터페이스 + no-op 구현)만 잡아뒀고 실제 Vert.x
래핑 구현체는 다음 단계다(아래 "다음에 할 일" 참고).

라우트는 여전히 하나(`/echo`), 필터도 하나(`LoggingFilter`)뿐이다. 실제 동작
검증은 `GatewayEngineTest`(순수 JUnit5, `./gradlew test`)가 route match/필터
순서/abort/에러 전파를 담당하고, 여기서는 그 위에 얹힌 `runtime.vertx` 계층
(실제 HTTP, 스트리밍 바디)이 진짜로 동작하는지 curl로 확인한다.

## 1. 사전 정리

```bash
fuser -k 8080/tcp 9001/tcp
```

## 2. 기동

```bash
./gradlew runDay10
```

다음 두 줄이 뜨면 정상:

```
Dummy upstream listening on port 9001
day10 gateway listening on port 8080
```

## 3. 정상 요청 — 계층을 통과하는 전체 흐름 확인

```bash
curl -s -i http://localhost:8080/echo
```

기대: `200`, `{"servedBy":"upstream-9001","path":"/echo"}`.

각 로그 줄이 어느 계층/스텝에서 찍히는지가 그대로 Arch.md의 실행 흐름이다 —
`[server]`(runtime.vertx 진입점, 어댑팅만) → `[engine]`(Route Match →
Downstream FilterChain → Endpoint Selection → Upstream) → `[filter-chain]`
(개별 Filter 호출) → `[upstream]`(runtime.vertx의 실제 HTTP 호출) → 다시
`[engine]`(Upstream FilterChain) → `[server]`/`[response-writer]`(출구).
`rid=`는 `GatewayExchange.requestId()` — 비동기 콜백이 스레드를 넘나들어도
요청 하나의 흐름을 로그에서 추적할 수 있게 해준다(라우트 매칭 전에는 아직
exchange가 없어서 `[server]`/최초 `[engine]` 로그에는 rid가 없다).

서버 콘솔에 다음 순서로 찍혀야 한다 (로그 레벨을 DEBUG로 보고 있다는 전제):

```
[server] <- GET /echo
[engine] rid=xxxxxxxx start GET /echo
[filter-chain] rid=xxxxxxxx onRequest[0] LoggingFilter
[request]  rid=xxxxxxxx GET /echo
[engine] rid=xxxxxxxx downstream-chain done -> upstream Endpoint[host=localhost, port=9001]
[upstream] connecting localhost:9001 GET /echo
[upstream] connected -> streaming request body
[upstream] response status=200
[engine] rid=xxxxxxxx upstream status=200 -> upstream-chain
[filter-chain] rid=xxxxxxxx onResponse[0] LoggingFilter
[response] rid=xxxxxxxx GET /echo -> 200
[engine] rid=xxxxxxxx complete status=200
[server] -> status=200
[response-writer] streaming body to client, status=200
[response-writer] response fully written to client
```

## 4. 미매칭 경로 — 404

```bash
curl -s -i http://localhost:8080/nope
```

기대: `404`, `no route matched`. 서버 콘솔에 `[server] <- GET /nope` →
`[engine] no route matches GET /nope` → `[server] -> status=404`.
`[filter-chain]`/`[upstream]` 로그는 전혀 안 찍혀야 한다 — Route Match는
`GatewayEngine`의 첫 스텝이라 engine 로그는 남지만, 필터/업스트림 단계는
아예 실행되지 않는다.

## 5. 대용량 바디 — 스트리밍 브리지(backpressure) 확인

`GatewayBody`는 버퍼링이 아니라 `Flow.Publisher<byte[]>`로 정의돼 있고, 요청/응답
양쪽 모두 `VertxReadStreamPublisher`/`VertxWriteStreamSubscriber`가 Vert.x
`ReadStream`/`WriteStream`의 `fetch(n)`/`writeQueueFull()`/`drainHandler()`를 직접
써서 다리를 놓는다. 이게 실제로 동작하는지(중간에 멈추거나 OOM 없이) 큰 바디로
확인한다.

```bash
head -c 5000000 /dev/urandom > /tmp/day10-bigfile.bin
curl -s -o /dev/null -w "status=%{http_code} uploaded=%{size_upload}\n" \
  -X POST --data-binary @/tmp/day10-bigfile.bin http://localhost:8080/echo
rm -f /tmp/day10-bigfile.bin
```

기대: `status=200 uploaded=5000000`. 서버 콘솔에 3번과 같은 전체 흐름
(`[server]` → `[engine]` → `[upstream]` → `[engine]` → `[response-writer]`)이
`POST /echo`로 다시 찍히고, hang이나 예외가 없어야 한다.

## 6. 다음에 할 일 (구조는 잡혔지만 아직 no-op인 부분)

- `RetryPolicy.none()` / `CircuitBreaker.disabled()` / `TimeoutPolicy.none()` —
  `UpstreamExecutor`가 이 셋을 호출하는 자리는 있지만 지금은 그대로 통과시킨다.
  업스트림을 일부러 죽여서 502를 확인해도(`fuser -k 9001/tcp` 후 curl) 재시도나
  회로차단 없이 즉시 실패하는 게 정상이다 — 실제 구현체가 들어가기 전까지는.
- `RouteTable.match()`는 정확 일치만 한다. 라우트가 2개 이상이 되면 Vert.x
  `Router`로 매칭을 위임하는 RouteMatcher로 교체한다(PLAN.md 2차 항목 2).
- `RuntimeSnapshot`은 부팅 시 한 번만 만들어지고 교체되지 않는다. Config 파일
  로딩 + 원자적 스왑은 PLAN.md 2차 항목 5.

## 7. 정리

```bash
fuser -k 8080/tcp 9001/tcp
```

## 체크리스트

- [ ] `./gradlew test` — `GatewayEngineTest` 통과 (Vertx 인스턴스 없이 route
      match/필터 순서/abort/에러 전파 검증)
- [ ] `GET /echo` → 200, `[server]→[engine]→[filter-chain]→[upstream]→[engine]→
      [filter-chain]→[server]→[response-writer]` 전체 흐름이 로그로 그대로 보임
- [ ] `GET /nope` → 404, `[server]`/`[engine]` 로그만 찍히고
      `[filter-chain]`/`[upstream]`은 안 찍힘 (Route Match가 engine 첫 스텝)
- [ ] 5MB POST `/echo` → 200, hang/예외 없음 (스트리밍 backpressure 브리지 검증)
