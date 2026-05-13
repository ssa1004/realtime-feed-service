// SSE 부하 시나리오.
//
// 검증 대상: WebSocket 대신 SSE 로 구독하는 클라이언트가 N 명 붙었을 때,
//   - 연결이 안정적으로 유지되는가 (HTTP keep-alive)
//   - 첫 이벤트 수신까지의 latency
//
// k6 의 http 모듈에는 SSE 전용 reader 가 없어 streaming response 를 chunk 단위로
// 읽는다. 한 요청을 길게 끌면 VU 가 다른 일을 못 하므로, 30s 짜리 짧은 hold 로 한 번에
// 처리량을 측정한다.
//
// thresholds:
//   - http_req_duration{name:sse-stream} p95 < 1500ms (chunk 첫 도착까지)
//   - sse_first_event_latency p95 < 1000ms
//   - sse_connect_fail rate < 1%

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { BASE_URL, pickSku } from '../lib/config.js';
import { authHeader } from '../lib/auth.js';

const firstEventLatency = new Trend('sse_first_event_latency_ms', true);
const eventsReceived = new Counter('sse_events_received');
const connectFail = new Rate('sse_connect_fail');

export const options = {
  scenarios: {
    sse: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 30 },
        { duration: '30s', target: 30 },
        { duration: '10s', target: 60 },
        { duration: '20s', target: 60 },
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    sse_connect_fail: ['rate<0.01'],
    sse_first_event_latency_ms: ['p(95)<1000'],
    'http_req_duration{name:sse-stream}': ['p(95)<1500'],
  },
};

export default function () {
  const sku = pickSku(__VU);
  const url = `${BASE_URL}/api/v1/feed/${sku}/stream`;

  const params = {
    headers: { Accept: 'text/event-stream', ...authHeader() },
    timeout: '30s',
    tags: { name: 'sse-stream' },
    // chunked response — k6 는 body 가 끝날 때까지 모은다.
  };

  const startedAt = Date.now();
  const res = http.get(url, params);

  const ok = check(res, {
    'sse status 200': (r) => r.status === 200,
    'content-type text/event-stream': (r) =>
      (r.headers['Content-Type'] || '').includes('text/event-stream'),
  });
  if (!ok) connectFail.add(1);

  // body 에서 SSE 이벤트 (`data:` prefix) 개수를 센다.
  const body = res.body || '';
  let count = 0;
  const lines = body.split('\n');
  let firstDataIdx = -1;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].startsWith('data:')) {
      count++;
      if (firstDataIdx === -1) firstDataIdx = i;
    }
  }
  eventsReceived.add(count);
  if (count > 0) {
    // body 가 완전히 끝난 뒤에야 측정 가능 — 첫 chunk 가 아닌 전체 응답 시간의 근사.
    firstEventLatency.add(Date.now() - startedAt);
  }

  sleep(1);
}
