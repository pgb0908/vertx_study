# day10 테스트 가이드

day9까지는 필터가 `RoutingContext`를 직접 받고 프록시가 `Handler<RoutingContext>`를
리턴하는, Vert.x Handler Chain에 종속된 구조였다. day10은 `Arch.md`(day9 패키지에
있음)가 제안한 대로 `domain`/`engine`/`runtime.vertx`로 계층을 나눈 첫 버전이다 —
`GatewayEngine`은 Vert.x를 전혀 몰라도 되고, `runtime.vertx` 패키지만 실제 소켓을
만진다.

v1 범위는 최소다: 라우트 하나(`/echo`), 정책 하나(`LoggingPolicy`), 단일 업스트림
프록시. JWT/rate limit/circuit breaker/TLS/hot-reload는 없다 — 이 단계의 목적은
기능 이식이 아니라 "GatewayEngine이 Vert.x 없이 독립적으로 동작하는가"를 검증하는
것이다. 그건 `GatewayEngineTest`(순수 JUnit5, `./gradlew test`)가 이미 검증한다.
여기서는 그 위에 얹힌 `runtime.vertx` 계층(실제 HTTP, 스트리밍 바디)이 진짜로
동작하는지 curl로 확인한다.

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

각 로그 줄이 어느 계층에서 찍히는지가 그대로 Arch.md의 계층 구조다 —
`[server]`(runtime.vertx 진입점) → `[engine]`(Vert.x 비의존 실행 모델,
before는 순서대로/after는 역순) → `[upstream]`(runtime.vertx의 실제 HTTP
호출) → 다시 `[engine]` → `[server]`/`[response-writer]`(runtime.vertx
출구). `[request]`/`[response]`는 `LoggingPolicy`가 `[engine]`의
before/after-policy 호출 안에서 찍는 것 — 정책이 엔진에 "얹혀서" 실행된다는
걸 로그로도 확인할 수 있다.

서버 콘솔에 다음 순서로 찍혀야 한다:

```
[server] <- GET /echo
[server] matched route /echo -> adapting HttpServerRequest to GatewayRequest
[engine] execute() start: GET /echo route=/echo policies=1
[engine]   before-policy[0] LoggingPolicy
[request] GET /echo
[engine] before-chain done -> dispatching to upstream Endpoint[host=localhost, port=9001]
[upstream] connecting to localhost:9001 for GET /echo
[upstream] connected -> streaming request body
[upstream] response headers received, status=200
[engine] upstream responded status=200 -> running after-chain
[engine]   after-policy[0] LoggingPolicy
[response] GET /echo -> 200
[engine] execute() complete -> status=200
[server] -> writing status=200 back to client
[response-writer] streaming body to client, status=200
[response-writer] response fully written to client
```

## 4. 미매칭 경로 — 404

```bash
curl -s -i http://localhost:8080/nope
```

기대: `404`, `no route matched`. 서버 콘솔에 `[server] <- GET /nope` →
`[server] no route matches /nope -> 404`. `[engine]`/`[upstream]` 로그는 전혀
안 찍혀야 한다 — 매칭 실패는 engine까지 가지 않고 runtime.vertx 계층에서 끝난다.

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

## 6. 정리

```bash
fuser -k 8080/tcp 9001/tcp
```

## 체크리스트

- [ ] `./gradlew test` — `GatewayEngineTest` 2개 통과 (Vertx 인스턴스 없이 정책
      순서 검증)
- [ ] `GET /echo` → 200, `[server]→[engine]→[upstream]→[engine]→[response-writer]`
      전체 흐름이 로그로 그대로 보임
- [ ] `GET /nope` → 404, `[server]` 로그만 찍히고 `[engine]`/`[upstream]`은 안 찍힘
- [ ] 5MB POST `/echo` → 200, hang/예외 없음 (스트리밍 backpressure 브리지 검증)
