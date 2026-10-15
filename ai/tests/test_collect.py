import contextlib
from datetime import date

import pytest

from app import collect as collector
from app.collect import collect, collect_business, load_companies, periods
from app.dart import DartError
from app.store import business_rcept_no, replace_business_chunks, upsert_companies

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


# --- 사업의 내용(FR-021) ---

REPORT = {"rcept_no": "20260814003699", "report_nm": "반기보고서 (2026.06)"}
DOC = "<SECTION-1><TITLE>II. 사업의 내용</TITLE><SECTION-2><TITLE>1. 개요</TITLE><P>가</P><P>나</P></SECTION-2></SECTION-1>".encode()
NO_SECTION = "<SECTION-1><TITLE>I. 회사의 개요</TITLE><P>가</P></SECTION-1>".encode()


def _seed(conn, *stocks):
    upsert_companies(conn, [(s, f"{int(s):08d}", f"회사{s}") for s in stocks])


class Fakes:
    """DART·OpenAI 호출을 대신한다. 받은 호출을 기록해 둔다."""

    def __init__(self, report=REPORT, doc=DOC):
        self.report, self.doc = report, doc
        self.documents, self.embeds = [], []

    def latest_report(self, api_key, corp_code, today):
        return self.report

    def document(self, api_key, rcept_no):
        self.documents.append(rcept_no)
        return self.doc

    def embed(self, texts):
        self.embeds.append(list(texts))
        return [[0.5] * 1536 for _ in texts]

    def run(self, conn, stocks):
        return collect_business(
            conn,
            "KEY",
            stocks,
            today=TODAY,
            latest_report=self.latest_report,
            document=self.document,
            embed=self.embed,
        )


def test_새_보고서의_사업의_내용을_청크와_벡터로_저장한다(conn):
    _seed(conn, "005930")
    fakes = Fakes()

    assert fakes.run(conn, ["005930"]) == (1, 0)

    assert conn.execute(
        "SELECT stock_code, seq, rcept_no, report_nm, section, content FROM business_chunk"
    ).fetchall() == [("005930", 0, "20260814003699", "반기보고서 (2026.06)", "1. 개요", "가\n나")]
    assert fakes.embeds == [["가\n나"]]


def test_같은_접수번호면_다시_받지도_임베딩하지도_않는다(conn):
    _seed(conn, "005930")
    fakes = Fakes()
    fakes.run(conn, ["005930"])

    assert fakes.run(conn, ["005930"]) == (0, 0)

    assert fakes.documents == ["20260814003699"]  # 처음 한 번뿐
    assert len(fakes.embeds) == 1


def test_새_접수번호면_그_회사의_청크를_통째로_바꾼다(conn):
    _seed(conn, "005930", "000660")
    old = Fakes(report={"rcept_no": "20260515002181", "report_nm": "분기보고서 (2026.03)"})
    old.run(conn, ["005930", "000660"])
    new_doc = DOC.replace("가".encode(), "새 본문".encode())

    Fakes(doc=new_doc).run(conn, ["005930"])

    rows = conn.execute(
        "SELECT stock_code, rcept_no, content FROM business_chunk ORDER BY stock_code"
    ).fetchall()
    assert rows == [
        ("000660", "20260515002181", "가\n나"),  # 새 보고서가 없는 회사는 그대로
        ("005930", "20260814003699", "새 본문\n나"),
    ]


def test_한_회사가_실패해도_다음_회사를_계속한다(conn, capsys):
    _seed(conn, "000660", "005930")

    class Broken(Fakes):
        def embed(self, texts):
            # 첫 회사: 차원이 맞지 않아 DB가 거부한다. 둘째 회사부터 정상
            self.embeds.append(list(texts))
            return [[0.5, 0.5] for _ in texts] if len(self.embeds) == 1 else super().embed(texts)

    fakes = Broken()

    assert fakes.run(conn, ["000660", "005930"]) == (1, 1)

    assert business_rcept_no(conn, "000660") is None
    assert business_rcept_no(conn, "005930") == "20260814003699"
    assert "회사000660" in capsys.readouterr().out  # 실패한 회사를 알린다


def test_원문을_못_받아도_다음_회사를_계속한다(conn):
    _seed(conn, "000660", "005930")

    class Flaky(Fakes):
        def document(self, api_key, rcept_no):
            self.documents.append(rcept_no)
            if len(self.documents) == 1:
                raise DartError("020 요청 제한을 초과하였습니다.")
            return self.doc

    assert Flaky().run(conn, ["000660", "005930"]) == (1, 1)


def test_최신_보고서가_없으면_건너뛴다(conn):
    _seed(conn, "005930")
    fakes = Fakes(report=None)

    assert fakes.run(conn, ["005930"]) == (0, 0)
    assert fakes.documents == []


def test_사업의_내용_장이_없으면_기존_청크를_지우지_않는다(conn, capsys):
    _seed(conn, "005930")
    old = {"rcept_no": "20260515002181", "report_nm": "분기보고서 (2026.03)"}
    replace_business_chunks(conn, "005930", old, [("개요", "옛 본문", [0.1] * 1536)])

    assert Fakes(doc=NO_SECTION).run(conn, ["005930"]) == (0, 0)

    assert business_rcept_no(conn, "005930") == "20260515002181"
    assert "찾지 못해" in capsys.readouterr().out


def test_목록에_없는_종목은_수집하지_않는다(conn):
    _seed(conn, "005930", "000660")
    fakes = Fakes()

    fakes.run(conn, ["005930"])

    assert business_rcept_no(conn, "000660") is None


def _main_env(monkeypatch, conn, business):
    monkeypatch.setenv("DART_API_KEY", "KEY")
    monkeypatch.setattr(collector, "load_companies", lambda: [("005930", "삼성전자")])
    monkeypatch.setattr(collector, "collect", lambda connection, key, companies: 7)
    monkeypatch.setattr(collector.psycopg, "connect", lambda **kw: contextlib.nullcontext(conn))
    monkeypatch.setattr(collector, "collect_business", business)


def test_OpenAI_키가_없으면_사업의_내용만_건너뛴다(monkeypatch, capsys, conn):
    def business(*args, **kwargs):
        raise AssertionError("키가 없으면 호출하지 않는다")

    _main_env(monkeypatch, conn, business)
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)

    assert collector.main() == 0

    out = capsys.readouterr().out
    assert "재무 7행" in out  # 재무 수집은 그대로
    assert "OPENAI_API_KEY" in out


def test_사업의_내용_수집에_실패한_회사가_있으면_종료_코드가_1이다(monkeypatch, capsys, conn):
    calls = []

    def business(connection, api_key, stock_codes):
        calls.append((api_key, stock_codes))
        return 10, 2

    _main_env(monkeypatch, conn, business)
    monkeypatch.setenv("OPENAI_API_KEY", "sk-test")

    assert collector.main() == 1

    assert calls == [("KEY", ["005930"])]
    assert "10청크" in capsys.readouterr().out
