// 시나리오 공통 설정.
//
// BASE_URL / WS_URL 은 환경변수로 덮어쓸 수 있도록. 기본은 docker-compose 의 노출 포트.

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8088';
export const WS_URL = __ENV.WS_URL || 'ws://localhost:8088';

/**
 * SKU pool — 시연용 SKU 가 한정돼 있어 시나리오마다 같은 풀을 돌려쓴다.
 * resell-orderbook 의 시드 데이터와 의도적으로 prefix 만 맞추면 cross-portfolio
 * 시나리오 (예: orderbook 부하 → feed 부하) 도 한 SKU 집합으로 묶을 수 있다.
 */
export const SKUS = (__ENV.K6_SKUS || 'NIKE-DUNK-LOW-001,NIKE-DUNK-LOW-002,ADIDAS-YEEZY-001,JORDAN-1-RETRO-001,NEW-BALANCE-990-001')
  .split(',')
  .map((s) => s.trim())
  .filter((s) => s.length > 0);

/**
 * VU 인덱스 기반 SKU 선택 — 같은 VU 는 항상 같은 SKU 를 구독 (sticky session 시뮬레이션).
 */
export function pickSku(vuId) {
  if (SKUS.length === 0) return 'NIKE-DUNK-LOW-001';
  return SKUS[vuId % SKUS.length];
}
