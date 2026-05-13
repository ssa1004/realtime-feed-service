#!/usr/bin/env bash
# k6 부하 시나리오 5종 일괄 실행.
#
# 단계:
#   1) 본 앱 healthcheck (없으면 docker-compose 를 띄우라고 안내)
#   2) k6 실행 경로 결정 — 우선 로컬 k6, 없으면 docker run
#   3) WebSocket fan-out → SSE → REST recent → REST window → backpressure 순서
#   4) 각 결과는 build/k6-reports/{scenario}.json 에 떨군다
#
# 환경변수:
#   BASE_URL / WS_URL — 시나리오의 endpoint base. 기본은 docker-compose 의 8088
#   K6_TOKEN          — prod 프로필에서만 의미. dev 우회 시 빈 값
#   K6_EXPECTED_RATE  — backpressure 의 upstream produce 속도 (evt/s)

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SCENARIO_DIR="${ROOT_DIR}/load/k6/scenarios"
REPORT_DIR="${ROOT_DIR}/build/k6-reports"
mkdir -p "$REPORT_DIR"

BASE_URL="${BASE_URL:-http://localhost:8088}"
WS_URL="${WS_URL:-ws://localhost:8088}"
K6_TOKEN="${K6_TOKEN:-}"
K6_EXPECTED_RATE="${K6_EXPECTED_RATE:-10}"

echo "==> base url: $BASE_URL"
echo "==> ws url:   $WS_URL"

# 1) healthcheck
echo
echo "==> health 확인 ($BASE_URL/actuator/health)"
if ! curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1; then
    cat <<EOF
ERROR: $BASE_URL 가 응답하지 않습니다.

먼저 통합 환경을 띄워야 합니다:
    docker compose -p feed-integration -f infrastructure/docker-compose.yml up -d --build
    ./scripts/integration-demo.sh   # 더미 이벤트 1회

또는 BASE_URL 를 다른 host 로 덮어쓰세요 (예: BASE_URL=http://staging:8080).
EOF
    exit 1
fi
echo "    UP"

# 2) k6 실행 경로
if command -v k6 >/dev/null 2>&1; then
    K6_EXEC=("k6")
    echo "==> 로컬 k6 사용 ($(k6 version | head -1))"
elif command -v docker >/dev/null 2>&1; then
    # docker 안에서 호스트의 localhost 를 보려면 host.docker.internal 또는 --network host
    # macOS 의 docker desktop 은 host.docker.internal 이 자동.
    # 사용자가 호스트의 8088 을 가리키면 컨테이너에서는 host.docker.internal:8088 로 치환.
    if [[ "$BASE_URL" == *"localhost"* || "$BASE_URL" == *"127.0.0.1"* ]]; then
        BASE_URL_DOCKER="${BASE_URL//localhost/host.docker.internal}"
        BASE_URL_DOCKER="${BASE_URL_DOCKER//127.0.0.1/host.docker.internal}"
        WS_URL_DOCKER="${WS_URL//localhost/host.docker.internal}"
        WS_URL_DOCKER="${WS_URL_DOCKER//127.0.0.1/host.docker.internal}"
    else
        BASE_URL_DOCKER="$BASE_URL"
        WS_URL_DOCKER="$WS_URL"
    fi
    K6_EXEC=(docker run --rm -i \
        -v "${ROOT_DIR}/load/k6:/scripts:ro" \
        -e "BASE_URL=${BASE_URL_DOCKER}" \
        -e "WS_URL=${WS_URL_DOCKER}" \
        -e "K6_TOKEN=${K6_TOKEN}" \
        -e "K6_EXPECTED_RATE=${K6_EXPECTED_RATE}" \
        grafana/k6:0.50.0)
    SCRIPT_PREFIX="/scripts/scenarios"
    echo "==> docker run grafana/k6 사용"
else
    echo "ERROR: k6 도 docker 도 없습니다. brew install k6 또는 docker 설치 후 다시 시도하세요." >&2
    exit 1
fi

# 3) 시나리오 실행 — 한 단계 실패해도 다음 단계는 진행 (set +e 부분 한정)
run_scenario() {
    local name="$1"
    local file="$2"

    echo
    echo "==> [$name] start ($(date +%H:%M:%S))"
    local out="${REPORT_DIR}/${name}.json"

    if [[ "${K6_EXEC[0]}" == "k6" ]]; then
        export BASE_URL WS_URL K6_TOKEN K6_EXPECTED_RATE
        set +e
        "${K6_EXEC[@]}" run --summary-export="$out" "$file"
        local rc=$?
        set -e
    else
        local docker_file="${SCRIPT_PREFIX}/$(basename "$file")"
        # docker 경로에서는 summary-export 경로도 컨테이너 내부로 매핑해야 한다.
        set +e
        "${K6_EXEC[@]}" run --summary-export="/scripts/${name}.summary.json" "$docker_file"
        local rc=$?
        set -e
        # 호스트에서 결과가 보이도록 마운트 디렉토리에서 build 디렉토리로 옮긴다.
        if [[ -f "${ROOT_DIR}/load/k6/${name}.summary.json" ]]; then
            mv "${ROOT_DIR}/load/k6/${name}.summary.json" "$out"
        fi
    fi

    if [[ $rc -eq 0 ]]; then
        echo "==> [$name] PASSED (report: $out)"
    else
        echo "==> [$name] FAILED rc=$rc (report: $out)"
    fi
}

# WS → SSE → REST → backpressure 순서 — connection-heavy 시나리오를 먼저
run_scenario "websocket-fanout" "${SCENARIO_DIR}/websocket-fanout.js"
run_scenario "sse-stream"       "${SCENARIO_DIR}/sse-stream.js"
run_scenario "rest-recent"      "${SCENARIO_DIR}/rest-recent.js"
run_scenario "rest-window"      "${SCENARIO_DIR}/rest-window.js"
run_scenario "backpressure"     "${SCENARIO_DIR}/backpressure.js"

echo
echo "==> 모든 시나리오 종료. 리포트: $REPORT_DIR"
ls -lah "$REPORT_DIR" 2>/dev/null || true
