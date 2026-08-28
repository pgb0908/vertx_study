# Day 6 테스트 — 레이트리밋 필터 (토큰 버킷)

`/public/*`에 `requestsPerSecond: 1, burstSize: 2`로 설정되어 있다. 즉 클라이언트당
한 번에 2개까지는 몰아 써도 되고(버스트), 그 이후로는 초당 1개씩만 리필된다.
클라이언트 식별은 `X-Client-Id` 헤더(없으면 접속 IP)로 한다.

## 1. 사전 정리

```bash
fuser -k 8080/tcp 9001/tcp 9002/tcp 2>/dev/null
```

## 2. 기동

```bash
./gradlew runDay6
```

```
Dummy upstream listening on port 9001
Dummy upstream listening on port 9002
[reload] 2 routes loaded from config/day6/routes.json
  /orders/* -> filters=[logging, authJwt] upstreamGroup=orders-service rateLimit=null
  /public/* -> filters=[logging] upstreamGroup=orders-service rateLimit=RateLimitConfig[requestsPerSecond=1.0, burstSize=2]
Gateway listening on port 8080
```

## 3. 버스트 소진 + 429 확인

```bash
for i in 1 2 3 4; do
  curl -s -o /dev/null -w "요청$i: status=%{http_code}\n" \
    http://localhost:8080/public/items -H "X-Client-Id: alice"
done
# -> 200, 200, 429, 429 예상 (버스트 2개 쓰고 나머지는 거절)
```

## 4. 클라이언트별 독립 확인

```bash
curl -s -o /dev/null -w "status=%{http_code}\n" \
  http://localhost:8080/public/items -H "X-Client-Id: bob"
# -> alice가 이미 소진했어도 bob은 200 (서로 다른 버킷)
```

## 5. Retry-After 헤더 확인

```bash
curl -s -D - -o /dev/null http://localhost:8080/public/items -H "X-Client-Id: alice" \
  | grep -i "retry-after\|HTTP"
# -> HTTP/1.1 429 Too Many Requests
#    Retry-After: 1
```

## 6. 토큰 리필 확인

```bash
sleep 1.2
curl -s -o /dev/null -w "status=%{http_code}\n" \
  http://localhost:8080/public/items -H "X-Client-Id: alice"
# -> 1.2초 뒤 토큰 1개 리필되어 200
```

## 7. 정리

```bash
fuser -k 8080/tcp 9001/tcp 9002/tcp 2>/dev/null
```

## 체크리스트

- [ ] 버스트(2개) 소진 후 429
- [ ] 429 응답에 `Retry-After` 헤더 포함
- [ ] 서로 다른 `X-Client-Id`는 독립적인 버킷을 가짐
- [ ] 일정 시간 대기 후 토큰이 리필되어 다시 200
- [ ] `/orders/*`는 rateLimit 미설정이라 반복 요청해도 429 없음 (JWT 인증만 적용)
