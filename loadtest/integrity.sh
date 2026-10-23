#!/usr/bin/env bash
# 정합성 테스트(NFR-034). 사용: [ROUNDS=100 PARALLEL=10] loadtest/integrity.sh [MODE=main]
# db·server를 새로 띄우고 시드한 뒤, 송금 확정에 동시 요청 두 가지(같은 요청ID 연타, 같은 계좌 동시 출금)를 건다.
# 끝나면 SQL로 판정한다: 전체 잔액 합계 불변 / 중복 확정 0 / 음수 잔액 0 / 알림 행 수 = 확정 송금 × 2(FR-024). 위반이 있으면 종료 코드 1.
# MODE: main(현재 구현, 원자적 UPDATE) | none(잠금 없음, 기준선) | lock(FOR UPDATE) | version(@Version). main 외에는 변형 패치가 필요하다.
#   none·lock: git apply loadtest/variants/transfer-confirm.patch
#   version:   위 패치 + git apply loadtest/variants/transfer-confirm-version.patch
# 패치는 측정 브랜치에서만 적용하고 끝나면 되돌린다(git apply -R). 결과는 loadtest/results/integrity-<MODE>-<시각>/에 저장한다.
set -euo pipefail
cd "$(dirname "$0")/.."

MODE=${1:-main}
ROUNDS=${ROUNDS:-100}
PARALLEL=${PARALLEL:-10}
BALANCE=${BALANCE:-100000} # 동시 출금 계좌의 시작 잔액(요청 금액 3만원 → 3건만 나가야 한다)
SRC=server/src/main/java/com/softcatch/smart
die() { echo "$1" >&2; exit 2; }
variant() { grep -q confirmVariant $SRC/transfer/TransferService.java; }
versioned() { grep -q '@Version' $SRC/account/Account.java; }
case $MODE in
  main) ;;
  none | lock) variant || die "변형 패치를 먼저 적용한다"; ! versioned || die "@Version 패치를 되돌린다(잠금 없음·FOR UPDATE에 섞인다)" ;;
  version) { variant && versioned; } || die "transfer-confirm.patch와 transfer-confirm-version.patch를 모두 적용한다" ;;
  *) die "MODE는 main | none | lock | version" ;;
esac
export TRANSFER_CONFIRM_MODE=$MODE
[ "$MODE" = version ] && TRANSFER_CONFIRM_MODE=none # @Version 패치 위에서 잠금 없는 확정을 돌린다

export SEED_USERS=$((ROUNDS * (3 + PARALLEL))) SEED_DART=0 # 회원 번호 배치는 k6/integrity.js 머리말
OUT=loadtest/results/integrity-$MODE-$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT"
source loadtest/lib.sh # DC

$DC down -v --remove-orphans >/dev/null 2>&1 || true
$DC up -d --build db server
$DC --profile tools run --rm seed
sql() { $DC exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At'; }

# 동시 출금 계좌(보내는 쪽 ROUNDS+1…2*ROUNDS)의 잔액을 맞추고, 시작 전 합계와 마지막 송금 ID를 적어 둔다.
BEFORE=$(sql <<SQL
UPDATE account SET balance = $BALANCE
 WHERE account_number BETWEEN '$((100000000000 + ROUNDS + 1))' AND '$((100000000000 + 2 * ROUNDS))';
SELECT (SELECT sum(balance) FROM account) || ' ' || (SELECT coalesce(max(id), 0) FROM transfer);
SQL
)
BEFORE=${BEFORE##*$'\n'} # UPDATE 결과 줄 뒤의 마지막 줄만
read -r SUM_BEFORE LAST_TRANSFER <<<"$BEFORE"

for scenario in dup overdraw; do
  k6 run -e SCENARIO=$scenario -e ROUNDS="$ROUNDS" -e PARALLEL="$PARALLEL" -e BALANCE="$BALANCE" \
    --summary-export "$OUT/$scenario.json" loadtest/k6/integrity.js 2>&1 | tee "$OUT/$scenario.txt"
done

sql >"$OUT/checks.json" <<SQL
SELECT json_build_object(
  'mode', '$MODE',
  'sum_diff', (SELECT sum(balance) FROM account) - $SUM_BEFORE,
  'duplicates', (SELECT count(*) FROM transfer WHERE id > $LAST_TRANSFER) - (SELECT count(*) FROM transfer_request WHERE used),
  'negative', (SELECT count(*) FROM account WHERE balance < 0),
  'notification_diff', (SELECT count(*) FROM transfer WHERE id > $LAST_TRANSFER) * 2
                       - (SELECT count(*) FROM notification WHERE transfer_id > $LAST_TRANSFER));
SQL
python3 ai/loadtest/report.py integrity "$OUT" | tee "$OUT/report.md"
echo "결과: $OUT"
python3 -c 'import json,sys; c=json.load(open(sys.argv[1])); sys.exit(any(c[k] for k in ("sum_diff","duplicates","negative","notification_diff")))' "$OUT/checks.json"
