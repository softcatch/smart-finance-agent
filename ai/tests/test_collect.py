from datetime import date

import pytest

from app import collect as collector
from app.collect import collect, load_companies, periods
from app.dart import DartError

TODAY = date(2026, 10, 15)


def _row(stock_code, amount=100, rcept_no="20251015000001", **kw):
    base = {
        "stock_code": stock_code,
        "bsns_year": 2026,
        "reprt_code": "11013",
        "fs_div": "CFS",
        "account_nm": "매출액",
        "amount": amount,
        "add_amount": amount,
        "prev_amount": 50,
        "rcept_no": rcept_no,
    }
    return {**base, **kw}


def test_올해와_작년의_네_보고서를_받는다():
    assert periods(TODAY) == [
        (2025, "11013"),
        (2025, "11012"),
        (2025, "11014"),
        (2025, "11011"),
        (2026, "11013"),
        (2026, "11012"),
        (2026, "11014"),
        (2026, "11011"),
    ]


def test_기업_목록_파일을_읽는다():
    companies = load_companies()

    assert ("005930", "삼성전자") in companies
    assert all(len(code) == 6 for code, _ in companies)


def test_기간마다_기업을_100개씩_나눠_받아_적재한다(conn):
    stocks = [f"{i:06d}" for i in range(250)]
    companies = [(s, f"회사{s}") for s in stocks]
    calls = []

    def fetch(api_key, corp_codes, year, reprt_code):
        calls.append((year, reprt_code, len(corp_codes)))
        return [_row("000000", bsns_year=year, reprt_code=reprt_code)] if corp_codes else []

    total = collect(
        conn,
        "KEY",
        companies,
        today=TODAY,
        corp_codes=lambda key: {s: (f"{int(s):08d}", f"회사{s}") for s in stocks},
        financials=fetch,
    )

    assert len(calls) == 8 * 3  # 8개 기간 × 100·100·50개
    assert {n for _, _, n in calls} == {100, 50}
    assert total == 24  # 호출마다 1행(같은 키는 덮어쓰므로 DB에는 8행)
    assert conn.execute("SELECT count(*) FROM financial").fetchone()[0] == 8
    assert conn.execute("SELECT count(*) FROM company").fetchone()[0] == 250


def test_기업코드를_못_찾은_종목은_건너뛴다(conn, capsys):
    companies = [("005930", "삼성전자"), ("999999", "없는회사")]
    seen = []

    def fetch(api_key, corp_codes, year, reprt_code):
        seen.append(corp_codes)
        return []

    collect(
        conn,
        "KEY",
        companies,
        today=TODAY,
        corp_codes=lambda key: {"005930": ("00126380", "삼성전자")},
        financials=fetch,
    )

    assert all(batch == ["00126380"] for batch in seen)
    assert "999999" in capsys.readouterr().out  # 못 찾았다고 알린다
    assert conn.execute("SELECT stock_code FROM company").fetchall() == [("005930",)]


def test_정정_공시는_다음_수집에서_덮어쓴다(conn):
    companies = [("005930", "삼성전자")]
    corp = lambda key: {"005930": ("00126380", "삼성전자")}

    def first(api_key, corp_codes, year, reprt_code):
        return (
            [_row("005930", amount=100, rcept_no="20251015000001")]
            if (year, reprt_code) == (2026, "11013")
            else []
        )

    def corrected(api_key, corp_codes, year, reprt_code):
        return (
            [_row("005930", amount=120, rcept_no="20251101000009")]
            if (year, reprt_code) == (2026, "11013")
            else []
        )

    collect(conn, "KEY", companies, today=TODAY, corp_codes=corp, financials=first)
    collect(conn, "KEY", companies, today=TODAY, corp_codes=corp, financials=corrected)

    assert conn.execute("SELECT amount, rcept_no FROM financial").fetchall() == [
        (120, "20251101000009")
    ]


def test_DART_오류는_그대로_올린다(conn):
    def fetch(api_key, corp_codes, year, reprt_code):
        raise DartError("020 요청 제한을 초과하였습니다.")

    with pytest.raises(DartError, match="020"):
        collect(
            conn,
            "KEY",
            [("005930", "삼성전자")],
            today=TODAY,
            corp_codes=lambda key: {"005930": ("00126380", "삼성전자")},
            financials=fetch,
        )


def test_키가_없으면_안내만_하고_건너뛴다(monkeypatch, capsys):
    monkeypatch.delenv("DART_API_KEY", raising=False)

    assert collector.main() == 0
    assert "DART_API_KEY" in capsys.readouterr().out
