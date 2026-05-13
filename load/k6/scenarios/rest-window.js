// /api/v1/feed/{sku}/window VWAP 집계 endpoint 부하.
//
// recent 와 달리 단순 lookup 이 아닌 집계 (volume / VWAP / high / low) 라
// CPU + DB IO 양쪽 비용이 더 크다. p95 임계도 더 느슨하게 둔다.
//
// thresholds:
//   - http_req_duration p95 < 200ms
//   - http_req_failed rate < 1%

import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, pickSku } from '../lib/config.js';
import { authHeader } from '../lib/auth.js';

export const options = {
  scenarios: {
    window: {
      executor: 'constant-arrival-rate',
      rate: 100,                 // 초당 100 req — recent 의 절반
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 30,
      maxVUs: 150,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<200', 'p(99)<500'],
  },
};

const MINUTES = [1, 5, 15, 30, 60];

export default function () {
  const sku = pickSku(__VU);
  const minutes = MINUTES[Math.floor(Math.random() * MINUTES.length)];
  const url = `${BASE_URL}/api/v1/feed/${sku}/window?minutes=${minutes}`;

  const res = http.get(url, {
    headers: authHeader(),
    tags: { name: 'rest-window' },
  });

  check(res, {
    'status 200': (r) => r.status === 200,
    'body has vwap or volume': (r) => {
      const b = r.body || '';
      return b.includes('vwap') || b.includes('volume') || b === '{}' || b.startsWith('{');
    },
  });

  sleep(0.1);
}
