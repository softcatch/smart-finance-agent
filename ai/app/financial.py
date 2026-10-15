# 재무 비교 표를 만든다. 기간 선택·기준 선택·증감·영업이익률은 모두 여기서(코드가) 계산한다 — LLM은 숫자를 만들지 않는다.
LABELS = {"11013": "1분기", "11012": "2분기", "11014": "3분기", "11011": "연간", "Q4": "4분기"}
ORDER = {"11013": 1, "11012": 2, "11014": 3, "11011": 4}  # 한 해 안의 보고서 순서
QUARTERLY = ("11013", "11012", "11014")  # 1·2·3분기 보고서. 사업보고서(연간)에서 빼 4분기를 구한다
BASIS = {"CFS": "연결", "OFS": "별도"}
ACCOUNTS = ("매출액", "영업이익", "당기순이익")
ASK_COMPANY = "어느 기업을 볼까요? 기업 이름을 알려 주세요. 예: 삼성전자와 SK하이닉스 비교해 줘"


def period_label(year: int, reprt_code: str) -> str:
    return f"{year}년 {LABELS[reprt_code]}"


def _key(period: tuple[int, str]) -> tuple[int, int]:
    return period[0], ORDER[period[1]]


def _won(amount: int) -> str:
    return f"{round(amount / 100_000_000):,}억원"  # 억원 단위로 반올림(조·억 표기 정규화는 추후)


def _change(current: int | None, prev: int | None) -> str | None:
    if current is None or not prev:  # 전기가 없거나 0이면 증감률을 쓰지 않는다
        return None
    return f"{(current - prev) / abs(prev) * 100:+.1f}%"


def _cell(current: int | None, prev: int | None) -> str:
    if current is None:
        return "-"
    change = _change(current, prev)
    return f"{_won(current)} ({change})" if change else _won(current)


def _margin(op: int | None, revenue: int | None) -> float | None:
    if op is None or not revenue:
        return None
    return op / revenue * 100


def _margin_cell(rec: dict[str, dict]) -> str:
    op, revenue = rec.get("영업이익"), rec.get("매출액")
    now = _margin(op and op["amount"], revenue and revenue["amount"])
    if now is None:
        return "-"
    before = _margin(op and op["prev_amount"], revenue and revenue["prev_amount"])
    return f"{now:.1f}% ({now - before:+.1f}%p)" if before is not None else f"{now:.1f}%"


# 사업보고서의 amount는 연간이다. 분기 비교(명세 "최근 분기")를 위해 같은 해 1·2·3분기 단독 값을 빼
# 4분기 단독 값으로 바꾼다. 전년 4분기도 같은 방식(prev_amount)이다. 하나라도 없으면 계산하지 않는다(None).
def _fourth_quarter(rows: list[dict], year: int) -> list[dict]:
    result = []
    for annual in (r for r in rows if r["bsns_year"] == year and r["reprt_code"] == "11011"):
        parts = [
            r
            for r in rows
            if r["bsns_year"] == year
            and r["reprt_code"] in QUARTERLY
            and r["fs_div"] == annual["fs_div"]
            and r["account_nm"] == annual["account_nm"]
        ]

        def minus(field: str, parts=parts, annual=annual) -> int | None:
            values = [p[field] for p in parts]
            if len(parts) < len(QUARTERLY) or annual[field] is None or None in values:
                return None
            return annual[field] - sum(values)

        result.append({**annual, "amount": minus("amount"), "prev_amount": minus("prev_amount")})
    return result


# companies: [(종목코드, 이름)] 요청 순서. rows_by_stock: 종목코드 → financial 행 목록.
# 데이터가 하나도 없으면 None. 아니면 E-11의 table({columns, rows, note})을 돌려준다.
def build_table(
    companies: list[tuple[str, str]], rows_by_stock: dict[str, list[dict]]
) -> dict | None:
    latest = {}
    for stock, _ in companies:
        periods = {(r["bsns_year"], r["reprt_code"]) for r in rows_by_stock.get(stock, [])}
        latest[stock] = max(periods, key=_key) if periods else None
    published = [p for p in latest.values() if p]
    if not published:
        return None

    # 비교 대상 중 가장 최근 기간으로 맞추고, 그 기간이 없는 회사는 미공시로 둔다.
    target = max(published, key=_key)
    at_target = {
        stock: [r for r in rows_by_stock[stock] if (r["bsns_year"], r["reprt_code"]) == target]
        for stock, _ in companies
        if latest[stock] == target
    }
    annual = target[1] == "11011"
    if annual:
        at_target = {stock: _fourth_quarter(rows_by_stock[stock], target[0]) for stock in at_target}
    # 모두 연결 재무제표가 있으면 연결, 하나라도 없으면 전부 별도로 맞춘다(명세).
    basis = (
        "CFS" if all(any(r["fs_div"] == "CFS" for r in rs) for rs in at_target.values()) else "OFS"
    )
    values = {
        stock: {r["account_nm"]: r for r in rs if r["fs_div"] == basis}
        for stock, rs in at_target.items()
    }

    def cell(stock: str, account: str) -> str:
        if stock not in values:
            return "미공시"
        rec = values[stock].get(account)
        return _cell(rec["amount"], rec["prev_amount"]) if rec else "-"

    rows = [[a, *(cell(stock, a) for stock, _ in companies)] for a in ACCOUNTS]
    rows.append(
        [
            "영업이익률",
            *(
                "미공시" if stock not in values else _margin_cell(values[stock])
                for stock, _ in companies
            ),
        ]
    )
    undisclosed = [name for stock, name in companies if stock not in values]
    label = f"{target[0]}년 4분기(연간 − 1~3분기)" if annual else period_label(*target)
    note = f"{label} · {BASIS[basis]} 기준 · 괄호 안은 전년 동기 대비"
    if undisclosed:
        note += f" · {', '.join(undisclosed)} 미공시"
    return {"columns": ["항목", *(name for _, name in companies)], "rows": rows, "note": note}
