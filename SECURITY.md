# Security Policy

본 문서는 `realtime-feed-service` 의 보안 취약점 보고 절차와 지원 범위를 정리합니다.

## 지원 범위

본 저장소는 한 사람이 운영하는 포트폴리오성 프로젝트입니다. 운영 SLA / 24x7 보안
패치 보장은 없으며, 다음 범위 안의 보고만 best-effort 로 대응합니다.

| 항목 | 지원 |
|---|---|
| `main` 브랜치 최신 commit | 대응 |
| 과거 tag / release | 대응 안 함 (필요 시 main 으로 fork) |
| 로컬 / 데모 환경 (`dev` profile, docker-compose) | 대응 안 함 |
| 운영 의존성 (`prod` profile, `infrastructure/*`, `helm/*`) | 대응 |

## 취약점 보고

GitHub 의 [Security Advisories](https://github.com/ssa1004/realtime-feed-service/security/advisories/new)
private vulnerability reporting 으로 보고해 주세요. 공개 issue 로 올리지 않는 것을
부탁드립니다.

대안으로 이메일도 받습니다 — 메일 주소는 GitHub 프로필에서 확인 가능합니다.

다음 정보를 함께 적어주시면 재현이 빨라집니다.
- 영향받는 commit / 모듈 / 파일 경로
- 재현 절차 (가능하면 PoC)
- 예상 영향 범위 (인증 우회 / 정보 노출 / DoS 등)

## 대응 시간

| 단계 | 목표 |
|---|---|
| 보고 접수 ack | 7일 이내 |
| 영향 범위 평가 + 수정 계획 공유 | 14일 이내 |
| 수정 patch 머지 + advisory 공개 | best-effort (심각도에 따라 가변) |

## 도메인 특성에 따른 보안 고려사항

본 서비스는 reactive WebFlux 기반의 실시간 push (WebSocket / SSE) 를 다룹니다.
다음 항목이 보안 검토에서 자주 문제가 됩니다.

- **인증 / 인가** — `feed-adapter-in` 의 Spring Security Reactive (OAuth2 Resource
  Server, JWT) 를 통한 검증. JWK Set 은 `auth-service` 가 발행 (ADR-0005 참고).
- **WebSocket / SSE 의 long-lived connection** — slow consumer / connection 누수 /
  cancellation propagation. `ReactorFeedSink` 의 `multicast onBackpressureBuffer`
  와 `sample` backpressure (ADR-0003, ADR-0006) 가 기본 방어선.
- **Kafka consumer 의 입력 검증** — 외부에서 publish 된 `market.tradematched`
  메시지의 schema / 값 invariant 검증은 `IngestTradeMatchedUseCase` 에서 수행.
- **DTO / 도메인 boundary** — `feed-domain` 은 외부 의존 0 원칙 (`build.gradle.kts`
  주석 참고). adapter 에서 들어온 raw 데이터를 도메인 값객체로 변환하며 invariant 검증.

자세한 reactive 컨벤션은 `docs/adr/` 의 ADR-0001 ~ ADR-0010 을 참고하세요.
항목별 OWASP API Top 10 (2023) 매핑은 [`docs/security/owasp-mapping.md`](docs/security/owasp-mapping.md) 을 참고하세요.

## 의존성 / SBOM

- `./gradlew dependencies` 로 의존성 트리 확인.
- 운영 이미지에 대한 Trivy 스캔은 CI (`.github/workflows/ci.yml`) 의 `build-image`
  step 에서 `HIGH,CRITICAL` 만 fail 처리.
- 향후 SBOM 자동 생성 (CycloneDX / SPDX) + dependency-track 연동은 README 의
  "향후 개선 사항" 에 두었습니다.

## 시크릿 관리

- 코드 / commit / Helm `values.yaml` 에 plain secret 을 넣지 않습니다. `existingSecret`
  / `existingSecretPasswordKey` 로 외부 Secret 을 참조합니다 (`values.yaml` 의
  `postgres.existingSecret` 등 참고).
- `.env`, `*.local.yml`, `**/credentials.*` 는 `.gitignore` 에 등록.
- 만약 시크릿이 commit 된 흔적을 발견하면 즉시 위 절차로 보고해 주세요.
