# Day 8 테스트 — TLS 종단 (HTTPS)

게이트웨이가 8080(평문) 대신 8443에서 HTTPS로만 요청을 받는다. 인증서는
`config/day8/tls/cert.pem`/`key.pem`(자체서명, CN=localhost)을 쓴다. 로컬
자체서명이라 curl에는 `-k`(인증서 검증 생략)가 필요하다.

## 0. 인증서가 없다면 재생성

이미 생성해뒀지만, 만료되었거나 지웠다면:
```bash
mkdir -p config/day8/tls
openssl req -x509 -newkey rsa:2048 -keyout config/day8/tls/key.pem -out config/day8/tls/cert.pem \
  -days 365 -nodes -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"
```

## 1. 사전 정리

```bash
fuser -k 8080/tcp 8443/tcp 9001/tcp 9002/tcp 2>/dev/null
```

## 2. 기동

```bash
./gradlew runDay8
```

```
Dummy upstream listening on port 9001
Dummy upstream listening on port 9002
[reload] 3 routes loaded from config/day8/routes.json
  ...
Gateway listening on port 8443 (HTTPS)
```

## 3. 평문 HTTP로는 접속 불가 확인

```bash
curl -m 3 -o /dev/null -w "status=%{http_code}\n" http://localhost:8443/resilient/items
# -> 연결 자체가 실패(status=000). SSL 핸드셰이크를 기대하는 포트에 평문으로 접속했기 때문.
```

## 4. HTTPS 정상 접속 + 인증서 확인

```bash
curl -sk -w "\nstatus=%{http_code}\n" https://localhost:8443/resilient/items

curl -skv https://localhost:8443/resilient/items 2>&1 | grep -i "subject\|issuer\|SSL connection"
# -> subject: CN=localhost / issuer: CN=localhost (자체서명이라 subject==issuer)
```

## 5. day5~7에서 만든 필터 체인이 HTTPS 위에서도 그대로 동작하는지 (통합 확인)

```bash
# JWT 인증: 토큰 없이 401
curl -sk -w " status=%{http_code}\n" https://localhost:8443/orders/1

# JWT 인증: 유효한 토큰으로 200
TOKEN=$(./gradlew -q genDay5Token)
curl -sk -w "\nstatus=%{http_code}\n" https://localhost:8443/orders/42 -H "Authorization: Bearer $TOKEN"

# 레이트리밋: burst 2 -> 200, 200, 429
for i in 1 2 3; do
  curl -sk -o /dev/null -w "요청$i status=%{http_code}\n" https://localhost:8443/public/items -H "X-Client-Id: eve"
done
```

## 6. 정리

```bash
fuser -k 8080/tcp 8443/tcp 9001/tcp 9002/tcp 2>/dev/null
```

## 체크리스트

- [ ] 평문 HTTP로 8443에 접속하면 실패함 (TLS 전용)
- [ ] `-k`로 HTTPS 접속 시 정상 응답, 인증서 subject가 CN=localhost
- [ ] JWT 인증(토큰 없음 401 / 유효 토큰 200)이 HTTPS 위에서도 그대로 동작
- [ ] 레이트리밋(버스트 2 + 429)이 HTTPS 위에서도 그대로 동작
- [ ] 회로차단기/hot-reload 등 day7까지의 기능이 모두 HTTPS 위에서 동일하게 동작 (전송 계층만 바뀌고 필터 체인은 그대로)
