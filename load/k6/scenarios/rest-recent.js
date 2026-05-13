// /api/v1/feed/{sku}/recent REST endpoint 부하.
//
// catch-up 용 endpoint — Redis SortedSet 에서 ZRANGE 로 최근 N 건을 가져오고,
// hit miss 시 PostgreSQL R2DBC 로 fallback. 본 시나리오는 cache hit 비율을
// 측정하기보다 단순히 throughput / latency 를 본다.
//
// thresholds:
//   - http_req_duration p95 < 100ms — 캐시 hit 기준
//   - http_req_failed rate < 1%

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, pickSku } from '../lib/config.js';
import { authHeader } from '../lib/auth.js';

export const options = {
  scenarios: {
    recent: {
      executor: 'constant-arrival-rate',
      rate: 200,                 // 초당 200 req
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<100', 'p(99)<250'],
  },
};

const LIMITS = [10, 20, 50, 100];

export default function () {
  const sku = pickSku(__VU);
  const limit = LIMITS[Math.floor(Math.random() * LIMITS.length)];
  const url = `${BASE_URL}/api/v1/feed/${sku}/recent?limit=${limit}`;

  const res = http.get(url, {
    headers: authHeader(),
    tags: { name: 'rest-recent' },
  });

  check(res, {
    'status 200': (r) => r.status === 200,
    'body is JSON array': (r) => (r.body || '').trim().startsWith('['),
  });

  sleep(0.1);
}
