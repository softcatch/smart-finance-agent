import io
import zipfile

import httpx
import pytest

from app.dart import DartError, fetch_corp_codes, fetch_financials, parse_amount, parse_financials


def _item(account, fs_div="CFS", sj_div="IS", stock_code="005930", **kw):
    base = {
        "rcept_no": "20250515000123",
        "bsns_year": "2025",
        "stock_code": stock_code,
        "reprt_code": "11013",
        "account_nm": account,
        "fs_div": fs_div,
        "fs_nm": "연결재무제표" if fs_div == "CFS" else "재무제표",
        "sj_div": sj_div,
        "sj_nm": "손익계산서",
        "thstrm_nm": "제 57 기 1분기",
        "thstrm_amount": "79,140,000,000,000",
        "thstrm_add_amount": "79,140,000,000,000",
        "frmtrm_nm": "제 56 기 1분기",
        "frmtrm_amount": "71,915,611,000,000",
        "frmtrm_add_amount": "71,915,611,000,000",
        "ord": "1",
        "currency": "KRW",
    }
    return {**base, **kw}


def _response(body):
    return httpx.Response(200, json=body, request=httpx.Request("GET", "https://opendart"))


def test_금액_문자열을_정수로():
    assert parse_amount("79,140,000,000,000") == 79_140_000_000_000
    assert parse_amount("-1,234") == -1234
    assert parse_amount("0") == 0


@pytest.mark.parametrize("text", ["-", "", " ", None])
def test_금액이_없으면_None(text):
    assert parse_amount(text) is None


def test_세_계정만_남기고_연결_별도를_모두_둔다():
    items = [
        _item("매출액"),
        _item("영업이익"),
        _item("당기순이익"),
        _item("매출액", fs_div="OFS"),
        _item("자산총계", sj_div="BS"),  # 재무상태표 — 쓰지 않는다
        _item("법인세차감전 순이익"),  # 쓰지 않는다
    ]

    rows = parse_financials(items)

    assert [(r["account_nm"], r["fs_div"]) for r in rows] == [
        ("매출액", "CFS"),
        ("영업이익", "CFS"),
        ("당기순이익", "CFS"),
        ("매출액", "OFS"),
    ]


def test_행의_값을_옮긴다():
    row = parse_financials([_item("영업이익")])[0]

    assert row == {
        "stock_code": "005930",
        "bsns_year": 2025,
        "reprt_code": "11013",
        "fs_div": "CFS",
        "account_nm": "영업이익",
        "amount": 79_140_000_000_000,
        "add_amount": 79_140_000_000_000,
        "prev_amount": 71_915_611_000_000,
        "rcept_no": "20250515000123",
    }


def test_계정명_변형을_표준_이름으로():
    rows = parse_financials(
        [_item("영업이익(손실)"), _item("당기순이익(손실)"), _item("수익(매출액)")]
    )

    assert [r["account_nm"] for r in rows] == ["영업이익", "당기순이익", "매출액"]


def test_누적_금액이_없는_행도_받는다():
    row = parse_financials([_item("매출액", thstrm_add_amount="-")])[0]

    assert row["add_amount"] is None
    assert row["amount"] == 79_140_000_000_000


def test_종목코드가_없는_행은_버린다():
    assert parse_financials([_item("매출액", stock_code=" ")]) == []


def test_정상_응답을_해석하고_요청_인자를_보낸다(monkeypatch):
    calls = []

    def get(url, **kwargs):
        calls.append((url, kwargs))
        return _response({"status": "000", "message": "정상", "list": [_item("매출액")]})

    monkeypatch.setattr(httpx, "get", get)

    rows = fetch_financials("KEY", ["00126380", "00164779"], 2025, "11013")

    url, kwargs = calls[0]
    assert url == "https://opendart.fss.or.kr/api/fnlttMultiAcnt.json"
    assert kwargs["params"] == {
        "crtfc_key": "KEY",
        "corp_code": "00126380,00164779",
        "bsns_year": "2025",
        "reprt_code": "11013",
    }
    assert len(rows) == 1


def test_공시_전이면_빈_목록(monkeypatch):
    body = {"status": "013", "message": "조회된 데이터가 없습니다."}
    monkeypatch.setattr(httpx, "get", lambda url, **kw: _response(body))

    assert fetch_financials("KEY", ["00126380"], 2026, "11011") == []


def test_인증_오류는_원인을_알린다(monkeypatch):
    # 실제 서버가 HTTP 200으로 돌려주는 형식
    body = {"status": "010", "message": "등록되지 않은 인증키입니다."}
    monkeypatch.setattr(httpx, "get", lambda url, **kw: _response(body))

    with pytest.raises(DartError, match="010.*등록되지 않은 인증키"):
        fetch_financials("BAD", ["00126380"], 2025, "11013")


def _corp_zip() -> bytes:
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?><result>'
        "<list><corp_code>00126380</corp_code><corp_name>삼성전자</corp_name>"
        "<stock_code>005930</stock_code><modify_date>20240101</modify_date></list>"
        "<list><corp_code>00434003</corp_code><corp_name>다코</corp_name>"
        "<stock_code> </stock_code><modify_date>20170630</modify_date></list>"
        "</result>"
    )
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("CORPCODE.xml", xml)
    return buf.getvalue()


def test_종목코드로_기업코드를_찾는다_상장사만(monkeypatch):
    res = httpx.Response(200, content=_corp_zip(), request=httpx.Request("GET", "https://opendart"))
    monkeypatch.setattr(httpx, "get", lambda url, **kw: res)

    assert fetch_corp_codes("KEY") == {"005930": ("00126380", "삼성전자")}


def test_기업코드_인증_오류는_XML로_온다(monkeypatch):
    # 실제 서버: corpCode.xml은 오류일 때 ZIP이 아니라 XML을 돌려준다
    xml = '<?xml version="1.0"?><result><status>010</status><message>등록되지 않은 인증키입니다.</message></result>'
    res = httpx.Response(
        200, content=xml.encode(), request=httpx.Request("GET", "https://opendart")
    )
    monkeypatch.setattr(httpx, "get", lambda url, **kw: res)

    with pytest.raises(DartError, match="010"):
        fetch_corp_codes("BAD")
