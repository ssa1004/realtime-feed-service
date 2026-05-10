# Contributing

본 저장소의 개발 흐름과 commit 규칙을 정리한 문서입니다.

## 브랜치 전략

GitHub Flow 를 따릅니다. `main` 은 항상 배포 가능한 상태로 유지되며, 모든 작업은 feature
브랜치에서 진행됩니다.

```
main (protected)
  ├── feature/sse-stream-router         ← 기능 브랜치
  ├── fix/ws-cancellation-leak
  └── docs/update-adr-0007
```

흐름은 `git checkout -b feature/<짧은-설명>` → 작업 → PR → 코드 리뷰 + CI 통과 → Squash and
merge 입니다. 머지 후 feature 브랜치는 즉시 삭제합니다.

## Commit 메시지

Conventional Commits 형식을 따릅니다.

```
<type>(<scope>): <짧은 설명, 50자 이내>

<상세 설명, 한 줄에 72자 이내>
- 무엇이 / 왜 변경되었는지
- 영향받는 모듈
```

사용하는 type: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `perf`.
scope 에는 모듈명 (`domain`, `application`, `adapter-out`, `adapter-in`, `bootstrap` 등) 이
들어갑니다.

reactive backpressure / cancellation / sink 가 도메인의 핵심이므로 관련 commit 이
자주 발생합니다.

### 예시

```
feat(adapter-in): WebSocket subscribe path 에 SKU 검증 추가

- /ws/feed/{skuId} 의 skuId 가 비거나 길이 64 초과면 즉시 close
- SkuId value class 의 init 블록과 같은 invariants 를 입구에서 한 번 더 검증
```

```
fix(adapter-out): ReactorFeedSink 의 EmitResult 누락 처리

emit 결과가 OK 가 아니면 metric 을 올리고 logger 에 기록.
이전에는 false silently — slow consumer overflow 가 보이지 않던 문제.
```

## Commit 단위

한 commit 은 한 가지 논리적 변경을 담는 것을 원칙으로 합니다. 새 기능 + 리팩터링 + 버그
수정이 한 commit 에 같이 포함되어 있다면 거의 항상 분리 가능합니다. WIP commit 은 PR 머지
전에 squash 합니다.

## 테스트

PR 전 `./gradlew check` 통과가 필수입니다. 모듈별로 빠르게 돌려보려면:

- 도메인: `:feed-domain:test`
- 애플리케이션: `:feed-application:test`
- inbound (WebFlux + WebSocket + SSE): `:feed-adapter-in:test`
- outbound (Sink / Kafka / R2DBC / Redis): `:feed-adapter-out:test`

## 코드 스타일

- Kotlin: official style (`kotlin.code.style=official` 가 `gradle.properties` 에).
- 100% Kotlin 정책 (AGENTS.md §4) — Java 파일 0개.
- 주석 / 문서는 자연스러운 한국어 (영어 직역체 지양).
- 도메인 모델은 `data class` + `val` (불변). value class (`@JvmInline`) 적극 사용.
- sealed interface / sealed class 로 상태와 이벤트를 닫는다 (when exhaustive).
- reactive boundary 는 ADR-0001 의 컨벤션 그대로.
