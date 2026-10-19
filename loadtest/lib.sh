# run.sh·stress.sh가 같이 쓰는 함수. source해서 쓴다(저장소 루트에서).
DC="docker compose -p smart-loadtest -f compose.yaml -f loadtest/compose.yaml"

# 측정 환경을 새로 띄우고(빈 DB) 시드를 넣는다. MONITOR=0이면 모니터링(Prometheus·Grafana 등)을 띄우지 않는다.
bring_up() {
  $DC down -v --remove-orphans >/dev/null 2>&1 || true
  local services="db server ai fake-llm"
  [ "${MONITOR:-1}" = 1 ] && services="$services prometheus postgres-exporter cadvisor grafana"
  $DC up -d --build $services
  $DC --profile tools run --rm seed
}

# k6 결과를 Prometheus에 밀어 넣는 설정. K6_OUT 배열에 k6 옵션을 채운다(MONITOR=0이면 빈 배열).
k6_monitor() {
  K6_OUT=()
  if [ "${MONITOR:-1}" = 1 ]; then
    until curl -sf localhost:9090/-/ready >/dev/null; do sleep 1; done
    export K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write
    export K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=true # p95 게이지는 합칠 수 없어서 히스토그램으로 보낸다
    export K6_PROMETHEUS_RW_PUSH_INTERVAL=5s
    K6_OUT=(-o experimental-prometheus-rw)
    echo "Grafana: http://localhost:3000"
  fi
}
