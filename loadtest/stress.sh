#!/usr/bin/env bash
# 스트레스 테스트(NFR-032). 사용: [HOLD=60s WARMUP=15s] loadtest/stress.sh ["5 10 15 20 25 30 40 50 70 93 186 372 652"]
# 같은 환경에서 TPS를 단계적으로 올리며 단계마다 k6를 따로 돌린다(판정하지 않고 요청 이름별 지표를 남긴다). 단계가 끝나면
# 앞 단계가 밀어 둔 요청이 다 끝날 때까지(FastAPI·Spring이 한가해질 때까지) 기다려 다음 단계를 흐리지 않게 한다.
# 마지막 652는 DAU 전원 동시(명세서의 극단 상한, 부하 목표 아님)라서 버티는지만 본다. 끝나면 첫 단계 TPS를 한 번 더 돌려
# 회복을 확인하고, 컨테이너가 재시작·메모리 부족으로 죽었는지 적는다.
# 결과: loadtest/results/stress-<시각>/{step-<TPS>,recovery-<TPS>}.json, containers.txt, report.md(표와 한계 TPS).
set -euo pipefail
cd "$(dirname "$0")/.."
[ "${MONITOR:-1}" = 1 ] || { echo "스트레스는 MONITOR=1이 필요하다(단계 사이 대기에 Prometheus를 쓴다)"; exit 1; }
ulimit -n 65536 2>/dev/null || true # VU가 수천 개면 연결도 그만큼 열린다

STEPS=${1:-"5 10 15 20 25 30 40 50 70 93 186 372 652"}
HOLD=${HOLD:-60s}
WARMUP=${WARMUP:-15s}
OUT=loadtest/results/stress-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"
source loadtest/lib.sh

bring_up
k6_monitor
echo "{\"hold\": ${HOLD%s}, \"warmup\": ${WARMUP%s}}" >"$OUT/meta.json"

# FastAPI 처리 중 요청 + Tomcat 일하는 스레드가 3 이하(한가할 때 1)가 될 때까지, 최대 5분.
wait_idle() {
  local start=$SECONDS busy=99
  for _ in $(seq 1 60); do
    busy=$(curl -s -G localhost:9090/api/v1/query --data-urlencode \
      'query=(sum(http_requests_in_flight) or vector(0)) + (sum(tomcat_threads_busy_threads) or vector(0))' |
      python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["result"][0]["value"][1])' 2>/dev/null || echo 99)
    python3 -c "import sys; sys.exit(0 if float('$busy') <= 3 else 1)" && break
    sleep 5
  done
  echo "   비우는 데 $((SECONDS - start))초 (남은 처리 $busy)"
}

step() { # <TPS> <결과 파일 이름>
  echo "== $1 TPS (유지 $HOLD) → $2"
  k6 run ${K6_OUT[@]+"${K6_OUT[@]}"} -e JUDGE=0 -e TARGET_TPS="$1" -e DURATION="$HOLD" -e WARMUP="$WARMUP" \
    -e RUN_ID="$2" --summary-export "$OUT/$2.json" loadtest/k6/scenario.js >"$OUT/$2.txt" 2>&1 || true
  wait_idle
}

for tps in $STEPS; do step "$tps" "step-$tps"; done
first=${STEPS%% *}
step "$first" "recovery-$first"
docker inspect -f '{{.Name}} 재시작 {{.RestartCount}}회, 메모리 부족 종료 {{.State.OOMKilled}}' \
  $($DC ps -q server ai db) | tee "$OUT/containers.txt"
python3 ai/loadtest/report.py stress "$OUT" | tee "$OUT/report.md"
