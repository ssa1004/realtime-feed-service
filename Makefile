# realtime-feed-service — 자주 쓰는 명령 단일 진입점
#
#   make up        통합 인프라(postgres/redis/kafka + 앱) 기동
#   make ps        컨테이너 상태
#   make logs      인프라 로그 follow
#   make demo      통합 데모 (mock 체결 produce → catch-up / window 조회)
#   make down      인프라 정지 (볼륨 유지)
#   make clean     인프라 정지 + 볼륨 삭제 (옛 데이터 제거)
#   make build     전체 gradle 빌드
#   make test      전체 테스트 (./gradlew check)
#   make run       앱 호스트 실행 (feed-bootstrap, :8080)
#   make load      k6 부하 5종 (통합 환경이 떠 있어야 함)
#
# compose 는 -p feed-integration 으로 project 이름을 고정 — 다른 portfolio 레포의 compose 와
# 같은 머신에서 동시에 띄울 때 이름/포트 충돌을 피한다.
# 포트: 컨테이너 앱은 호스트 8088 로 매핑(compose), 호스트에서 `make run` 하면 앱 자체 포트 8080.
#
# `make run` 으로 호스트에서 띄운 앱은 Kafka 를 localhost:9092 로 붙는다
# (infrastructure/docker-compose.yml 의 EXTERNAL listener). 컨테이너 안 앱은 kafka:29092.

COMPOSE := docker compose -p feed-integration -f infrastructure/docker-compose.yml
GRADLE  := ./gradlew

.DEFAULT_GOAL := help
.PHONY: help up ps logs demo down clean build test run load urls

help: ## 이 도움말
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
	  | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

up: ## 통합 인프라 기동 (postgres/redis/kafka + 앱 컨테이너, --build)
	$(COMPOSE) up -d --build
	@echo "→ app http://localhost:8088 · health /actuator/health"

ps: ## 컨테이너 상태
	$(COMPOSE) ps

logs: ## 인프라 로그 follow
	$(COMPOSE) logs -f --tail=100

demo: ## 통합 데모 (mock 체결 produce → catch-up/window 조회 → SSE/WS 가이드)
	./scripts/integration-demo.sh

down: ## 인프라 정지 (볼륨 유지)
	$(COMPOSE) down

clean: ## 인프라 정지 + 볼륨 삭제 (다음 기동 시 깨끗한 상태)
	$(COMPOSE) down -v

build: ## 전체 gradle 빌드 (테스트 제외)
	$(GRADLE) build -x test

test: ## 전체 테스트 (빌드 + 모든 모듈 test)
	$(GRADLE) check

run: ## 앱 호스트 실행 (feed-bootstrap, :8080) — Kafka 는 localhost:9092
	$(GRADLE) :feed-bootstrap:bootRun

load: ## k6 부하 5종 (WS fan-out / SSE / recent / window / backpressure)
	./scripts/run-load.sh

urls: ## 주요 엔드포인트 (compose 통합 환경 기준 — 호스트 8088)
	@echo "app health   http://localhost:8088/actuator/health"
	@echo "SSE          http://localhost:8088/api/v1/feed/{sku}/stream"
	@echo "WebSocket    ws://localhost:8088/ws/feed/{sku}"
	@echo "prometheus   http://localhost:8088/actuator/prometheus"
