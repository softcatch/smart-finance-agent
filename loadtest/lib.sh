# run.sh·stress.sh·history.sh가 같이 쓰는 설정과 함수. source해서 쓴다(저장소 루트에서).
# REAL_LLM=1: 가짜 LLM 대신 실제 OpenAI를 부른다(NFR-035, 돈이 든다). .env의 OPENAI_API_KEY를 쓴다.
# DART_FROM_DEV=1: 합성 DART 데이터 대신 개발 DB(smart-finance-agent-db-1)의 실데이터를 복사한다.
DC="docker compose -p smart-loadtest -f compose.yaml -f loadtest/compose.yaml"
if [ "${REAL_LLM:-0}" = 1 ]; then DC="$DC -f loadtest/compose.real-llm.yaml"; fi

# 개발 DB의 DART 테이블 데이터를 측정용 DB로 옮긴다. 개발 DB가 꺼져 있었다면 잠깐 켰다 다시 끈다.
copy_dart_from_dev() {
  local dev=smart-finance-agent-db-1 started=0
  if [ "$(docker inspect -f '{{.State.Running}}' $dev)" != true ]; then docker start $dev >/dev/null; started=1; fi
  until docker exec $dev sh -c 'pg_isready -q -U "$POSTGRES_USER"'; do sleep 1; done
  docker exec $dev sh -c 'pg_dump --data-only -U "$POSTGRES_USER" -d "$POSTGRES_DB" -t company -t financial -t business_chunk' |
    $DC exec -T db sh -c 'psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null'
  if [ "$started" = 1 ]; then docker stop $dev >/dev/null; fi
  $DC exec -T db sh -c 'psql -Atq -U "$POSTGRES_USER" -d "$POSTGRES_DB"' <<'SQL'
ANALYZE company, financial, business_chunk;
SELECT 'DART 실데이터: 회사 ' || (SELECT count(*) FROM company) || '곳, 사업의 내용 청크 ' || (SELECT count(*) FROM business_chunk) || '개';
SQL
}

# 측정 환경을 새로 띄우고(빈 DB) 시드를 넣는다. MONITOR=0이면 모니터링(Prometheus·Grafana 등)을 띄우지 않는다.
bring_up() {
  $DC down -v --remove-orphans >/dev/null 2>&1 || true
  local services="db server ai"
  [ "${REAL_LLM:-0}" = 1 ] || services="$services fake-llm"
  [ "${MONITOR:-1}" = 1 ] && services="$services prometheus postgres-exporter cadvisor grafana"
  $DC up -d --build $services
  if [ "${DART_FROM_DEV:-0}" = 1 ]; then export SEED_DART=0; fi
  $DC --profile tools run --rm seed
  if [ "${DART_FROM_DEV:-0}" = 1 ]; then copy_dart_from_dev; fi
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
