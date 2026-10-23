import psycopg
import psycopg.rows
from psycopg.types.json import Jsonb

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
    amount       BIGINT,  -- 당기. 1분기·반기·3분기는 해당 분기 단독, 사업보고서는 연간
    add_amount   BIGINT,  -- 당기 누적(사업보고서는 없음)
    prev_amount  BIGINT,  -- 전기. 분기 보고서는 전년 동기, 사업보고서는 전년 연간
    rcept_no     VARCHAR(14),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (stock_code, bsns_year, reprt_code, fs_div, account_nm)
);

CREATE EXTENSION IF NOT EXISTS vector;

-- 사업의 내용(FR-021). 회사별 최신 보고서의 청크만 둔다: 새 접수번호가 오면 회사 단위로 통째로 바꾼다.
-- 벡터 인덱스는 없다 — 검색은 회사 하나로 좁힌 뒤 수십~수백 행만 훑는다. 임베딩 모델을 바꾸면 차원도 바꾼다.
CREATE TABLE IF NOT EXISTS business_chunk (
    stock_code  VARCHAR(6)   NOT NULL,
    seq         INTEGER      NOT NULL,
    rcept_no    VARCHAR(14)  NOT NULL,
    report_nm   VARCHAR(100) NOT NULL,
    section     TEXT         NOT NULL,  -- 소제목 경로(출처 표시)
    content     TEXT         NOT NULL,
    embedding   vector(1536) NOT NULL,
    PRIMARY KEY (stock_code, seq)
);

-- 채팅 한 턴의 판정 코드와 노드별 소요 시간(NFR-026). 메시지 본문은 남기지 않는다.
CREATE TABLE IF NOT EXISTS chat_log (
    id          BIGSERIAL    PRIMARY KEY,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    thread_id   TEXT         NOT NULL,
    guard       VARCHAR(20)  NOT NULL,
    node_ms     JSONB        NOT NULL  -- 노드 이름 → 걸린 시간(ms)
);

-- LLM 호출 한 번의 사용량(NFR-054). 호출마다 한 줄이고 메시지 본문은 남기지 않는다.
-- 하루 한도(오늘 KST 합계)와 관리자 화면(날짜별·사용자별)이 읽는다. 회원은 Spring 소유라 FK를 두지 않는다.
CREATE TABLE IF NOT EXISTS ai_usage (
    id             BIGSERIAL    PRIMARY KEY,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    user_id        BIGINT       NOT NULL,  -- 회원 ID(JWT의 sub)
    model          TEXT         NOT NULL,
    input_tokens   INTEGER      NOT NULL,
    output_tokens  INTEGER      NOT NULL,  -- 추론 토큰은 출력에 든다
    fallback       BOOLEAN      NOT NULL DEFAULT FALSE  -- 장애 대체(Claude)로 답한 호출(NFR-056)
);

-- 하루 한도 확인(회원의 오늘 합계)이 채팅·상세 보기마다 읽는다. 없으면 표를 처음부터 훑어서 행이 늘수록 느려진다(개요 5절 실측).
CREATE INDEX IF NOT EXISTS ai_usage_user_time ON ai_usage (user_id, created_at);

-- 상세 보기(FR-016): 답변마다 발급한 detailId와 분석 조건. 보고서는 클릭할 때 이 조건으로 만든다.
CREATE TABLE IF NOT EXISTS detail_condition (
    detail_id    TEXT         PRIMARY KEY,
    kind         VARCHAR(20)  NOT NULL,  -- 지금은 financial(재무 비교)뿐
    stock_codes  TEXT[]       NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
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


# 같은 회사·기간 데이터를 통째로 바꾼다(FR-022). 응답에 들어 있는 (회사, 사업연도, 보고서)만 먼저 지워서,
# 정정본에서 빠진 계정·재무제표가 남지 않게 한다. 응답에 없는 회사의 기존 데이터는 건드리지 않는다.
# 금액 값은 당기(분기)·누적·전기(전년 동기). 지우기와 넣기는 한 트랜잭션이다.
def upsert_financials(conn: psycopg.Connection, rows: list[dict]) -> int:
    periods = {(r["stock_code"], r["bsns_year"], r["reprt_code"]) for r in rows}
    with conn.cursor() as cur:
        cur.executemany(
            "DELETE FROM financial WHERE stock_code = %s AND bsns_year = %s AND reprt_code = %s",
            list(periods),
        )
        # 한 응답 안에 같은 키가 두 번 와도 깨지지 않게 충돌은 덮어쓴다.
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


def _normalize(name: str) -> str:
    return name.replace(" ", "").upper()


# 사용자가 말한 이름을 company에서 찾는다. 공백·대소문자를 무시한 정식 이름이나 종목코드가 일치해야 한다
# (별칭·오타는 인식률이 문제로 확인되면 — NFR-044). (찾은 [(종목코드, 이름)] 말한 순서·중복 제거, 못 찾은 이름들).
def find_companies(
    conn: psycopg.Connection, names: list[str]
) -> tuple[list[tuple[str, str]], list[str]]:
    wanted = [_normalize(n) for n in names]
    rows = conn.execute(
        "SELECT stock_code, name FROM company "
        "WHERE upper(replace(name, ' ', '')) = ANY(%s) OR stock_code = ANY(%s)",
        (wanted, wanted),
    ).fetchall()
    by_key = {}
    for stock_code, name in rows:
        by_key[_normalize(name)] = (stock_code, name)
        by_key[stock_code] = (stock_code, name)

    found: list[tuple[str, str]] = []
    missing: list[str] = []
    for original, key in zip(names, wanted, strict=True):
        company = by_key.get(key)
        if company is None:
            missing.append(original)
        elif company not in found:
            found.append(company)
    return found, missing


# 회사별 재무 행(분석에 필요한 열만). 행이 없는 회사도 빈 목록으로 돌려준다.
def load_financials(conn: psycopg.Connection, stock_codes: list[str]) -> dict[str, list[dict]]:
    grouped: dict[str, list[dict]] = {code: [] for code in stock_codes}
    with conn.cursor(row_factory=psycopg.rows.dict_row) as cur:
        cur.execute(
            "SELECT stock_code, bsns_year, reprt_code, fs_div, account_nm, amount, add_amount, "
            "prev_amount FROM financial WHERE stock_code = ANY(%s) "
            "ORDER BY stock_code, bsns_year, reprt_code, fs_div, account_nm",
            (stock_codes,),
        )
        for row in cur:
            grouped[row["stock_code"]].append(row)
    return grouped


# 회사에 저장된 사업의 내용이 어느 보고서(접수번호)의 것인지. 없으면 None.
def business_rcept_no(conn: psycopg.Connection, stock_code: str) -> str | None:
    row = conn.execute(
        "SELECT rcept_no FROM business_chunk WHERE stock_code = %s LIMIT 1", (stock_code,)
    ).fetchone()
    return row[0] if row else None


# 회사의 사업의 내용을 새 보고서의 청크로 통째로 바꾼다 — 회사별 최신 보고서만 유지(FR-021).
# chunks: [(소제목, 본문, 벡터)]. 지우기와 넣기는 한 트랜잭션이라 중간에 실패하면 기존 청크가 남는다.
def replace_business_chunks(
    conn: psycopg.Connection,
    stock_code: str,
    report: dict,
    chunks: list[tuple[str, str, list[float]]],
) -> int:
    with conn.cursor() as cur:
        cur.execute("DELETE FROM business_chunk WHERE stock_code = %s", (stock_code,))
        cur.executemany(
            "INSERT INTO business_chunk "
            "(stock_code, seq, rcept_no, report_nm, section, content, embedding) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s::vector)",
            [
                (stock_code, seq, report["rcept_no"], report["report_nm"], section, content, str(v))
                for seq, (section, content, v) in enumerate(chunks)
            ],
        )
    conn.commit()
    return len(chunks)


# 질문 벡터와 코사인 거리가 가까운 청크를 한 회사 안에서 limit개(FR-017). 인덱스 없이 그 회사 행만 훑는다.
# 기본 5개는 평가 지표 Recall@5(NFR-039)와 맞춘 값이다. 청크가 없으면 빈 목록.
def search_business_chunks(
    conn: psycopg.Connection, stock_code: str, vector: list[float], limit: int = 5
) -> list[dict]:
    with conn.cursor(row_factory=psycopg.rows.dict_row) as cur:
        cur.execute(
            "SELECT section, content, report_nm, rcept_no FROM business_chunk "
            "WHERE stock_code = %s ORDER BY embedding <=> %s::vector LIMIT %s",
            (stock_code, str(vector), limit),
        )
        return cur.fetchall()


# 수집기가 스키마를 만들기 전(DART 키 없음)이면 여기서 만들고 다시 한 번 실행한다.
def _execute_ensuring_schema(conn: psycopg.Connection, sql: str, params: tuple) -> None:
    try:
        conn.execute(sql, params)
    except psycopg.errors.UndefinedTable:
        conn.rollback()
        ensure_schema(conn)
        conn.execute(sql, params)
    conn.commit()


# 채팅 한 턴의 판정과 노드별 소요 시간을 남긴다.
def write_chat_log(
    conn: psycopg.Connection, thread_id: str, guard: str, node_ms: dict[str, int]
) -> None:
    _execute_ensuring_schema(
        conn,
        "INSERT INTO chat_log (thread_id, guard, node_ms) VALUES (%s, %s, %s)",
        (thread_id, guard, Jsonb(node_ms)),
    )


# LLM 호출 한 번의 사용량을 남긴다. fallback은 장애 대체(NFR-056)로 답한 호출이다.
def write_ai_usage(
    conn: psycopg.Connection,
    user_id: int,
    model: str,
    input_tokens: int,
    output_tokens: int,
    fallback: bool = False,
) -> None:
    _execute_ensuring_schema(
        conn,
        "INSERT INTO ai_usage (user_id, model, input_tokens, output_tokens, fallback) "
        "VALUES (%s, %s, %s, %s, %s)",
        (user_id, model, input_tokens, output_tokens, fallback),
    )


# 답변마다 발급한 상세 보기 ID와 분석 조건(기업 종목코드)을 저장한다.
def save_detail(
    conn: psycopg.Connection, detail_id: str, kind: str, stock_codes: list[str]
) -> None:
    _execute_ensuring_schema(
        conn,
        "INSERT INTO detail_condition (detail_id, kind, stock_codes) VALUES (%s, %s, %s)",
        (detail_id, kind, stock_codes),
    )


# 상세 보기 조건. 없으면 None(테이블을 만든 적 없는 환경도 같다).
def load_detail(conn: psycopg.Connection, detail_id: str) -> dict | None:
    try:
        row = conn.execute(
            "SELECT kind, stock_codes FROM detail_condition WHERE detail_id = %s", (detail_id,)
        ).fetchone()
    except psycopg.errors.UndefinedTable:
        conn.rollback()
        return None
    return {"kind": row[0], "stock_codes": row[1]} if row else None
