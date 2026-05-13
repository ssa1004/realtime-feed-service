# Load test (k6)

realtime-feed-service 의 5 가지 부하 시나리오. WebFlux + Coroutines 기반의 reactive
push 흐름을 k6 로 검증한다. 단순 RPS 측정에 더해 fan-out / sample 백프레셔 같은
ADR 정책의 동작도 함께 본다.

## 디렉토리

```
load/
├── README.md
└── k6/
    ├── lib/
    │   ├── auth.js          # mock JWT 헬퍼
    │   └── config.js        # BASE/WS URL, SKU pool
    └── scenarios/
        ├── websocket-fanout.js   # WS 다중 클라이언트 fan-out
        ├── sse-stream.js         # SSE 부하
        ├── rest-recent.js        # /recent endpoint
        ├── rest-window.js        # /window VWAP 집계
        └── backpressure.js       # slow consumer + sample 100ms 검증
```

## 사전 준비

세 가지 방법 중 하나:

### A. brew 로 로컬 설치

```bash
brew install k6
k6 version
```

### B. docker 직접 실행

```bash
docker run --rm -i grafana/k6 run - < load/k6/scenarios/rest-recent.js
```

### C. docker-compose profile 활성

`infrastructure/docker-compose.yml` 에 추가된 `k6` 서비스 (profile=`load`):

```bash
docker compose -p feed-integration -f infrastructure/docker-compose.yml \
    --profile load up k6
```

## 통합 환경 기동

k6 시나리오는 docker-compose 통합 환경의 endpoint 를 가정한다. 본 앱을 먼저 띄운다.

```bash
docker compose -p feed-integration -f infrastructure/docker-compose.yml up -d --build
./scripts/integration-demo.sh   # 헬스 + 더미 이벤트 1 회 produce
```

## 시나리오별 실행

### 1) WebSocket fan-out

다수의 WS 클라이언트가 같은 SKU 를 구독했을 때 `Sinks.many().multicast()` 가 정상
broadcast 하는지.

```bash
k6 run load/k6/scenarios/websocket-fanout.js
```

| metric | 기준 |
|---|---|
| `ws_connecting` p95 | < 500ms |
| `ws_feed_connect_fail` | < 1% |
| `ws_session_duration` p95 | > 25s |
| `ws_feed_msgs_received` total | > 0 |

### 2) SSE stream

```bash
k6 run load/k6/scenarios/sse-stream.js
```

| metric | 기준 |
|---|---|
| `sse_connect_fail` | < 1% |
| `sse_first_event_latency_ms` p95 | < 1000ms |
| `http_req_duration{name:sse-stream}` p95 | < 1500ms |

### 3) REST `/recent`

cache hit 기준의 lookup endpoint.

```bash
k6 run load/k6/scenarios/rest-recent.js
```

| metric | 기준 |
|---|---|
| `http_req_failed` | < 1% |
| `http_req_duration` p95 / p99 | < 100ms / 250ms |

### 4) REST `/window`

VWAP 집계 — DB 비용이 더 크므로 임계 느슨.

```bash
k6 run load/k6/scenarios/rest-window.js
```

| metric | 기준 |
|---|---|
| `http_req_failed` | < 1% |
| `http_req_duration` p95 / p99 | < 200ms / 500ms |

### 5) backpressure (slow consumer)

각 VU 가 메시지 처리마다 50ms busy-wait — `sample(100ms)` 정책이 메시지를 어떻게
드롭하는지 관측. drop 율 자체는 alert 가 아니라 metric 으로만 노출한다 (ADR-0003).

```bash
k6 run load/k6/scenarios/backpressure.js
```

| metric | 의미 |
|---|---|
| `ws_bp_disconnect` rate | < 1% — slow consumer 도 끊기지 않아야 한다 |
| `ws_bp_msgs_per_session` | 세션당 받은 메시지 수 (관측용) |
| `ws_bp_drop_ratio` | (예상 - 수신) / 예상 — 0 에 가까우면 sample 이 안 먹은 것 |

`K6_EXPECTED_RATE` 환경변수로 upstream produce 속도를 주입한다. 비워 두면 10 evt/s 가
기본. `scripts/run-load.sh` 가 백그라운드 producer 와 함께 조정한다.

## 한 번에 실행

```bash
./scripts/run-load.sh
```

WS → SSE → REST recent → REST window → backpressure 순으로 단계 실행. 각 단계 결과는
`build/k6-reports/` 에 JSON 으로 떨군다.

## 환경변수

| key | 기본 | 설명 |
|---|---|---|
| `BASE_URL` | `http://localhost:8088` | HTTP base |
| `WS_URL` | `ws://localhost:8088` | WebSocket base |
| `K6_TOKEN` | (빈 값) | prod 프로필일 때만 의미 — auth-stub 발급 토큰 |
| `K6_SKUS` | 5개 기본 SKU | 시나리오가 round-robin 할 SKU CSV |
| `K6_EXPECTED_RATE` | `10` | backpressure 의 upstream produce 속도 (evt/s) |

## k6 metric 해석

| metric | 의미 |
|---|---|
| `vus` / `vus_max` | 현재 / 최대 VU |
| `iter_duration` | 한 default 함수 실행 시간 — sleep 포함 |
| `iteration_duration` | iter_duration 의 동의어 (alias) |
| `http_req_duration` | HTTP 응답 소요 — connect / TLS / waiting 합 |
| `http_req_waiting` | TTFB (server-side latency 의 근사) |
| `http_req_failed` | non-2xx 비율 |
| `ws_connecting` | WS handshake 시간 |
| `ws_session_duration` | WS 세션 유지 시간 |
| `ws_msgs_received` | k6 standard 메시지 카운터 |
| `ws_msgs_sent` | 클라이언트가 send 한 수 (본 시나리오는 0) |
| `data_received` / `data_sent` | byte 카운터 — 네트워크 IO 추세 |

### p95 / p99 보는 법

- **p95** 는 변동성 신호 (95 백분위) — 일상 SLO 의 기준.
- **p99** 는 꼬리 신호 — GC, RTT 스파이크, R2DBC 풀 고갈 등 드문 이벤트.
- p95 → p99 격차가 크면 운영 환경의 reliability tail 이 두꺼운 것 — 풀 크기 /
  GC tuning / async timeout 부터 본다.

### 시나리오별 부하 모델

| 시나리오 | executor | 모델 |
|---|---|---|
| websocket-fanout | ramping-vus | 0 → 50 → 100 VU, 80s 총 |
| sse-stream | ramping-vus | 0 → 30 → 60 VU, 75s |
| rest-recent | constant-arrival-rate | 200 req/s, 60s |
| rest-window | constant-arrival-rate | 100 req/s, 60s |
| backpressure | ramping-vus | 0 → 20 VU, 55s |

`ramping-vus` 는 connection-bound 측정 (WS / SSE), `constant-arrival-rate` 는
throughput 기준 측정 (REST) 에 적합.

## 결과 예시 (참고 — 환경마다 다름)

m1 max + docker-compose 통합 (1 instance, 4 cpu, 4G heap) 기준:

```
WebSocket fan-out
  ws_connecting............... avg=42ms     p(95)=180ms
  ws_session_duration......... avg=30s      p(95)=30s
  ws_feed_msgs_received....... count=14500  rate=120/s

REST /recent (cache hit 비율 약 80%)
  http_req_duration........... avg=18ms     p(95)=62ms   p(99)=140ms
  http_req_failed............. 0.00%
  iterations.................. 12000        rate=200/s

REST /window (R2DBC 집계)
  http_req_duration........... avg=55ms     p(95)=160ms  p(99)=380ms
  http_req_failed............. 0.00%

backpressure (slow consumer 50ms busy-wait)
  ws_bp_disconnect............ rate=0.00%
  ws_bp_msgs_per_session...... avg=300      min=280   max=320
  ws_bp_drop_ratio............ avg=0.00     (upstream rate 가 sample 임계 이하라 드롭 없음)
```

upstream produce 속도를 `K6_EXPECTED_RATE=50` 으로 올리면 `ws_bp_drop_ratio` 가 0.7
부근으로 올라가야 한다 — sample 정책이 정상이라는 신호.

## 더 나아가려면

- 5 시나리오의 결과를 `build/k6-reports/*.json` 으로 떨궈서 dashboard 에 plot 한다.
- `commerce-ops` 의 Prometheus remote-write 로 `k6 → Prom → Grafana` 도
  가능 — `--out experimental-prometheus-rw=http://prom:9090/api/v1/write`.
- 더 큰 부하는 k6 cloud / k6 distributed mode 가 필요 — 본 시나리오는 single-node
  기준이라 VU 100 ~ 200 선에서 운용한다.
