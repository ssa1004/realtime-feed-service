# ADR-0005: context propagation (Reactor Context / CoroutineContext / MDC)

## 상태
적용

## 배경
요청 단위 컨텍스트 (traceId, tenantId, userId, locale) 가 여러 layer 를 넘어 흘러가야
한다. imperative 코드는 ThreadLocal 로 자연스럽게 흐르지만, reactive 와 coroutines 는
스레드를 자유롭게 바꾸므로 ThreadLocal 이 안 통한다.

세 가지 컨텍스트 메커니즘이 한 코드베이스에 공존한다:

1. **Reactor Context** — Reactor publisher 의 chain 을 따라 흐른다 (downstream → upstream
   방향, 즉 subscription 시점에서 업).
2. **CoroutineContext** — coroutine 의 child 로 흐른다 (parent → child).
3. **MDC** (SLF4J) — ThreadLocal. logback / log4j 가 자동으로 logging context 에 포함.

이 셋을 어떻게 통합하느냐가 logging / tracing 의 품질을 결정한다.

## 결정

### 1. 전역 컨텍스트 키 정의

```kotlin
object FeedContextKeys {
    const val TRACE_ID = "traceId"
    const val TENANT_ID = "tenantId"
    const val SUBJECT = "sub"        // JWT subject
}
```

### 2. WebFlux 진입 단계 — WebFilter 에서 Reactor Context 에 주입

```kotlin
class TraceWebFilter : WebFilter {
    override fun filter(exchange, chain) =
        chain.filter(exchange).contextWrite { ctx ->
            ctx.put(FeedContextKeys.TRACE_ID, exchange.request.id)
        }
}
```

### 3. coroutines 진입 — `coRouter` 가 자동으로 Reactor Context → CoroutineContext 변환

`kotlinx-coroutines-reactor` 의 `mono { }` / `coRouter { }` 는 Reactor Context 를
CoroutineContext 의 `ReactorContext` element 로 변환한다. coroutine 안에서:

```kotlin
suspend fun handle() {
    val ctx = currentCoroutineContext()[ReactorContext]?.context
    val traceId = ctx?.getOrEmpty<String>(FeedContextKeys.TRACE_ID)?.orElse("?")
}
```

### 4. MDC 동기화 — coroutine 진입 시 MDCContext 사용

```kotlin
withContext(MDCContext()) { ... }
```

또는 더 단순히 `kotlinx-coroutines-slf4j` 의 `MDCContext` element 를 entry coroutine
에 미리 넣어 두면 logger 가 자동으로 MDC 를 본다.

### 5. Micrometer Tracing 통합

Spring Boot 3.x 의 Micrometer Observation 은 자동으로 Reactor Context 에 traceId 를
넣는다 (via `Hooks.enableAutomaticContextPropagation()`). 본 repo 는 이 hook 을 enable
한다 (`RealtimeFeedApplication` 의 main 함수 시작에서).

```kotlin
fun main(args: Array<String>) {
    Hooks.enableAutomaticContextPropagation()
    runApplication<RealtimeFeedApplication>(*args)
}
```

## 결과
- 한 요청의 traceId 가 router → use case → adapter (Kafka producer / R2DBC) 까지 일관되게
  로그에 찍힌다.
- multi-tenant 시 tenantId 가 자동 흐름.
- (단점) `Hooks.enableAutomaticContextPropagation()` 은 약간의 오버헤드가 있다 (publisher
  생성 시 ThreadLocal snapshot). 트래픽이 매우 클 때 측정 필요.
- (단점) coroutine ↔ Reactor 변환 boundary 가 많으면 컨텍스트 누락 위험. boundary 마다
  unit test 로 traceId 가 살아 있는지 확인.

## 다시 검토할 시점
- distributed tracing 백엔드 (Tempo / Jaeger) 와 통합 시 — `commerce-ops` 의
  Tempo 와 연결되는지 확인.
- 새로운 컨텍스트 키 추가 시 — 키 이름과 lifecycle 을 본 ADR 에 갱신.
