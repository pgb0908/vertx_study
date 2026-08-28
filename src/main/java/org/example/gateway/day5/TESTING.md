# Day 5 테스트 — JWT 인증 필터

Day 5는 day4까지의 가짜 `auth()`(헤더 존재 여부만 체크) 대신, 실제 JWT
서명/만료를 검증하는 `authJwt()` 필터가 핵심이다. 게이트웨이 자체가 토큰을
발급하진 않지만(실무에서는 별도 인증 서버가 발급), 검증 로직만 독립적으로
테스트할 수 있도록 같은 비밀키로 서명된 토큰을 뽑아주는 `genDay5Token` 도구를
함께 만들었다.

## 1. 사전 정리

```bash
fuser -k 8080/tcp 9001/tcp 9002/tcp 2>/dev/null
```

## 2. 기동

```bash
./gradlew runDay5
```

정상 기동 로그:

```
Dummy upstream listening on port 9001
Dummy upstream listening on port 9002
[reload] 2 routes loaded from config/day5/routes.json
  /orders/* -> filters=[logging, authJwt] upstreamGroup=orders-service
  /public/* -> filters=[logging] upstreamGroup=orders-service
Gateway listening on port 8080
[watcher] watching .../config/day5
```

## 3. 테스트용 토큰 발급

```bash
TOKEN=$(./gradlew genDay5Token -q)
echo "$TOKEN"
```

`MainVerticle`과 같은 공유 비밀키(`JwtConfig.SHARED_SECRET`)로 서명되므로,
이 토큰은 게이트웨이가 검증에 성공해야 정상이다.

## 4. 인증 시나리오별 확인

```bash
# 토큰 없이 -> 401 (missing bearer token)
curl -s -w " [status=%{http_code}]\n" http://localhost:8080/orders/1

# 위조/깨진 토큰 -> 401 (invalid token: ...)
curl -s -w " [status=%{http_code}]\n" \
  -H "Authorization: Bearer garbage.token.value" \
  http://localhost:8080/orders/1

# 유효한 토큰 -> 200 + 프록시 통과 (servedBy JSON)
curl -s -w " [status=%{http_code}]\n" \
  -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/orders/1

# /public/*는 authJwt 필터가 없으므로 토큰 없이도 통과
curl -s -w " [status=%{http_code}]\n" http://localhost:8080/public/items
```

## 5. 정리

```bash
fuser -k 8080/tcp 9001/tcp 9002/tcp 2>/dev/null
```

## 체크리스트

- [ ] `/orders/*` 토큰 없이 401 (`missing bearer token`)
- [ ] `/orders/*` 위조 토큰 401 (`invalid token: ...`)
- [ ] `/orders/*` 유효한 토큰 200 + 프록시 응답
- [ ] `/public/*`는 토큰 없이 통과 (해당 라우트는 `authJwt` 필터 미적용)
- [ ] `genDay5Token`으로 발급한 토큰이 매번 다르게 나오는지 (서명 시각 `iat` 포함 확인용)
