#!/bin/bash
# ============================================================
# kafka-pipeline-lab 부하테스트 실행 스크립트
# ============================================================
# 사전 조건:
#   1. docker compose -f docker/kafka-cluster-compose.yml up -d
#   2. docker compose -f docker/monitoring-compose.yml up -d
#   3. ./gradlew bootRun (Spring Boot 앱 실행)
#   4. k6 설치: brew install k6
#
# Grafana 대시보드: http://localhost:3000 (admin/admin)
# Prometheus: http://localhost:9090
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_DIR="${SCRIPT_DIR}/results"
mkdir -p "$RESULTS_DIR"

echo "=== 부하테스트 시작 ==="
echo "결과 저장: ${RESULTS_DIR}"
echo ""

# 사전 체크
echo "[체크] Spring Boot 앱 상태..."
if ! curl -s http://localhost:8085/actuator/health | grep -q "UP"; then
    echo "❌ Spring Boot 앱이 실행 중이 아닙니다. ./gradlew bootRun 실행 후 다시 시도하세요."
    exit 1
fi
echo "✅ Spring Boot 앱 정상"

echo ""
echo "────────────────────────────────────────"
echo "시나리오 1: Outbox Baseline (3분 30초)"
echo "────────────────────────────────────────"
k6 run "${SCRIPT_DIR}/01-outbox-baseline.js"

echo ""
echo "────────────────────────────────────────"
echo "시나리오 2: Coupon Concurrency (1분 10초)"
echo "────────────────────────────────────────"
k6 run "${SCRIPT_DIR}/02-coupon-concurrency.js"

echo ""
echo "────────────────────────────────────────"
echo "시나리오 3: Spike Test (4분 30초)"
echo "────────────────────────────────────────"
k6 run "${SCRIPT_DIR}/03-spike-test.js"

echo ""
echo "=== 전체 부하테스트 완료 ==="
echo "결과 파일:"
ls -la "${RESULTS_DIR}/"
echo ""
echo "Grafana 대시보드에서 시각화된 결과를 확인하세요: http://localhost:3000"
