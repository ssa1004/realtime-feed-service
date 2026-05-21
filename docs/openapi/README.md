# OpenAPI spec

`realtime-feed-service` 의 REST API 를 OpenAPI 3 spec 으로 build-time export 한다.

## 무엇이 들어가나

- `realtime-feed-service.yaml` — 빌드 시 생성되는 OpenAPI 3 문서. 외부 참조 / SDK codegen 의 단일 진실값.
  - 최근 feed 조회 (`GET /api/v1/feed/{skuId}/recent`)
  - window 통계 (`GET /api/v1/feed/{skuId}/window`)

이 서비스는 Spring WebFlux 의 functional routing (`coRouter { }`) 을 쓴다.
`@RestController` 기반이 아니므로 springdoc 은 `springdoc-openapi-starter-webflux-api`
로 `/v3/api-docs` 를 노출한다. functional route 의 상세 스키마는 `@RouterOperations`
를 붙여야 더 풍부해지지만, 현재는 endpoint 변경 없이 기본 노출만 한다.

WebSocket / SSE 스트리밍 endpoint 는 OpenAPI 대상이 아니다 (REST 만 spec 에 포함).

> 이 디렉토리의 `*.yaml` 은 CI 에서 생성·갱신된다. 로컬에서 수기로 편집하지 않는다.

## 생성 방법

`org.springdoc.openapi-gradle-plugin` 을 `feed-bootstrap` 모듈에 적용했다.
`generateOpenApiDocs` 태스크가 앱을 부팅한 뒤 `/v3/api-docs.yaml` 을 받아
`docs/openapi/realtime-feed-service.yaml` 로 저장한다.

```bash
./gradlew :feed-bootstrap:generateOpenApiDocs
```

앱 부팅에 Postgres(R2DBC) / Kafka / Redis 가 필요하므로, 의존 인프라를 먼저 띄워야 한다.
CI 에서는 service container 를 띄운 잡에서 위 태스크를 실행해 산출된 yaml 을
commit 하거나 아티팩트로 업로드한다.

## 보는 법

- Redoc — `npx @redocly/cli preview-docs docs/openapi/realtime-feed-service.yaml`
- 통합 뷰어 — profile repo `ssa1004/ssa1004` 의 `docs/api/index.html` (11 service spec 드롭다운)
