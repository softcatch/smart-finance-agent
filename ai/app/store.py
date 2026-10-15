import psycopg

# 이 테이블은 FastAPI만 읽고 쓴다(재무 수치는 파이썬 계산 코드 — 개요 3절). Flyway와 엮지 않고 여기서 만든다.
SCHEMA = """
CREATE TABLE IF NOT EXISTS company (
    stock_code  VARCHAR(6)   PRIMARY KEY,
    corp_code   VARCHAR(8)   NOT NULL,
    name        VARCHAR(100) NOT NULL
);

-- 연결(CFS)·별도(OFS)를 모두 저장하고 어느 쪽을 쓸지는 조회 때 정한다.
-- 같은 키로 다시 넣으면 덮어쓴다 — 정정 공시 반영(FR-022).
CREATE TABLE IF NOT EXISTS financial (
    stock_code   VARCHAR(6)  NOT NULL,
    bsns_year    SMALLINT    NOT NULL,
    reprt_code   VARCHAR(5)  NOT NULL,
    fs_div       VARCHAR(3)  NOT NULL,
    account_nm   VARCHAR(20) NOT NULL,
    amount       BIGINT,
    add_amount   BIGINT,
    prev_amount  BIGINT,
    rcept_no     VARCHAR(14),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (stock_code, bsns_year, reprt_code, fs_div, account_nm)
);
"""


def ensure_schema(conn: psycopg.Connection) -> None:
    conn.execute(SCHEMA)
    conn.commit()


def upsert_companies(conn: psycopg.Connection, companies: list[tuple[str, str, str]]) -> None:
    with conn.cursor() as cur:
        cur.executemany(
            "INSERT INTO company (stock_code, corp_code, name) VALUES (%s, %s, %s) "
            "ON CONFLICT (stock_code) DO UPDATE "
            "SET corp_code = EXCLUDED.corp_code, name = EXCLUDED.name",
            companies,
        )
    conn.commit()


# 금액 값은 당기(분기)·누적·전기(전년 동기). 키가 같으면 새 값으로 덮어쓴다.
def upsert_financials(conn: psycopg.Connection, rows: list[dict]) -> int:
    with conn.cursor() as cur:
        cur.executemany(
            "INSERT INTO financial (stock_code, bsns_year, reprt_code, fs_div, account_nm, "
            "amount, add_amount, prev_amount, rcept_no) "
            "VALUES (%(stock_code)s, %(bsns_year)s, %(reprt_code)s, %(fs_div)s, %(account_nm)s, "
            "%(amount)s, %(add_amount)s, %(prev_amount)s, %(rcept_no)s) "
            "ON CONFLICT (stock_code, bsns_year, reprt_code, fs_div, account_nm) DO UPDATE "
            "SET amount = EXCLUDED.amount, add_amount = EXCLUDED.add_amount, "
            "prev_amount = EXCLUDED.prev_amount, rcept_no = EXCLUDED.rcept_no, updated_at = now()",
            rows,
        )
    conn.commit()
    return len(rows)
