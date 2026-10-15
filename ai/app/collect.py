import csv
import os
import sys
from datetime import date
from pathlib import Path

import psycopg

from app.dart import (
    MAX_CORPS_PER_CALL,
    REPORT_CODES,
    DartError,
    fetch_corp_codes,
    fetch_financials,
)
from app.history import today_kst
from app.store import ensure_schema, upsert_companies, upsert_financials

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


def main() -> int:
    api_key = os.getenv("DART_API_KEY", "").strip()
    if not api_key:
        print("DART_API_KEY가 없어 수집을 건너뜁니다. .env에 키를 넣으세요(opendart.fss.or.kr).")
        return 0
    try:
        with psycopg.connect(
            host=os.getenv("POSTGRES_HOST", "localhost"),
            dbname=os.getenv("POSTGRES_DB", "smart"),
            user=os.getenv("POSTGRES_USER", "smart"),
            password=os.getenv("POSTGRES_PASSWORD", ""),
        ) as conn:
            total = collect(conn, api_key, load_companies())
    except DartError as e:
        print(f"DART 수집 실패: {e}")
        return 1
    print(f"재무 {total}행을 적재했습니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
