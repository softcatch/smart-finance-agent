#!/usr/bin/env bash
# AI 사용량 쿼리 성능 테스트(NFR-054). 사용: [SIZES=1000000,10000000] loadtest/ai_usage.sh
# db·server(Flyway)만 새로 띄우고 회원·사용량을 시드한 뒤, 한도 확인·하루 집계 쿼리를 인덱스 없이/있이 잰다(loadtest/ai_usage.py).
# 결과는 loadtest/results/ai-usage-<시각>/result.txt에 저장한다. 끝난 뒤 환경은 남겨 둔다:
#   docker compose -p smart-loadtest down -v
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=loadtest/results/ai-usage-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"
source loadtest/lib.sh # DC

$DC down -v --remove-orphans >/dev/null 2>&1 || true
$DC up -d --build db server
$DC --profile tools build -q seed >/dev/null # 시드 이미지를 현재 앱 코드로 다시 만든다(ai_usage 정의가 들어 있다)
$DC --profile tools run --rm -e SIZES="${SIZES:-1000000,10000000}" seed \
  uv run --no-sync python /loadtest/ai_usage.py | tee "$OUT/result.txt"
echo "결과: $OUT/result.txt"
