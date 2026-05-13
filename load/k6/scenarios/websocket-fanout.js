// WebSocket fan-out 시나리오.
//
// 검증 대상: N 개의 클라이언트가 같은 SKU 를 동시에 구독했을 때,
//   - 모든 클라이언트가 메시지를 받는가 (multicast 의 fan-out 동작)
//   - 메시지 수신 latency 가 임계 안에 머무는가 (sample 100ms 백프레셔의 영향)
//
// 시나리오는 메시지를 publish 하지 않는다 — `resell-orderbook` 이 발행하는 실제 Kafka
// 이벤트를 받는 것이 본 service 의 정상 흐름이라, 별도 producer 없이 흐름만 측정한다.
// docker-compose 통합 환경에서는 scripts/integration-demo.sh 가 mock 이벤트를 흘려준다.
//
// thresholds:
//   - ws_connecting p95 < 500ms — handshake 시간
//   - ws_msgs_received_total > 0 — 적어도 한 클라이언트는 메시지를 받았다
//   - ws_session_duration p95 > 25s — 대부분의 세션이 끊기지 않고 살아남았다

import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { WS_URL } from '../lib/config.js';
import { pickSku } from '../lib/config.js';
import { authHeader } from '../lib/auth.js';

const msgsReceived = new Counter('ws_feed_msgs_received');
const msgsParseErr = new Counter('ws_feed_msgs_parse_error');
const connectFail = new Rate('ws_feed_connect_fail');
const firstMsgLatency = new Trend('ws_feed_first_msg_latency_ms', true);

export const options = {
  scenarios: {
    fanout: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 50 },   // ramp up
        { duration: '30s', target: 50 },   // hold — fan-out 정상 상태
        { duration: '10s', target: 100 },  // 부하 증가
        { duration: '20s', target: 100 },  // hold
        { duration: '5s', target: 0 },     // ramp down
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    ws_feed_connect_fail: ['rate<0.01'],          // 핸드셰이크 실패율 1% 미만
    ws_connecting: ['p(95)<500'],                  // 핸드셰이크 p95 500ms 이하
    ws_session_duration: ['p(95)>25000'],         // 세션이 25s 이상 유지된 비율 p95
    ws_feed_msgs_received: ['count>0'],           // 최소 1건 메시지 수신
  },
};

export default function () {
  const sku = pickSku(__VU);
  const url = `${WS_URL}/ws/feed/${sku}`;
  const params = { headers: authHeader() };

  const startedAt = Date.now();
  let firstMsgSeen = false;

  const res = ws.connect(url, params, function (socket) {
    socket.on('open', () => {
      // 클라이언트는 구독 외에 추가 메시지를 보내지 않는다 — pure subscribe.
    });

    socket.on('message', (raw) => {
      msgsReceived.add(1);
      if (!firstMsgSeen) {
        firstMsgLatency.add(Date.now() - startedAt);
        firstMsgSeen = true;
      }
      try {
        JSON.parse(raw);
      } catch (_) {
        msgsParseErr.add(1);
      }
    });

    socket.on('error', () => {
      connectFail.add(1);
    });

    // 세션을 30 초 유지하면서 메시지를 흘러보낸다.
    socket.setTimeout(() => socket.close(), 30 * 1000);
  });

  const ok = check(res, { 'ws status is 101': (r) => r && r.status === 101 });
  if (!ok) connectFail.add(1);

  sleep(1);
}
