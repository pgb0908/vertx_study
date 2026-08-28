# Day 4 테스트 — 무중단 Hot-Reload

Day 4는 `config/day4/routes.json`, `config/day4/upstreams.json`이 바뀌면
**서버 재시작 없이** 라우팅이 즉시 반영되는지가 핵심이다. 따라서 테스트도
"서버를 켠 채로 파일을 수정 → curl로 즉시 반영 확인" 흐름으로 진행한다.

## 1. 사전 정리 (포트 충돌 방지)

이전 Day 실행이 남아있으면 포트가 겹쳐 기동이 실패하므로 먼저 정리한다.

```bash
fuser -k 8080/tcp 9001/tcp 9002/tcp 2>/dev/null
```

## 2. 기동

```bash
./gradlew runDay4
```

정상 기동 시 아래 로그가 순서대로 찍힌다.

```
Dummy upstream listening on port 9001
Dummy upstream listening on port 9002
[reload] 2 routes loaded from config/day4/routes.json
  /orders/* -> filters=[logging, auth] upstreamGroup=orders-service
  /public/* -> filters=[logging] upstreamGroup=orders-service
Gateway listening on port 8080
[watcher] watching .../config/day4
```

## 3. 기본 동작 확인 (day3와 동일한 필터 체인 + 프록시)

```bash
# 인증 없이 /orders/* -> 401
curl -s -w " [status=%{http_code}]\n" http://localhost:8080/orders/1

# 인증 헤더 포함 -> 프록시 통과 + 라운드로빈
curl -s -H "X-Api-Key: test" http://localhost:8080/orders/1
curl -s -H "X-Api-Key: test" http://localhost:8080/orders/1   # servedBy가 9001 -> 9002로 바뀌는지 확인

# /public/* -> 인증 없이 통과
curl -s http://localhost:8080/public/items
```

## 4. Hot-Reload 확인 (핵심)

**4-1. 변경 전 — 아직 없는 경로는 404여야 한다.**

```bash
curl -s -w " [status=%{http_code}]\n" http://localhost:8080/new-path/1
# -> 404 예상
```

**4-2. 서버를 끄지 않은 채로 `config/day4/routes.json`에 라우트를 추가한다.**

```bash
cat > config/day4/routes.json <<'EOF'
{
  "routes": [
    { "path": "/orders/*", "filters": ["logging", "auth"], "upstreamGroup": "orders-service" },
    { "path": "/public/*", "filters": ["logging"], "upstreamGroup": "orders-service" },
    { "path": "/new-path/*", "filters": ["logging"], "upstreamGroup": "orders-service" }
  ]
}
EOF
```

**4-3. 로그에 reload가 찍히는지 확인한다.**

```
[reload] 3 routes loaded from config/day4/routes.json
  /orders/* -> filters=[logging, auth] upstreamGroup=orders-service
  /public/* -> filters=[logging] upstreamGroup=orders-service
  /new-path/* -> filters=[logging] upstreamGroup=orders-service
```

**4-4. 재시작 없이 새 경로가 즉시 동작하는지 확인한다.**

```bash
curl -s -w " [status=%{http_code}]\n" http://localhost:8080/new-path/1
# -> 200 + servedBy JSON 예상 (재시작 없이)
```

**4-5. 테스트가 끝나면 `config/day4/routes.json`을 원래 상태(라우트 2개)로 되돌려 놓는다.**
(저장소에 커밋되는 기본 설정 파일이 테스트 흔적으로 남지 않도록)

## 5. 정리

```bash
fuser -k 8080/tcp 9001/tcp 9002/tcp 2>/dev/null
```

## 체크리스트

- [ ] `/orders/*` 인증 없이 401
- [ ] `/orders/*` 인증 포함 200 + 라운드로빈(9001/9002 교차) 확인
- [ ] `/public/*` 인증 없이 200
- [ ] 신규 경로 추가 전 404
- [ ] 파일 수정 후 재시작 없이 `[reload]` 로그 확인
- [ ] 신규 경로가 재시작 없이 200으로 응답
- [ ] 테스트용 설정 변경분 원복
