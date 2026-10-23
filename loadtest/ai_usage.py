"""AI 사용량 쿼리 성능 테스트(NFR-054). 사용량 테이블(ai_usage)이 커질 때 두 쿼리가 얼마나 느려지는지 재고 인덱스 후보를 비교한다.

- 한도 확인: 채팅·상세 보기마다 도는 "이 회원의 오늘(KST) 토큰 합계"(server의 AiUsageRepository.todayTokens와 같은 쿼리)
- 하루 집계: 관리자 화면(E-16)의 "그날 회원·모델별 합계"(AiUsageRepository.byUserAndModel과 같은 쿼리)
SIZES(기본 100만·1,000만)행을 최근 DAYS(30)일에 시간 순으로 넣는다(실제로도 시간 순으로 쌓인다). 회원은 USERS(10,000)명이고
사용량은 고르게 흩어지며, 모델은 gpt-5-nano가 98%·Claude가 2%다. 한도 확인은 회원을 바꿔 가며 CHECKS번, 하루 집계는 LISTS번 잰다.
기록 1건(INSERT) 시간도 함께 재서 인덱스가 쓰기를 얼마나 늦추는지 본다 — FastAPI는 LLM 호출이 끝날 때 동기로 쓴다.
loadtest/ai_usage.sh가 측정 환경(DB 2 vCPU·512MiB)에서 이 스크립트를 돌린다.
"""

import math
import os
import random
import re
import time
from datetime import datetime, timedelta, timezone

import psycopg

from app.store import ensure_schema

SIZES = [int(n) for n in os.getenv("SIZES", "1000000,10000000").split(",")]
USERS = int(os.getenv("USERS", "10000"))
DAYS = int(os.getenv("DAYS", "30"))
CHECKS = int(os.getenv("CHECKS", "300"))
LISTS = int(os.getenv("LISTS", "5"))
INSERTS = int(os.getenv("INSERTS", "300"))
KST = timezone(timedelta(hours=9))

# server의 AiUsageRepository 쿼리와 같다
LIMIT_SQL = (
    "SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM ai_usage "
    "WHERE user_id = %s AND created_at >= %s"
)
LIST_SQL = (
    "SELECT u.user_id, m.login_id, m.ai_blocked AS blocked, u.model, count(*) AS calls, "
    "count(*) FILTER (WHERE u.fallback) AS fallback_calls, "
    "sum(u.input_tokens) AS input_tokens, sum(u.output_tokens) AS output_tokens "
    "FROM ai_usage u JOIN member m ON m.id = u.user_id "
    "WHERE u.created_at >= %s AND u.created_at < %s "
    "GROUP BY u.user_id, m.login_id, m.ai_blocked, u.model"
)
INSERT_SQL = (
    "INSERT INTO ai_usage (user_id, model, input_tokens, output_tokens) "
    "VALUES (%s, 'probe', 1529, 550)"
)

# 비교할 인덱스. 이름 → (만드는 문). 채택한 것은 app/store.py의 SCHEMA에도 같은 정의로 있다.
CANDIDATES = {
    "(user_id, created_at)": "CREATE INDEX ai_usage_user_time ON ai_usage (user_id, created_at)",
    "(created_at)": "CREATE INDEX ai_usage_time ON ai_usage (created_at)",
}


def connect() -> psycopg.Connection:
    return psycopg.connect(
        host=os.getenv("POSTGRES_HOST", "localhost"),
        dbname=os.getenv("POSTGRES_DB", "smart"),
        user=os.getenv("POSTGRES_USER", "smart"),
        password=os.getenv("POSTGRES_PASSWORD", ""),
        autocommit=True,
    )


# 작은 수부터 세어 올림한 순번의 값(다른 측정 도구와 같은 방식)
def percentile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(q * len(ordered)) - 1)]


def seed_members(conn: psycopg.Connection) -> int:
    """측정용 회원 USERS명을 만들고 첫 회원 ID를 돌려준다. 이미 있으면 그대로 쓴다."""
    if not conn.execute("SELECT 1 FROM member WHERE login_id = 'usage000001'").fetchone():
        conn.execute(
            "INSERT INTO member (login_id, password_hash, name) "
            "SELECT 'usage' || lpad(n::text, 6, '0'), 'x', '사용' || n FROM generate_series(1, %s) n",
            (USERS,),
        )
    return conn.execute("SELECT id FROM member WHERE login_id = 'usage000001'").fetchone()[0]


def seed_usage(conn: psycopg.Connection, first_user: int, size: int) -> None:
    conn.execute("TRUNCATE ai_usage RESTART IDENTITY")
    span = DAYS * 86400
    conn.execute(
        "INSERT INTO ai_usage (created_at, user_id, model, input_tokens, output_tokens, fallback) "
        "SELECT now() - make_interval(secs => %(span)s) + make_interval(secs => g::float8 * %(span)s / %(n)s), "
        "%(first)s + floor(random() * %(users)s)::bigint, m.model, "
        "800 + floor(random() * 1400)::int, 200 + floor(random() * 700)::int, m.model <> 'gpt-5-nano' "
        "FROM generate_series(1, %(n)s) g "
        "CROSS JOIN LATERAL (SELECT CASE WHEN random() < 0.02 "
        "THEN 'claude-haiku-5-5' ELSE 'gpt-5-nano' END AS model) m",
        {"span": span, "n": size, "first": first_user, "users": USERS},
    )
    conn.execute("VACUUM ANALYZE ai_usage")


def timed(conn: psycopg.Connection, sql: str, params_list: list[tuple]) -> list[float]:
    millis = []
    for params in params_list:
        start = time.perf_counter()
        cursor = conn.execute(sql, params)
        if cursor.description:  # SELECT면 결과를 끝까지 읽는다
            cursor.fetchall()
        millis.append((time.perf_counter() - start) * 1000)
    return millis


# 실행 계획에서 ai_usage를 읽는 방식(예: Parallel Seq Scan, Index Only Scan using ...)
def plan(conn: psycopg.Connection, sql: str, params: tuple) -> str:
    lines = [row[0] for row in conn.execute("EXPLAIN " + sql, params).fetchall()]
    scans = [
        re.sub(r"\s+\(cost.*", "", line).strip(" ->")
        for line in lines
        if re.search(r"Scan .*ai_usage", line)
    ]
    return scans[0] if scans else "?"


def measure(conn: psycopg.Connection, first_user: int) -> dict:
    rng = random.Random(1)
    today = datetime.now(KST).replace(hour=0, minute=0, second=0, microsecond=0)
    users = [first_user + rng.randrange(USERS) for _ in range(CHECKS)]
    checks = [(user, today) for user in users]
    lists = [(today, today + timedelta(days=1))] * LISTS
    timed(conn, LIMIT_SQL, checks[:20])  # 데우기
    timed(conn, LIST_SQL, lists[:1])
    check_ms = timed(conn, LIMIT_SQL, checks)
    list_ms = timed(conn, LIST_SQL, lists)
    insert_ms = timed(conn, INSERT_SQL, [(u,) for u in users[:INSERTS]])
    conn.execute("DELETE FROM ai_usage WHERE model = 'probe'")
    return {
        "check": check_ms,
        "list": list_ms,
        "insert": insert_ms,
        "check_plan": plan(conn, LIMIT_SQL, checks[0]),
        "list_plan": plan(conn, LIST_SQL, lists[0]),
    }


def report(label: str, result: dict, index_mb: float | None) -> None:
    check, lst, ins = result["check"], result["list"], result["insert"]
    size = "-" if index_mb is None else f"{index_mb:.0f}MB"
    print(
        f"  {label:<24}{percentile(check, 0.5):>9.2f}{percentile(check, 0.95):>9.2f}{max(check):>9.2f}"
        f"{percentile(lst, 0.5):>10.0f}{max(lst):>8.0f}{percentile(ins, 0.5):>9.2f}{size:>8}"
    )
    print(f"      계획: 한도 확인 {result['check_plan']} / 하루 집계 {result['list_plan']}")


def main() -> None:
    conn = connect()
    ensure_schema(conn)
    first_user = seed_members(conn)
    print(
        f"회원 {USERS}명, 최근 {DAYS}일, 한도 확인 {CHECKS}회·하루 집계 {LISTS}회·기록 {INSERTS}회 "
        "(ms, 하루 집계는 오늘 하루치)"
    )
    for size in SIZES:
        conn.execute("DROP INDEX IF EXISTS ai_usage_user_time, ai_usage_time")
        seed_usage(conn, first_user, size)
        table_mb = conn.execute("SELECT pg_relation_size('ai_usage') / 1048576.0").fetchone()[0]
        today_rows = conn.execute(
            "SELECT count(*) FROM ai_usage WHERE created_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Seoul') "
            "AT TIME ZONE 'Asia/Seoul'"
        ).fetchone()[0]
        print(f"\nai_usage {size:,}행 (표 {table_mb:.0f}MB, 오늘 {today_rows:,}행)")
        print(
            f"  {'인덱스':<24}{'확인p50':>9}{'확인p95':>9}{'확인max':>9}{'집계p50':>10}{'집계max':>8}"
            f"{'기록p50':>9}{'크기':>8}"
        )
        report("없음", measure(conn, first_user), None)
        for label, ddl in CANDIDATES.items():
            conn.execute(ddl)
            conn.execute("ANALYZE ai_usage")
            name = re.search(r"INDEX (\w+)", ddl)[1]
            index_mb = conn.execute("SELECT pg_relation_size(%s) / 1048576.0", (name,)).fetchone()[
                0
            ]
            report(label, measure(conn, first_user), index_mb)
            conn.execute(f"DROP INDEX {name}")


if __name__ == "__main__":
    main()
