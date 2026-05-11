# ADR-0008: 언제 reactive 를 쓰지 말아야 하나

## 상태
적용

## 배경
"reactive 가 무조건 빠르다" 는 흔한 오해다. 본 레포는 Kotlin coroutines + Spring WebFlux
를 의도적으로 선택했지만, 이는 "실시간 fan-out + WebSocket 다수 클라이언트 + 비동기 메시지
stream" 이라는 **본 도메인의 특성** 때문이다. 다른 도메인에서는 잘못된 선택일 수 있다.

본 ADR 은 "언제 reactive 를 쓰지 말아야 하는가" 의 판단 기준을 정리한다.

## 결정

### Reactive 를 *쓰지 말아야* 하는 시나리오

#### 1. CPU-bound 작업
ML 추론, 이미지 처리, 무거운 계산. reactive 의 이점은 *대기 시간을 다른 일로 채우는 것*.
CPU 가 100% 돌고 있으면 채울 시간이 없다. reactive 의 chain overhead 만 추가될 뿐.
- 대안: virtual thread + plain blocking, 또는 별도 worker 서비스 (예: gpu-job-orchestrator).

#### 2. 단순 CRUD + 낮은 concurrency
endpoint 수 ~수십, RPS ~수백, JPA 로 한 번에 한 행 갱신. Spring MVC + JPA + HikariCP
가 가장 단순하고 디버깅 쉬움. reactive 로 바꿔도 throughput 향상 없음 (DB connection 이
bottleneck).
- 대안: Spring MVC + JPA. 본 portfolio 의 다른 8 레포가 대부분 이 길을 고름.

#### 3. JPA / Hibernate 종속
JPA 의 lazy loading 이 핵심 가치인 경우 reactive 와 어울리지 않음. R2DBC 는 JPA 의
고급 기능 (caching, dirty checking, association mapping) 을 지원하지 않는다. reactive
때문에 도메인 모델을 anemic 으로 갈아엎느니 Spring MVC + JPA 가 옳다.
- 대안: Spring MVC + JPA. JPA 의 가치를 포기할 만한 reactive 의 이점이 있는지 측정 후 결정.

#### 4. transaction 이 복잡한 도메인
다단계 transaction, savepoint, isolation level 조정이 빈번한 도메인 (금융 거래 등).
R2DBC 의 transaction API 는 JDBC 보다 즉답성이 부족 (예: nested transaction, REQUIRES_NEW
의 보장 정도). 코드 복잡도 증가.
- 대안: Spring MVC + JDBC + JPA 의 `@Transactional`. resell-orderbook 의 saga 가 그
  사례.

#### 5. blocking 의존성이 필수
legacy SDK, Oracle JDBC (R2DBC 미지원 일부 기능), filesystem I/O 가 절대적으로 필요.
reactive 위에 boundedElastic 격리는 가능하지만 "왜 reactive?" 의 답이 약함.
- 대안: Spring MVC + virtual thread (Java 21 LTS 이상) — Loom 으로 거의 모든 blocking
  이 reactive 와 동등한 throughput 을 낸다. 코드는 imperative.

#### 6. 팀 / 운영자가 reactive 에 익숙하지 않음
reactive 의 디버깅, profiling, 경험적 직관 (operator 의 thread 행동, cancel 흐름) 은
학습 곡선이 가파르다. 팀이 imperative 만 안다면 reactive 는 *유지보수 부담*.
- 대안: imperative + 적절한 thread pool tuning. 충분히 빠른 경우가 많다.

### Reactive 를 *써야* 하는 시나리오 (본 레포의 정당화)

- 다수의 long-lived connection (WebSocket / SSE) — thread-per-connection 모델로는
  10,000 connection = 10,000 thread = 10GB stack. event-loop 면 4 thread 로 끝.
- 외부 메시지 stream (Kafka) 의 backpressure 를 도메인 layer 에 자연스럽게 노출.
- 한 publisher → 다수 subscriber 의 fan-out (multicast).

본 레포는 셋 다 해당해 reactive 가 명확히 의미가 있다. 결제 / 정산 같은 도메인이라면
portfolio 의 다른 레포처럼 imperative + JPA 를 골랐을 것이다.

## 결과
- 본 portfolio 안에서 reactive 가 정당화되는 도메인이 어느 것인지 명확히 분리.
- 신규 portfolio 레포를 만들 때 "이 도메인이 reactive 에 적합한가?" 를 본 ADR 로 빠르게 판단.
- (단점) "reactive 가 좋다" 라는 막연한 기대로 잘못된 도메인에 도입할 위험은 항상 남아 있다
  — 코드 리뷰 단계에서 본 ADR 의 기준을 다시 꺼내 적용한다.

## 다시 검토할 시점
- Java virtual thread 가 충분히 성숙해 reactive 의 가치 일부가 사라질 때 — long-lived
  connection 외의 시나리오는 모두 virtual thread + imperative 가 더 단순.
- R2DBC 가 JPA 의 일부 고급 기능을 reactive 로 제공하기 시작할 때 (가능성 낮음).
