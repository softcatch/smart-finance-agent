#!/usr/bin/env bash
# 송금 내역 목록 성능 테스트(NFR-030). 사용: [SIZES=1000,10000,100000] loadtest/history.sh [MODE=cursor]
# db·server만 새로 띄우고 내역이 많은 계정(SIZES건)을 시드한 뒤 k6를 돌린다. 결과는 loadtest/results/<이름>/에 저장한다.
# REUSE=1이면 환경과 시드를 새로 만들지 않는다. MODE=offset|all은 변형 패치(loadtest/variants/transfer-history.patch)를 적용한 서버에만 있다. 끝난 뒤 환경은 남겨 둔다:
#   docker compose -p smart-loadtest down -v
set -euo pipefail
cd "$(dirname "$0")/.."

MODE=${1:-cursor}
SIZES=${SIZES:-1000,10000,100000}
export SEED_HEAVY=$SIZES SEED_DART=0
OUT=loadtest/results/history-$MODE-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"
source loadtest/lib.sh # DC

if [ "${REUSE:-0}" = 1 ]; then # 이미 띄운 환경과 시드를 그대로 쓴다(같은 데이터에서 MODE만 바꿔 비교할 때)
  $DC up -d db server
else
  $DC down -v --remove-orphans >/dev/null 2>&1 || true
  $DC up -d --build db server
  $DC --profile tools run --rm seed
fi

# 깊이별 커서 — 큰 내역 계정마다 최신순 10·50·90·99% 지점의 송금 ID
CURSORS=$PWD/$OUT/cursors.json
$DC exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At' >"$CURSORS" <<'SQL'
SELECT jsonb_object_agg(n, c) FROM (
  SELECT h.n, jsonb_object_agg(l.label, (
      SELECT x.id FROM (
        SELECT id, row_number() OVER (ORDER BY id DESC) AS rn FROM transfer
        WHERE from_account_number = h.acc OR to_account_number = h.acc) x
      WHERE x.rn = greatest(1, floor(h.n * l.p))::int)) AS c
  FROM (SELECT substring(m.login_id from 6)::int AS n, a.account_number AS acc
        FROM member m JOIN account a ON a.member_id = m.id WHERE m.login_id LIKE 'heavy%') h
  CROSS JOIN (VALUES ('p10', 0.1), ('p50', 0.5), ('p90', 0.9), ('p99', 0.99)) AS l(label, p)
  GROUP BY h.n) z;
SQL

set +e
k6 run -e MODE="$MODE" -e SIZES="$SIZES" -e CURSORS_FILE="$CURSORS" ${RATE:+-e RATE="$RATE"} ${DURATION:+-e DURATION="$DURATION"} \
  --summary-export "$OUT/summary.json" loadtest/k6/history.js 2>&1 | tee "$OUT/k6.txt"
CODE=${PIPESTATUS[0]}
echo "결과: $OUT (k6 종료 코드 $CODE — 0이면 p95 < 500ms를 모두 통과)"
exit "$CODE"
