# Day 9 테스트 — Envoy 스타일 GatewayFilter 인터페이스

day8까지는 필터가 `Handler<RoutingContext>` 하나였다. day9부터는 `GatewayFilter`가
`onRequestHeaders`(요청 단계)/`onResponseHeaders`(응답 단계)로 분리되어, 로그만 봐도
요청 로그인지 응답 로그인지 구분된다. 서버 포트는 day8과 동일하게 8443(HTTPS)이다.

또한 `Main.java`가 실무 방식의 Vertx/배포 설정을 쓴다 — `MainVerticle`을
`DeploymentOptions.setInstances(2)`(기본값, `-Dgateway.instances=N`으로 조절 가능)로
2개 배포한다. 즉 이제부터는 요청이 event loop 2개(=MainVerticle 인스턴스 2개)에 라운드로빈으로
분산된다는 뜻이고, 이게 아래 8번 항목에서 확인할 "인스턴스별 로컬 상태" 문제로 이어진다.

## 1. 사전 정리

```bash
fuser -k 8080/tcp 8443/tcp 9001/tcp 9002/tcp 2>/dev/null
```

## 2. 기동

```bash
./gradlew runDay9
```

## 3. 정상 요청 — request 로그가 먼저, response 로그가 나중에

```bash
curl -sk -w " status=%{http_code}\n" https://localhost:8443/resilient/items
```

서버 콘솔에서 순서를 확인:
```
[logging] request  GET /resilient/items
[logging] response 200 /resilient/items
```
`onRequestHeaders`는 프록시 핸들러가 업스트림을 호출하기 전에, `onResponseHeaders`는
업스트림이 비동기로 응답을 완성한 **한참 뒤**에 호출된다 — `logging` 필터 자신은 응답을
전혀 만들지 않는데도(프록시 핸들러가 만듦) `ctx.addHeadersEndHandler`로 걸어둔 훅이
정확히 그 시점에 불려온다.

## 4. short-circuit(401)에서도 response 로그가 찍히는지

```bash
curl -sk -w " status=%{http_code}\n" https://localhost:8443/orders/1
```
```
[logging] request  GET /orders/1
[logging] response 401 /orders/1
```
이번엔 `authJwt`가 401로 체인을 끊었는데도(`callback.stopWithResponse()`),
`onResponseHeaders`는 여전히 호출된다 — 누가 응답을 썼는지와 무관하게 항상 불린다는 뜻.

## 5. 레이트리밋(429)도 동일

```bash
curl -sk -o /dev/null https://localhost:8443/public/items -H "X-Client-Id: frank"
curl -sk -o /dev/null https://localhost:8443/public/items -H "X-Client-Id: frank"
curl -sk -o /dev/null -w " status=%{http_code}\n" https://localhost:8443/public/items -H "X-Client-Id: frank"
```
마지막 요청 로그: `[logging] response 429 /public/items`

## 6. 회귀 확인 — 회로차단기 경로

```bash
curl -sk -w "\nstatus=%{http_code}\n" "https://localhost:8443/resilient/items?delayMs=2000"
# -> day7/day8과 동일하게 503 "circuit open, upstream unavailable"
```

## 7. (관찰용) 인스턴스가 2개라 로그/watcher도 2벌씩 찍힘

기동 로그를 보면 `[reload] ...`, `Gateway listening on port 8443 (HTTPS)`,
`[watcher] watching ...`이 전부 두 줄씩 찍힌다 — `MainVerticle` 인스턴스마다 자기
`ConfigWatcher`/HTTP 리스너를 독립적으로 갖기 때문. 두 인스턴스가 같은 포트(8443)에
listen해도 Vert.x가 내부적으로 들어오는 연결을 라운드로빈으로 나눠줘서 정상 동작한다.

## 8. (중요) 인스턴스별 로컬 상태 — 레이트리밋이 실질적으로 새는 걸 직접 확인

```bash
for i in 1 2 3 4 5; do
  curl -sk -o /dev/null -w "요청$i status=%{http_code}\n" https://localhost:8443/public/items -H "X-Client-Id: grace"
done
```

`burst: 2`로 설정했는데 실제로는 **200이 4번까지 나오고 5번째에야 429**가 뜬다.
이유: `RateLimiter`가 `MainVerticle.buildRouter()`에서 인스턴스별로 만들어지는 로컬
`HashMap` 기반이라, 인스턴스가 2개면 카운트도 2군데로 나뉘어서 실질 한도가
인스턴스 수만큼(여기선 2배) 뻥튀기된다. 앞서 대화에서 "여러 인스턴스로 스케일할 때
레이트리밋/회로차단기 상태를 Redis 같은 공유 저장소로 옮겨야 한다"고 짚었던 문제가
바로 이거고, PLAN.md도 "다음 단계로 미룸"이라고 명시한 부분이다 — day9은 일부러 이
한계를 고치지 않고 그대로 드러낸 것.

## 9. 정리

```bash
fuser -k 8080/tcp 8443/tcp 9001/tcp 9002/tcp 2>/dev/null
```

## 체크리스트

- [ ] 정상 요청: `[logging] request ...`가 먼저, `[logging] response 200 ...`이 나중에 찍힘
- [ ] 401 short-circuit에서도 response 로그가 정확한 상태코드(401)로 찍힘
- [ ] 429 short-circuit에서도 response 로그가 정확한 상태코드(429)로 찍힘
- [ ] `authJwt`/`rateLimit`/회로차단기 등 day8까지의 기능이 전부 그대로 동작 (필터
      시그니처만 바뀌었을 뿐 동작은 동일)
- [ ] 기동 로그가 인스턴스 수(기본 2)만큼 중복으로 찍힘
- [ ] 레이트리밋 burst가 인스턴스 수만큼 실질적으로 늘어남 (로컬 상태의 한계 확인)
