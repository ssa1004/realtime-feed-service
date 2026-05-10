#!/usr/bin/env bash
# realtime-feed-service 의 통합 시연.
#
# 시나리오:
#   1) docker compose 가 이미 떠 있다고 가정 (infrastructure/docker-compose.yml)
#   2) /actuator/health 가 UP 일 때까지 대기
#   3) Kafka topic `market.tradematched` 에 mock 이벤트 produce
#   4) /api/v1/feed/{sku}/recent 로 catch-up 조회
#   5) /api/v1/feed/{sku}/window 로 윈도우 통계 조회
#   6) SSE / WebSocket 시연 가이드 출력 (자동 연결 X — 사용자 직접 확인)
#
# dev 프로필이라 JWT 인증은 우회.
set -euo pipefail

APP_PORT=${APP_PORT:-8088}
KAFKA_PORT=${KAFKA_PORT:-19092}
SKU=${SKU:-NIKE-DUNK-LOW-001}
BASE="http://localhost:${APP_PORT}"

echo "==> $BASE 의 health 가 UP 이 될 때까지 대기 (최대 60s)"
for i in $(seq 1 30); do
    if curl -sf "$BASE/actuator/health" > /dev/null 2>&1; then
        echo "    UP"
        break
    fi
    sleep 2
done

echo
echo "==> Kafka topic market.tradematched 에 mock 체결 이벤트 3건 produce"
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
for seq in 1 2 3; do
    PRICE=$((150000 + RANDOM % 30000))
    PAYLOAD=$(cat <<EOF
{"skuId":"${SKU}","tradeId":"$(uuidgen | tr 'A-Z' 'a-z')","price":${PRICE},"quantity":1,"occurredAt":"${NOW}","sequence":${seq}}
EOF
)
    echo "    seq=${seq} price=${PRICE}"
    docker exec -i $(docker ps -q -f "ancestor=bitnami/kafka:3.7" | head -1) \
        kafka-console-producer.sh \
        --bootstrap-server localhost:9092 \
        --topic market.tradematched <<<"${PAYLOAD}"
done

# Kafka consumer 가 한 번 catch-up 할 시간
sleep 2

echo
echo "==> 최근 catch-up 조회"
curl -sf "$BASE/api/v1/feed/${SKU}/recent?limit=10" | head -c 500 ; echo

echo
echo "==> 윈도우 통계 (5분)"
curl -sf "$BASE/api/v1/feed/${SKU}/window?minutes=5" | head -c 300 ; echo

echo
echo "==> SSE / WebSocket 시연 가이드"
echo "    (브라우저)  http://localhost:${APP_PORT}/api/v1/feed/${SKU}/stream"
echo "    (curl SSE)  curl -N $BASE/api/v1/feed/${SKU}/stream"
echo "    (websocat)  websocat ws://localhost:${APP_PORT}/ws/feed/${SKU}"
echo
echo "다 보고 나면:"
echo "    docker compose -p feed-integration -f infrastructure/docker-compose.yml down -v"
