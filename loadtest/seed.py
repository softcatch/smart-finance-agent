"""부하 테스트 시드(NFR-031). 측정용 DB에 회원·계좌·송금 내역과 DART 데이터를 넣는다.

Spring이 Flyway로 테이블을 만든 뒤에 돌린다(compose의 seed 서비스). 이미 들어 있으면 건너뛴다.
- 회원 SEED_USERS명: 로그인 ID load000001…, 비밀번호 pw12345!. 계좌는 하나씩(번호 100000000000 + 회원 번호,
  계좌 비밀번호 1234, 잔액 1억)이고, 인당 송금 내역은 60건이다(보낸 30 + 받은 30).
- DART 30곳: 회사, 재무(2025~2026, 합성 숫자), 사업의 내용 청크(합성 글, 임베딩은 가짜 LLM과 같은 함수).
  부하 테스트는 데이터의 모양과 양만 필요해서 숫자와 글은 지어낸 값이다.
BCrypt 해시는 PostgreSQL pgcrypto로 만든다($2a$10$ 꼴이라 Spring의 BCryptPasswordEncoder가 읽는다).
"""

import os
import random

import psycopg

from app.store import ensure_schema
from loadtest.fake_llm import embedding_vector

USERS = int(os.getenv("SEED_USERS", "10000"))
PASSWORD = "pw12345!"
ACCOUNT_PASSWORD = "1234"
SENT_PER_USER = 30  # 받은 건수도 같다
CHUNKS_PER_COMPANY = 85  # 실데이터 30곳이 2,528개(회사당 평균 84개)였다
# 수집 대상 30곳(종목코드: DART 법인명). 채팅이 이 이름으로 회사를 찾는다.
COMPANIES = {
    "000270": "기아",
    "000660": "SK하이닉스",
    "005380": "현대자동차",
    "005490": "POSCO홀딩스",
    "005930": "삼성전자",
    "006400": "삼성SDI",
    "009150": "삼성전기",
    "010130": "고려아연",
    "010950": "S-Oil",
    "012330": "현대모비스",
    "012450": "한화에어로스페이스",
    "015760": "한국전력공사",
    "017670": "SK텔레콤",
    "028260": "삼성물산",
    "030200": "케이티",
    "032830": "삼성생명",
    "035420": "NAVER",
    "035720": "카카오",
    "051910": "LG화학",
    "055550": "신한지주",
    "066570": "LG전자",
    "068270": "셀트리온",
    "086790": "하나금융지주",
    "096770": "SK이노베이션",
    "105560": "KB금융",
    "207940": "삼성바이오로직스",
    "259960": "크래프톤",
    "316140": "우리금융지주",
    "329180": "HD현대중공업",
    "373220": "LG에너지솔루션",
}
SECTIONS = [
    "사업의 개요",
    "주요 제품 및 서비스",
    "원재료 및 생산설비",
    "매출 및 수주상황",
    "연구개발활동",
]
# (사업연도, 보고서 코드, 분기 번호) — 올해·작년 4개 보고서(올해는 반기까지)
REPORTS = [(2025, "11013", 1), (2025, "11012", 2), (2025, "11014", 3), (2025, "11011", 4)]
REPORTS += [(2026, "11013", 1), (2026, "11012", 2)]


def connect() -> psycopg.Connection:
    return psycopg.connect(
        host=os.getenv("POSTGRES_HOST", "localhost"),
        dbname=os.getenv("POSTGRES_DB", "smart"),
        user=os.getenv("POSTGRES_USER", "smart"),
        password=os.getenv("POSTGRES_PASSWORD", ""),
    )


def seed_members(conn: psycopg.Connection) -> None:
    done = conn.execute("SELECT count(*) FROM member WHERE login_id LIKE 'load%'").fetchone()[0]
    if done >= USERS:
        print(f"회원 {done}명이 이미 있다 — 건너뜀")
        return
    conn.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto")
    member_hash = conn.execute("SELECT crypt(%s, gen_salt('bf', 10))", (PASSWORD,)).fetchone()[0]
    account_hash = conn.execute(
        "SELECT crypt(%s, gen_salt('bf', 10))", (ACCOUNT_PASSWORD,)
    ).fetchone()[0]
    conn.execute(
        "INSERT INTO member (login_id, password_hash, name) "
        "SELECT 'load' || lpad(n::text, 6, '0'), %s, '유저' || n FROM generate_series(1, %s) n",
        (member_hash, USERS),
    )
    conn.execute(
        "INSERT INTO account (member_id, account_number, idempotency_key, balance, is_primary, "
        "password_hash) SELECT id, lpad((100000000000 + substring(login_id from 5)::int)::text, "
        "12, '0'), 'seed', 100000000, true, %s FROM member WHERE login_id LIKE 'load%%'",
        (account_hash,),
    )
    # 회원 n이 n+1…n+30번 회원에게 보낸다 — 인당 보낸 30 + 받은 30. 수신 번호는 끝에서 처음으로 돌아간다.
    conn.execute(
        f"INSERT INTO transfer (from_account_number, to_account_number, amount, created_at) "
        f"SELECT lpad((100000000000 + n)::text, 12, '0'), "
        f"lpad((100000000000 + (n + k - 1) % {USERS} + 1)::text, 12, '0'), "
        f"1000 + (n * k) % 9000, now() - ((n * k) % 1000 + k) * interval '1 minute' "
        f"FROM generate_series(1, {USERS}) n, generate_series(1, {SENT_PER_USER}) k"
    )
    conn.commit()
    print(f"회원 {USERS}명, 계좌 {USERS}개, 송금 내역 {USERS * SENT_PER_USER}건")


def financial_rows(stock: str) -> list[tuple]:
    """한 회사의 재무 행. 분기 값(1~3분기·반기는 해당 분기 단독, 사업보고서는 연간)과 전년 동기 값을 지어낸다."""
    rng = random.Random(stock)
    base = 10 ** rng.uniform(11, 13.8)  # 분기 매출 1천억 ~ 6조원
    margin = rng.uniform(0.03, 0.25)
    quarter = {}  # (연도, 분기) → {계정: 값}
    for index, year in enumerate((2024, 2025, 2026)):
        for q in range(1, 5):
            revenue = base * (1 + 0.03 * (index * 4 + q)) * rng.uniform(0.95, 1.05)
            op = revenue * margin * rng.uniform(0.8, 1.2)
            quarter[(year, q)] = {
                "매출액": revenue,
                "영업이익": op,
                "당기순이익": op * rng.uniform(0.6, 1.1),
            }

    rows = []
    for year, code, q in REPORTS:
        for fs_div, scale in (("CFS", 1.0), ("OFS", 0.7)):
            for account in ("매출액", "영업이익", "당기순이익"):
                if code == "11011":  # 사업보고서는 연간
                    amount = sum(quarter[(year, i)][account] for i in range(1, 5))
                    prev = sum(quarter[(year - 1, i)][account] for i in range(1, 5))
                    add = None
                else:
                    amount, prev = quarter[(year, q)][account], quarter[(year - 1, q)][account]
                    add = sum(quarter[(year, i)][account] for i in range(1, q + 1))
                rows.append(
                    (stock, year, code, fs_div, account, round(amount * scale),
                     None if add is None else round(add * scale), round(prev * scale),
                     f"{year}0814{q:02d}{stock[-4:]}")
                )  # fmt: skip
    return rows


def chunk_rows(stock: str, name: str) -> list[tuple]:
    rows = []
    for seq in range(CHUNKS_PER_COMPANY):
        topic = SECTIONS[seq % len(SECTIONS)]
        content = " ".join(
            f"{name}의 {topic} 설명 {seq}-{j}: 제품의 특성과 시장 환경, 판매 경로, 원재료와 생산 설비를 서술한다."
            for j in range(9)
        )
        rows.append(
            (stock, seq, f"20260814{stock[-4:]}", "반기보고서 (2026.06)",
             f"II. 사업의 내용 > {seq // 10 + 1}. {topic}", content, str(embedding_vector(content)))
        )  # fmt: skip
    return rows


def seed_dart(conn: psycopg.Connection) -> None:
    ensure_schema(conn)  # FastAPI 소유 테이블(회사·재무·청크)
    if conn.execute("SELECT count(*) FROM company").fetchone()[0] >= len(COMPANIES):
        print("DART 데이터가 이미 있다 — 건너뜀")
        return
    with conn.cursor() as cur:
        cur.executemany(
            "INSERT INTO company (stock_code, corp_code, name) VALUES (%s, %s, %s) "
            "ON CONFLICT DO NOTHING",
            [(stock, f"{int(stock):08d}", name) for stock, name in COMPANIES.items()],
        )
        for stock, name in COMPANIES.items():
            cur.executemany(
                "INSERT INTO financial (stock_code, bsns_year, reprt_code, fs_div, account_nm, "
                "amount, add_amount, prev_amount, rcept_no) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s) "
                "ON CONFLICT DO NOTHING",
                financial_rows(stock),
            )
            cur.executemany(
                "INSERT INTO business_chunk (stock_code, seq, rcept_no, report_nm, section, "
                "content, embedding) VALUES (%s,%s,%s,%s,%s,%s,%s::vector) ON CONFLICT DO NOTHING",
                chunk_rows(stock, name),
            )
    conn.commit()
    print(
        f"회사 {len(COMPANIES)}곳, 재무 행, 사업의 내용 청크 {len(COMPANIES) * CHUNKS_PER_COMPANY}개"
    )


def main() -> None:
    with connect() as conn:
        seed_members(conn)
        seed_dart(conn)
        conn.execute("ANALYZE")
        conn.commit()


if __name__ == "__main__":
    main()
