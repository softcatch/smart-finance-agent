import csv
import os
import sys
from datetime import date
from pathlib import Path

import psycopg

from app.business import chunk_lines, embed_texts, extract_business
from app.dart import (
    MAX_CORPS_PER_CALL,
    REPORT_CODES,
    DartError,
    fetch_corp_codes,
    fetch_document,
    fetch_financials,
    fetch_latest_report,
)
from app.history import today_kst
from app.store import (
    business_rcept_no,
    ensure_schema,
    replace_business_chunks,
    upsert_companies,
    upsert_financials,
)

# 수집 대상 기업(종목코드, 이름). 코스피200 전체 목록은 이 파일을 교체한다.
COMPANIES_CSV = Path(__file__).with_name("companies.csv")


def load_companies(path: Path = COMPANIES_CSV) -> list[tuple[str, str]]:
    with path.open(encoding="utf-8", newline="") as f:
        return [(row["stock_code"], row["name"]) for row in csv.DictReader(f)]


# 올해·작년 × 4개 보고서. 매일 전부 다시 받아 덮어쓰므로 초기 적재와 정정 반영이 같은 경로다.
def periods(today: date) -> list[tuple[int, str]]:
    return [(year, code) for year in (today.year - 1, today.year) for code in REPORT_CODES]


def _chunks(items: list, size: int):
    for i in range(0, len(items), size):
        yield items[i : i + size]


# 기업 목록을 기간마다 100개씩 나눠 받아 적재한다. 적재한 재무 행 수를 돌려준다.
# 호출 함수(corp_codes·financials)를 인자로 받아 테스트에서 가짜를 넣는다.
def collect(
    conn: psycopg.Connection,
    api_key: str,
    companies: list[tuple[str, str]],
    *,
    today: date | None = None,
    corp_codes=fetch_corp_codes,
    financials=fetch_financials,
) -> int:
    ensure_schema(conn)
    codes = corp_codes(api_key)
    missing = [stock for stock, _ in companies if stock not in codes]
    if missing:
        print(f"기업코드를 찾지 못해 건너뜀: {', '.join(missing)}")
    found = [(stock, *codes[stock]) for stock, _ in companies if stock in codes]
    upsert_companies(conn, [(stock, corp_code, name) for stock, corp_code, name in found])

    corp_list = [corp_code for _, corp_code, _ in found]
    total = 0
    for year, reprt_code in periods(today or today_kst()):
        for batch in _chunks(corp_list, MAX_CORPS_PER_CALL):
            rows = financials(api_key, batch, year, reprt_code)
            if rows:
                total += upsert_financials(conn, rows)
    return total


# 회사마다 최신 정기보고서를 찾아, 저장된 접수번호와 다를 때만 원문을 받아 청크·임베딩해 바꾼다(FR-021).
# 접수번호가 같으면 건너뛰므로 매일 돌려도 새 보고서만 임베딩한다. 사업의 내용 장이 없으면 기존 청크를 둔다.
# 한 회사의 실패는 알리고 다음 회사로 넘어간다. (적재한 청크 수, 실패한 회사 수)를 돌려준다.
# company 테이블은 collect()가 채운다 — 종목코드로 기업코드·이름을 읽는다.
def collect_business(
    conn: psycopg.Connection,
    api_key: str,
    stock_codes: list[str],
    *,
    today: date | None = None,
    latest_report=fetch_latest_report,
    document=fetch_document,
    embed=embed_texts,
) -> tuple[int, int]:
    rows = conn.execute(
        "SELECT stock_code, corp_code, name FROM company WHERE stock_code = ANY(%s) "
        "ORDER BY stock_code",
        (stock_codes,),
    ).fetchall()
    total = failed = 0
    for stock_code, corp_code, name in rows:
        try:
            report = latest_report(api_key, corp_code, today or today_kst())
            if report is None or business_rcept_no(conn, stock_code) == report["rcept_no"]:
                continue
            chunks = chunk_lines(extract_business(document(api_key, report["rcept_no"])))
            if not chunks:
                print(f"{name}: 사업의 내용을 찾지 못해 건너뜀 ({report['report_nm']})")
                continue
            vectors = embed([text for _, text in chunks])
            count = replace_business_chunks(
                conn,
                stock_code,
                report,
                [(section, text, v) for (section, text), v in zip(chunks, vectors, strict=True)],
            )
        # 한 회사의 실패(DART·OpenAI·DB 무엇이든)가 나머지 수집을 막지 않는다
        except Exception as e:  # noqa: BLE001
            conn.rollback()
            failed += 1
            print(f"{name}: 사업의 내용 수집 실패 — {type(e).__name__}: {e}")
        else:
            total += count
            print(f"{name}: {report['report_nm']} 청크 {count}개를 적재했습니다.")
    return total, failed


def main() -> int:
    api_key = os.getenv("DART_API_KEY", "").strip()
    if not api_key:
        print("DART_API_KEY가 없어 수집을 건너뜁니다. .env에 키를 넣으세요(opendart.fss.or.kr).")
        return 0
    companies = load_companies()
    failed = 0
    try:
        with psycopg.connect(
            host=os.getenv("POSTGRES_HOST", "localhost"),
            dbname=os.getenv("POSTGRES_DB", "smart"),
            user=os.getenv("POSTGRES_USER", "smart"),
            password=os.getenv("POSTGRES_PASSWORD", ""),
        ) as conn:
            print(f"재무 {collect(conn, api_key, companies)}행을 적재했습니다.")
            if os.getenv("OPENAI_API_KEY", "").strip():
                chunks, failed = collect_business(conn, api_key, [s for s, _ in companies])
                print(f"사업의 내용 {chunks}청크를 적재했습니다(실패 {failed}곳).")
            else:
                print("OPENAI_API_KEY가 없어 사업의 내용 수집을 건너뜁니다(임베딩에 필요).")
    except DartError as e:
        print(f"DART 수집 실패: {e}")
        return 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
