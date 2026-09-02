# Day 7 테스트 — 회로차단기 · 타임아웃 · 재시도

`/resilient/*`에 `maxFailures: 2, timeoutMs: 500, resetTimeoutMs: 3000, maxRetries: 2`가
설정되어 있다. 더미 업스트림은 `?delayMs=N`(지연), `?fail=true`(즉시 500)로 장애를 흉내낼 수 있다.

## 1. 사전 정리

```bash
fuser -k 8080/tcp 9001/tcp 9002/tcp 2>/dev/null
```

## 2. 기동

```bash
./gradlew runDay7
```

```
Dummy upstream listening on port 9001
Dummy upstream listening on port 9002
[reload] 3 routes loaded from config/day7/routes.json
  /orders/* -> filters=[logging, authJwt] upstreamGroup=orders-service rateLimit=null resilience=null
  /public/* -> filters=[logging] upstreamGroup=orders-service rateLimit=RateLimitConfig[...] resilience=null
  /resilient/* -> filters=[logging] upstreamGroup=orders-service rateLimit=null resilience=ResilienceConfig[maxFailures=2, timeoutMs=500, resetTimeoutMs=3000, maxRetries=2]
Gateway listening on port 8080
```

## 3. 정상 요청 (라운드로빈 확인)

```bash
curl -s http://localhost:8080/resilient/items; echo
curl -s http://localhost:8080/resilient/items; echo
# -> servedBy가 upstream-9001, upstream-9002로 번갈아 나옴
```

## 4. 타임아웃 + 재시도 + 회로 open

```bash
time curl -s -w "\nstatus=%{http_code}\n" "http://localhost:8080/resilient/items?delayMs=2000"
# -> 두 업스트림 모두 500ms 타임아웃(재시도 1회 소진) 후 3번째 시도는 즉시 open 처리,
#    총 소요 시간 약 1초, 최종 503 "circuit open, upstream unavailable"
```

## 5. 회로가 열린 동안은 업스트림을 아예 호출하지 않음 (fail-fast)

```bash
time curl -s -w "\nstatus=%{http_code}\n" http://localhost:8080/resilient/items
# -> 즉시(수 ms) 503. 정상 업스트림이어도 open 상태면 호출 자체를 건너뛴다.
```

## 6. resetTimeout 이후 half-open → 복구

```bash
sleep 3.2
curl -s -w " status=%{http_code}\n" http://localhost:8080/resilient/items
# -> 정상 업스트림이면 200, breaker가 CLOSED로 복귀
```

## 7. 5xx도 회로차단기의 "실패"로 집계됨

```bash
curl -s -w " status=%{http_code}\n" "http://localhost:8080/resilient/items?fail=true"
# -> 내부적으로 두 업스트림 모두 500을 반환하며 재시도가 소진되고, maxFailures(2)를
#    넘겨 회로가 열리므로 클라이언트에는 500이 아니라 503(circuit open)이 보인다.
sleep 3.2   # 다음 테스트 전에 breaker를 다시 닫아둔다
curl -s -o /dev/null -w "reset 확인 status=%{http_code}\n" http://localhost:8080/resilient/items
```

## 8. 재시도는 멱등 메서드(GET/HEAD/OPTIONS)에만 적용됨

```bash
curl -s -X POST -w " status=%{http_code}\n" "http://localhost:8080/resilient/items?delayMs=2000"
# -> 재시도 없이 500ms 타임아웃 1번만 겪고 바로 504 "upstream timeout"
#    (POST는 재시도하지 않으므로 breaker의 실패 카운트는 1만 증가, open까지 가지 않음)
```

## 9. 정리

```bash
fuser -k 8080/tcp 9001/tcp 9002/tcp 2>/dev/null
```

## 체크리스트

- [ ] 정상 요청은 라운드로빈으로 두 업스트림에 분산됨
- [ ] 느린 업스트림은 `timeoutMs` 이후 실패로 처리되고, GET은 다른 업스트림으로 재시도됨
- [ ] 재시도까지 실패하면 `maxFailures`를 넘겨 회로가 열리고 503 응답
- [ ] 회로가 열린 동안은 업스트림 호출 없이 즉시 503 (fail-fast)
- [ ] `resetTimeout` 경과 후 half-open으로 전환되어 성공 시 다시 CLOSED로 복귀
- [ ] 업스트림이 5xx를 반환해도(연결 자체는 성공) breaker의 실패로 집계됨
- [ ] POST 등 비멱등 요청은 재시도 없이 1회만 시도(바디 재전송 문제 회피)
