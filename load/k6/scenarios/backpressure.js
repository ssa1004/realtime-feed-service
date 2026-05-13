// backpressure 검증 시나리오.
//
// 검증 대상: 느린 client 가 WebSocket 으로 구독했을 때, sink 의 sample 100ms 정책이
// 의도대로 동작하는가. 즉,
//   - 모든 메시지가 도착하지 않아도 OK (드롭이 정상)
//   - 단, 클라이언트가 끊기지 않아야 한다 (slow consumer 가 fast path 를 막지 않음)
//   - 100ms 윈도우당 평균 1 건 이하로 떨어지는지 (sample 의 정의)
//
// 시나리오는 의도적으로 message 처리에 latency 를 박는다 — 실제 느린 브라우저 / 모바일
// 환경을 시뮬레이션. busy-wait 으로 50ms~150ms 정도 처리 시간을 인위적으로 만든다.
//
// 본 측정은 단발 endpoint 호출과 달리 "관측 가능한 동작" 을 확인하는 성격이라 thresholds
// 는 felonious 한 실패 (연결 끊김) 만 차단한다. 드롭률은 ADR-0003 의 정책 검증을 위해
// custom metric 으로 노출만 한다.
//
// thresholds:
//   - ws_bp_disconnect rate < 1% — slow consumer 도 끊기지 않는 것이 정책
//   - ws_bp_msgs_received count > 0 — 한 명이라도 받으면 OK
//   - ws_bp_drop_ratio 는 alert 가 아닌 관측 — Grafana 에 plot 해서 sample 정책 확인

import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { WS_URL, pickSku } from '../lib/config.js';
import { authHeader } from '../lib/auth.js';

const msgsReceived = new Counter('ws_bp_msgs_received');
const disconnect = new Rate('ws_bp_disconnect');
const sessionMsgRate = new Trend('ws_bp_msgs_per_session', false);
const dropRatio = new Trend('ws_bp_drop_ratio', false);

// upstream Kafka publish 율의 추정치 — 시나리오 외부에서 produce 되는 이벤트.
// scripts/run-load.sh 가 백그라운드로 일정 rate publisher 를 띄울 때 일치시킨다.
// docker-compose 통합 환경에서는 30 events/min/sku 정도가 기대치 (sample 100ms 이하).
const EXPECTED_UPSTREAM_PER_SEC = Number(__ENV.K6_EXPECTED_RATE || '10');
const SESSION_SECONDS = 30;

export const options = {
  scenarios: {
    slow_consumer: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 20 },
        { duration: '40s', target: 20 },
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    ws_bp_disconnect: ['rate<0.01'],
    ws_bp_msgs_received: ['count>0'],
  },
};

export default function () {
  const sku = pickSku(__VU);
  const url = `${WS_URL}/ws/feed/${sku}`;
  const params = { headers: authHeader() };

  let perSessionCount = 0;

  const res = ws.connect(url, params, function (socket) {
    socket.on('message', () => {
      msgsReceived.add(1);
      perSessionCount++;

      // 느린 client 시뮬레이션 — 50ms busy-wait.
      // sample 100ms 윈도우보다 짧지만, 처리 자체가 메시지마다 동기적으로 들어간다.
      const until = Date.now() + 50;
      while (Date.now() < until) {
        // 의도적인 busy-wait — Node sleep API 가 k6 에 없어서 직접 회전.
      }
    });

    socket.on('close', () => {
      const expected = EXPECTED_UPSTREAM_PER_SEC * SESSION_SECONDS;
      sessionMsgRate.add(perSessionCount);
      if (expected > 0) {
        const dropped = Math.max(0, expected - perSessionCount);
        dropRatio.add(dropped / expected);
      }
    });

    socket.on('error', () => {
      disconnect.add(1);
    });

    socket.setTimeout(() => socket.close(), SESSION_SECONDS * 1000);
  });

  check(res, { 'ws status 101': (r) => r && r.status === 101 });

  sleep(1);
}
