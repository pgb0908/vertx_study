# day10 테스트 가이드

day9까지는 필터가 `RoutingContext`를 직접 받고 프록시가 `Handler<RoutingContext>`를
리턴하는, Vert.x Handler Chain에 종속된 구조였다. day10은 `Arch.md`가 제안한 대로
`domain`/`engine`/`runtime.vertx`로 계층을 나눈 버전이다 — `GatewayEngine`은
Vert.x를 전혀 몰라도 되고, `runtime.vertx` 패키지만 실제 소켓을 만진다.

`gateway_day10_feedback_apply.md` 피드백을 반영해 v1에서 한 단계 더 실무 구조로
갔다: Route Match/Endpoint Selection/Retry-CircuitBreaker-Timeout이 `GatewayEngine`
안에 명시적 스텝으로 존재하고, 라우트마다 독립된 `FilterChain`을 가지며,
`GatewayExchange`가 원본/현재 요청과 원본/최종 응답을 구분해서 들고 있다.

"실무 관점 부족한 것 1~6순위" 코드 리뷰를 반영해 Retry/CircuitBreaker/Timeout이
실제 `io.vertx.circuitbreaker.CircuitBreaker`를 래핑한 구현체로 동작하고
(`VertxCircuitBreaker`/`VertxRetryPolicy`), hop-by-hop 헤더 제거 +
`X-Forwarded-*`/`Via` 추가, 에러 응답 정제, `GatewayHeaders` 완전 불변화,
graceful shutdown까지 반영했다.

그리고 이제 **파일 기반 config로 부팅**한다 — `doc/*.md`에 정의된 리소스 스펙
(Listener/Connector/Router)을 `config/day10/`에서 읽어(`ConfigLoader`) 실행 가능한
형태로 컴파일한다(`RuntimeSnapshotCompiler`). 하드코딩된 라우트는 더 이상 없다.
자세한 내용은 PLAN.md 참고.

## 0. config 파일 구조

```
config/day10/
├── listeners/http-listener.json   # 서버가 들을 포트 (지금은 1개만 지원, 8080)
├── connectors/echo-connector.json # 백엔드(더미 업스트림, localhost:9001) 정의
└── routers/
    ├── echo-get-router.json       # GET /echo -> echo-connector
    └── echo-post-router.json      # POST /echo -> echo-connector (5MB 스트리밍 테스트용)
```

각 파일이 리소스 하나(`kind`/`metadata`/`spec`)다. `-Dgateway.configDir=<path>`로
다른 디렉토리를 지정할 수 있다(기본값 `config/day10`).

**Connector.spec.method/proxyPath는 "고정 API 호출 템플릿"이다** — 클라이언트가
실제로 보낸 method/path가 무엇이든, 그 Connector로 라우팅되면 항상
spec.method + spec.proxyPath로 백엔드를 호출한다(투명 프록시 아님). 클라이언트의
쿼리스트링만 유지해서 proxyPath 뒤에 그대로 붙인다. `echo-connector.json`의
method는 `POST`로 되어 있다 — GET으로 고정하면 청크 바디 스트리밍이 실제로 깨지는
Vert.x 버그를 겪어서(6번 섹션 참고), 대신 `RuntimeConnector`가 GET/HEAD로 고정된
Connector는 원본 바디를 항상 비우도록 방어 처리했다.

## 1. 사전 정리

```bash
fuser -k 8080/tcp 9001/tcp
```

## 2. 기동

```bash
./gradlew runDay10
```

다음 세 줄이 뜨면 정상(더미 업스트림 배포 → config 로딩 → 서버 기동 순서):

```
Dummy upstream listening on port 9001
day10 gateway listening on port 8080
```

config가 스펙을 어기면(필수 필드 누락, 지원 안 하는 protocol/algorithm 등)
`GatewayConfigException`과 함께 부팅이 실패한다 — 조용히 기본값으로 넘어가지
않는다. `config/day10/listeners/http-listener.json`의 `protocol`을 `"HTTPS"`로
바꿔서 실행해보면 바로 확인할 수 있다(끝나면 원복).

## 3. 정상 요청 — 계층을 통과하는 전체 흐름 확인

```bash
curl -s -i http://localhost:8080/echo
```

기대: `200`, `{"servedBy":"upstream-9001","path":"/echo"}`.

각 로그 줄이 어느 계층/스텝에서 찍히는지가 그대로 Arch.md의 실행 흐름이다 —
`[server]`(runtime.vertx 진입점, 어댑팅만) → `[engine]`(Route Match →
Downstream FilterChain → Connector 선택) → `[filter-chain]`(개별 Filter 호출) →
`[connector:echo-connector]`(고정 method/proxyPath로 재작성) → `[upstream]`
(runtime.vertx의 실제 HTTP 호출) → 다시 `[engine]`(Upstream FilterChain) →
`[server]`/`[response-writer]`(출구). `rid=`는 `GatewayExchange.requestId()`.

서버 콘솔에 다음 순서로 찍혀야 한다 (로그 레벨을 DEBUG로 보고 있다는 전제):

```
[server] <- GET /echo
[engine] rid=xxxxxxxx start GET /echo
[filter-chain] rid=xxxxxxxx onRequest[0] LoggingFilter
[request]  rid=xxxxxxxx GET /echo
[engine] rid=xxxxxxxx request filters done -> selecting destination
[connector:echo-connector] rid=xxxxxxxx calling backend POST /echo -> Endpoint[host=localhost, port=9001]
[upstream] connecting localhost:9001 POST /echo
[upstream] connected -> streaming request body
[upstream] response status=200
[engine] rid=xxxxxxxx backend responded status=200 -> response filters
[filter-chain] rid=xxxxxxxx onResponse[0] LoggingFilter
[response] rid=xxxxxxxx GET /echo -> 200
[engine] rid=xxxxxxxx complete status=200
[server] -> status=200
[response-writer] streaming body to client, status=200
[response-writer] response fully written to client
```

`GET /echo`로 요청했는데 로그에 `calling backend POST /echo`가 찍히는 게
정상이다 — echo-connector의 spec.method가 POST로 고정돼 있어서, 클라이언트가
실제로 보낸 GET과 무관하게 항상 POST로 백엔드를 호출한다.

## 4. 미매칭 경로 — 404 (경로 불일치 / 메서드 불일치 둘 다)

```bash
curl -s -i http://localhost:8080/nope        # 경로 자체가 없음
curl -s -i -X DELETE http://localhost:8080/echo  # 경로는 있는데 이 메서드용 Router가 없음
```

기대: 둘 다 `404`, `no route matched`. 서버 콘솔에 `[server] <- ...` →
`[engine] no route matches ...` → `[server] -> status=404`. `[filter-chain]`/
`[connector:...]`/`[upstream]` 로그는 전혀 안 찍혀야 한다 — Route Match는
`GatewayEngine`의 첫 스텝이라 engine 로그는 남지만, 필터/업스트림 단계는
아예 실행되지 않는다. (경로는 맞는데 메서드가 틀린 경우를 405가 아니라 404로
처리하는 건 의도적 단순화 — `RouteTable` javadoc 참고.)

## 5. 대용량 바디 — 스트리밍 브리지(backpressure) 확인

`GatewayBody`는 버퍼링이 아니라 `Flow.Publisher<byte[]>`로 정의돼 있고, 요청/응답
양쪽 모두 `VertxReadStreamPublisher`/`VertxWriteStreamSubscriber`가 Vert.x
`ReadStream`/`WriteStream`의 `fetch(n)`/`writeQueueFull()`/`drainHandler()`를 직접
써서 다리를 놓는다. 이게 실제로 동작하는지(중간에 멈추거나 OOM 없이) 큰 바디로
확인한다. `POST /echo`는 echo-post-router를 통해 echo-connector(method=POST)로
가서 바디가 실제로 끝까지 백엔드에 전달된다.

```bash
head -c 5000000 /dev/urandom > /tmp/day10-bigfile.bin
curl -s -o /dev/null -w "status=%{http_code} uploaded=%{size_upload}\n" \
  -X POST --data-binary @/tmp/day10-bigfile.bin http://localhost:8080/echo
rm -f /tmp/day10-bigfile.bin
```

기대: `status=200 uploaded=5000000`, 예외/hang 없이 대략 1~2초 내 완료(6번
섹션에서 설명한 타임아웃 여유 덕분에 안전하게 통과한다).

**echo-connector의 method를 GET으로 바꿔서 이 테스트를 다시 돌리면 안 된다** —
실제로 겪은 버그: Vert.x `HttpClient`에 method=GET으로 청크 바디를 스트리밍하면
응답이 수 ms 만에 와버리고 업로드가 전혀 진행되지 않는다(`uploaded=0`). 그래서
`RuntimeConnector`는 고정 method가 GET/HEAD면 원본 바디를 무조건 `GatewayBody.EMPTY`로
바꿔치기하도록 방어 처리했다 — `RuntimeConnectorTest.getAndHeadFixedMethodsAlwaysForceEmptyBody`
참고.

## 6. 회로차단기 + 타임아웃 + 재시도

`DummyUpstreamVerticle`은 `?delayMs=N`(N ms 지연 후 응답)과 `?fail=true`(즉시
500)를 지원한다. `echo-connector.json`의 기본값: `maxFailures=2, timeoutMs=5000,
resetTimeoutMs=3000, numRetries=2`.

**재시도 예산은 클라이언트의 원래 method가 아니라 Connector로 고정된(실제로
백엔드에 나가는) method 기준으로 결정된다.** echo-connector의 method가 `POST`
(비멱등)라서, `GET /echo?delayMs=...`로 요청해도 실제로는 재시도가 전혀 안 된다
— 이게 맞는 동작이다: 재시도 안전성은 "백엔드에 실제로 무엇을 보내는지"에 달려
있지, 클라이언트가 원래 뭘 보냈는지와는 무관하다.

```bash
# 느린 백엔드 -> 1번 시도만에 타임아웃(5000ms) -> 504 (재시도 없음, method=POST가 비멱등)
curl -s -i "http://localhost:8080/echo?delayMs=8000"

# 즉시 500 -> circuitBreaker 실패 카운트 2에 도달, 이 요청 자체는 502
curl -s -i "http://localhost:8080/echo?fail=true"

# 이제 회로가 열려 있다 -> client를 부르지도 않고 즉시 503 (거의 0초)
curl -s -i "http://localhost:8080/echo?fail=true"

# resetTimeoutMs(3000ms) 지난 뒤 정상 요청 -> half-open 테스트 성공 -> 다시 200
sleep 4 && curl -s -i http://localhost:8080/echo
```

기대: 1번째 `504 upstream timeout`(약 5초), 2번째 `502 bad gateway`(즉시, 예외
메시지 노출 없음), 3번째 `503` + `Retry-After: 1` + `circuit open, upstream
unavailable`(거의 0초), 4번째 `200`으로 회복.

서버 콘솔에서 `[server] upstream timeout -> 504`/`circuit open -> 503` 로그로
에러 매핑이 맞는지 확인한다. 멱등 메서드(GET/HEAD/OPTIONS)로 고정된 Connector를
쓰면 `[retry] attempt failed (...), N retries left -> retrying` 로그도 볼 수
있다 — `VertxRetryPolicyTest`가 이 케이스를 순수 JUnit으로 이미 검증한다.

## 7. Hop-by-hop 헤더 제거 + X-Forwarded-*/Via 확인

curl에 `Connection`/`Keep-Alive` 헤더를 직접 실어보내고, 서버 DEBUG 로그의
`[upstream] forwarding headers: [...] (skipping hop-by-hop: [...])` 줄을 확인한다.

```bash
curl -s -i -H "Connection: keep-alive" http://localhost:8080/echo
```

기대: 로그의 `forwarding headers`에 `connection`/`keep-alive`가 없고,
`x-forwarded-for`/`x-forwarded-proto`/`x-forwarded-host`/`via`는 있어야 한다
(하나도 안 보냈는데도 게이트웨이가 직접 추가한 것).

## 8. 에러 응답에 내부 메시지가 없는지 확인

```bash
fuser -k 9001/tcp   # 더미 업스트림을 죽여서 connection refused를 유발
curl -s -i http://localhost:8080/echo
```

기대: `502` + 고정 문구 `bad gateway`만 — 실제 `Connection refused` 같은 예외
메시지는 서버 로그(`log.error`)에만 남고 응답 바디에는 없어야 한다.

## 9. Graceful shutdown — in-flight 요청이 안 끊기는지

```bash
./gradlew runDay10 &
sleep 12
curl -s -o /tmp/out.json -w "HTTP_CODE=%{http_code}\n" "http://localhost:8080/echo?delayMs=2000" &
sleep 0.5
kill -TERM $(pgrep -f org.example.gateway.day10.Main)
wait
cat /tmp/out.json
```

기대: `HTTP_CODE=200` + 정상 응답 본문 — SIGTERM을 보내도 이미 처리 중이던 요청은
끝까지 완료된 뒤에 프로세스가 종료된다(`VertxGatewayServer.stop()`이 in-flight
카운트가 0이 될 때까지 최대 `DRAIN_TIMEOUT_MS`(10초) 기다린 뒤에야 리스닝 소켓을
닫는다 — `HttpServer.close()`를 먼저 부르면 Vert.x 자체 동작("Any open HTTP
connections will be closed")때문에 draining 의미가 없어져서, 반드시 이 순서를
지켜야 한다).

## 10. 다음에 할 일 (아직 안 된 것)

- `RouteTable.match()`는 path+method 정확 일치만 한다(쿼리스트링은 잘라내고
  비교). 라우트가 많아지고 prefix 매칭이 필요해지면 Vert.x `Router`로 매칭을
  위임하는 RouteMatcher로 교체한다(PLAN.md 2차 항목).
- hot-reload 없음 — config는 부팅 시 한 번만 읽는다.
- Listener는 1개만 지원(2개 이상이면 부팅 실패). healthCheck/maxRequestBodySize/
  maxResponseBodySize/Listener.tls/Connector.upstreamTls는 파싱조차 안 한다.
- LoadBalancer는 ROUND_ROBIN만(algorithm이 다르면 부팅 실패). Connector의
  retryOn 조건/retryBackoff/perTryTimeout/timeout.connect/timeout.send는
  파싱만 하고 반영하지 않는다(day10 단순 resilience 모델에 매핑된 값만 씀).
- Policy 리소스(`Policy_auth_apikey` 등)는 아직 파싱/연동 안 함 — 모든 Router에
  `LoggingFilter`만 기본으로 붙는다.
- JWT/RateLimit Filter, TLS 종단은 여전히 미이식.

## 11. 정리

```bash
fuser -k 8080/tcp 9001/tcp
```

## 체크리스트

- [ ] `./gradlew test` — 전체 통과 (config/engine/runtime.vertx 신규 테스트 포함)
- [ ] `GET /echo` → 200, 전체 흐름 로그에 `calling backend POST /echo`(고정 method)
      가 보임
- [ ] `GET /nope` → 404, `DELETE /echo` → 404 (경로/메서드 각각 불일치)
- [ ] 5MB POST `/echo` → 200, hang/예외 없음
- [ ] `?delayMs=8000` → 504(재시도 없음), `?fail=true` 두 번째 → 503, 이후 정상
      요청 → 200 회복
- [ ] `Connection: keep-alive` curl → 로그에서 hop-by-hop 헤더 제거 +
      `X-Forwarded-*`/`Via` 추가 확인
- [ ] 업스트림 다운 상태에서 502 응답 바디에 내부 예외 메시지 없음
- [ ] SIGTERM 중 in-flight 요청이 200으로 끝까지 완료된 뒤 프로세스 종료
- [ ] `listeners/http-listener.json`의 protocol을 HTTPS로 바꾸면 부팅이
      `GatewayConfigException`으로 명확히 실패함(끝나면 원복)
