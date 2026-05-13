// mock JWT helper — k6 시나리오에서 Authorization 헤더에 붙일 토큰을 만든다.
//
// 두 가지 경로를 지원한다:
//   1) BASE 가 dev 프로필이면 SecurityConfig.devSecurityFilterChain 이 모든 요청을
//      permitAll() 한다. 토큰 없이도 200 이 떨어지므로 빈 문자열을 돌려준다.
//   2) prod / 그 외 프로필이면 OAuth2 Resource Server 가 JWT 를 강제로 본다.
//      auth-stub 같은 별도 발급기가 있다면 K6_TOKEN env 로 주입한다. 없으면 빈 토큰.
//
// 실제 auth-service / auth-stub 통합 발급 (Pre-flight 로 /oauth/token 호출) 은
// 본 시나리오의 범위 바깥. 토큰 라이프사이클 검증이 아닌 endpoint 부하 측정이 목적이라
// dev 프로필 + 빈 토큰 조합으로 충분히 covered 된다.

import encoding from 'k6/encoding';

const ENV_TOKEN = __ENV.K6_TOKEN || '';

/**
 * Authorization 헤더 객체를 돌려준다. 토큰이 비어 있으면 빈 객체.
 */
export function authHeader() {
  if (!ENV_TOKEN) return {};
  return { Authorization: `Bearer ${ENV_TOKEN}` };
}

/**
 * 토큰 raw 값을 돌려준다 — WebSocket subprotocol 등에서 헤더가 아닌 곳에 끼울 때 사용.
 */
export function rawToken() {
  return ENV_TOKEN;
}

/**
 * 테스트용 unsigned JWT — kid 가 맞지 않으면 prod 에서는 reject. dev 에서만 의미 있음.
 * jwt.io 호환 base64url 인코딩. 서명은 sha256 가 k6 stdlib 에 없어 빈 값으로 둔다.
 *
 * @param subject {string} — sub claim
 * @param ttlSeconds {number} — exp 까지의 초
 */
export function unsignedJwt(subject = 'k6-load', ttlSeconds = 3600) {
  const header = { alg: 'none', typ: 'JWT' };
  const now = Math.floor(Date.now() / 1000);
  const payload = {
    sub: subject,
    iat: now,
    exp: now + ttlSeconds,
    scope: 'feed:read',
  };
  const part = (o) => base64url(JSON.stringify(o));
  return `${part(header)}.${part(payload)}.`;
}

function base64url(s) {
  return encoding.b64encode(s, 'rawurl');
}
