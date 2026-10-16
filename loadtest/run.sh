#!/usr/bin/env bash
# 부하 테스트 실행(NFR-031). 사용: loadtest/run.sh [목표 TPS=93] [유지 시간=3m]
# 하는 일: 측정 환경을 새로 띄우고(앱 2 vCPU·2GiB 제한, 가짜 LLM, 빈 DB) → 시드 → docker stats 표본 → k6 →
# 결과를 loadtest/results/<시각>/에 저장한다(k6.txt, summary.json, stats.csv). 끝난 뒤에도 환경은 남겨 둔다:
#   docker compose -p smart-loadtest down -v
# k6는 호스트에서 돈다(앱 제한 밖). 올리는 시간(WARMUP, 기본 30s)은 합격 판정에서 빼고, 같은 DB를 쓰지 않게 매번 새로 시작한다.
# 모니터링(Prometheus·Grafana·exporter, NFR-036)도 함께 띄우고 k6 결과를 remote write로 보낸다 — 대시보드:
#   http://localhost:3000 (로그인 없음). MONITOR=0 loadtest/run.sh ...는 모니터링 없이 돈다(오버헤드 비교용).
set -euo pipefail
cd "$(dirname "$0")/.."

TPS=${1:-93}
DURATION=${2:-3m}
RUN_ID=$(date +%Y%m%d-%H%M%S)
OUT=loadtest/results/$RUN_ID
mkdir -p "$OUT"
DC="docker compose -p smart-loadtest -f compose.yaml -f loadtest/compose.yaml"

$DC down -v --remove-orphans >/dev/null 2>&1 || true
SERVICES="db server ai fake-llm"
[ "${MONITOR:-1}" = 1 ] && SERVICES="$SERVICES prometheus postgres-exporter cadvisor grafana"
$DC up -d --build $SERVICES
$DC --profile tools run --rm seed

# 컨테이너별 CPU%·메모리를 3초마다 적는다(NFR-036 모니터링 전의 간단한 표본).
(
  while true; do
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' $($DC ps -q) |
      sed "s/^/$(date +%T),/" >>"$OUT/stats.csv"
    sleep 3
  done
) &
STATS=$!
trap 'kill $STATS 2>/dev/null || true' EXIT

K6_OUT=()
if [ "${MONITOR:-1}" = 1 ]; then
  until curl -sf localhost:9090/-/ready >/dev/null; do sleep 1; done
  export K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write
  export K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=true # p95 게이지는 합칠 수 없어서 히스토그램으로 보낸다
  export K6_PROMETHEUS_RW_PUSH_INTERVAL=5s
  K6_OUT=(-o experimental-prometheus-rw)
  echo "Grafana: http://localhost:3000"
fi

set +e
k6 run ${K6_OUT[@]+"${K6_OUT[@]}"} -e TARGET_TPS="$TPS" -e DURATION="$DURATION" -e RUN_ID="$RUN_ID" \
  --summary-export "$OUT/summary.json" loadtest/k6/scenario.js 2>&1 | tee "$OUT/k6.txt"
CODE=${PIPESTATUS[0]}
echo "결과: $OUT (k6 종료 코드 $CODE — 0이면 합격 기준을 모두 통과)"
exit "$CODE"
