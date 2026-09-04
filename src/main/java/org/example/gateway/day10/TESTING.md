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

## 3. 정상 요청 — request 로그가 먼저, response 로그가 나중에

```bash
curl -s -i http://localhost:8080/echo
```

기대: `200`, `{"servedBy":"upstream-9001","path":"/echo"}`.

서버 콘솔에 다음 순서로 찍혀야 한다 (`LoggingPolicy.before`가 요청 시점,
`after`가 응답 시점 — GatewayEngine이 정책을 before는 순서대로, after는 역순으로
돌린다는 걸 실제 HTTP 요청으로도 확인하는 지점):

```
[request] GET /echo
[response] GET /echo -> 200
```

## 4. 미매칭 경로 — 404

```bash
curl -s -i http://localhost:8080/nope
```

기대: `404`, `no route matched`. 서버 콘솔에 `[unmatched] GET /nope`.

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

기대: `status=200 uploaded=5000000`. 서버 콘솔에 `[request] POST /echo` →
`[response] POST /echo -> 200` 순서로 찍히고, hang이나 예외가 없어야 한다.

## 6. 정리

```bash
fuser -k 8080/tcp 9001/tcp
```

## 체크리스트

- [ ] `./gradlew test` — `GatewayEngineTest` 2개 통과 (Vertx 인스턴스 없이 정책
      순서 검증)
- [ ] `GET /echo` → 200, request/response 로그 순서대로 출력
- [ ] `GET /nope` → 404, `[unmatched]` 로그
- [ ] 5MB POST `/echo` → 200, hang/예외 없음 (스트리밍 backpressure 브리지 검증)
