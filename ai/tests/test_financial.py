from app.collect import load_companies
from app.financial import (
    COMPETITOR_GROUPS,
    MAX_COMPANIES,
    build_detail_table,
    build_table,
    find_competitors,
    period_label,
)

SEC = ("005930", "삼성전자")
HYNIX = ("000660", "SK하이닉스")


def _rows(stock, year, reprt, fs_div="CFS", revenue=None, op=None, ni=None):
    """회사의 한 기간 행들. 값은 (당기, 전년 동기). None이면 그 계정 행이 없다."""
    out = []
    for account, pair in (("매출액", revenue), ("영업이익", op), ("당기순이익", ni)):
        if pair is None:
            continue
        out.append(
            {
                "stock_code": stock,
                "bsns_year": year,
                "reprt_code": reprt,
                "fs_div": fs_div,
                "account_nm": account,
                "amount": pair[0],
                "prev_amount": pair[1],
            }
        )
    return out


# 삼성전자 2026년 1분기 연결(실제 수집값)
SEC_Q1 = _rows(
    "005930",
    2026,
    "11013",
    revenue=(79_140_503_000_000, 71_915_601_000_000),
    op=(6_685_272_000_000, 6_606_009_000_000),
    ni=(8_222_878_000_000, 6_754_708_000_000),
)


def test_한_회사의_값과_전년_동기_대비를_코드가_계산한다():
    table = build_table([SEC], {"005930": SEC_Q1})

    assert table["columns"] == ["항목", "삼성전자"]
    assert table["rows"] == [
        ["매출액", "791,405억원 (+10.0%)"],
        ["영업이익", "66,853억원 (+1.2%)"],
        ["당기순이익", "82,229억원 (+21.7%)"],
        ["영업이익률", "8.4% (-0.7%p)"],
    ]
    assert "2026년 1분기" in table["note"]
    assert "연결" in table["note"]


def test_기간_이름():
    assert period_label(2026, "11013") == "2026년 1분기"
    assert period_label(2026, "11012") == "2026년 2분기"
    assert period_label(2026, "11014") == "2026년 3분기"
    assert period_label(2025, "11011") == "2025년 연간"


def test_가장_최근_기간을_고르고_그_기간이_없는_회사는_미공시():
    hynix_q2 = _rows("000660", 2026, "11012", op=(5_000_000_000_000, 3_000_000_000_000))
    sec_older = SEC_Q1  # 삼성전자는 1분기까지만 있다

    table = build_table([SEC, HYNIX], {"005930": sec_older, "000660": hynix_q2})

    assert "2026년 2분기" in table["note"]
    assert table["columns"] == ["항목", "삼성전자", "SK하이닉스"]
    assert [r[1] for r in table["rows"]] == ["미공시"] * 4
    assert table["rows"][1][2] == "50,000억원 (+66.7%)"
    assert "삼성전자 미공시" in table["note"]


def test_한_회사라도_연결이_없으면_모두_별도로_맞춘다():
    sec_both = SEC_Q1 + _rows(
        "005930", 2026, "11013", fs_div="OFS", op=(1_469_273_000_000, 2_007_380_000_000)
    )
    hynix_ofs_only = _rows(
        "000660", 2026, "11013", fs_div="OFS", op=(2_000_000_000_000, 1_000_000_000_000)
    )

    table = build_table([SEC, HYNIX], {"005930": sec_both, "000660": hynix_ofs_only})

    assert "별도" in table["note"]
    assert table["rows"][1][1] == "14,693억원 (-26.8%)"  # 삼성전자도 별도 값


def test_매출액이_없으면_영업이익률도_표시하지_않는다_금융업():
    bank = _rows(
        "105560",
        2026,
        "11013",
        op=(2_293_028_000_000, 2_000_000_000_000),
        ni=(1_699_122_000_000, 1_500_000_000_000),
    )

    table = build_table([("105560", "KB금융")], {"105560": bank})

    assert table["rows"][0] == ["매출액", "-"]
    assert table["rows"][3] == ["영업이익률", "-"]


def test_전년_동기가_0이거나_없으면_증감률을_쓰지_않는다():
    rows = _rows("005930", 2026, "11013", op=(100_000_000_000, 0), ni=(50_000_000_000, None))

    table = build_table([SEC], {"005930": rows})

    assert table["rows"][1] == ["영업이익", "1,000억원"]
    assert table["rows"][2] == ["당기순이익", "500억원"]


def test_적자_전환은_부호를_그대로_보여준다():
    rows = _rows("005930", 2026, "11013", op=(-300_000_000_000, 200_000_000_000))

    table = build_table([SEC], {"005930": rows})

    assert table["rows"][1] == ["영업이익", "-3,000억원 (-250.0%)"]


# 삼성전자 2025년 영업이익(연결, 실제 수집값): 1·2·3분기는 분기 단독, 사업보고서는 연간
SEC_2025 = (
    _rows("005930", 2025, "11013", op=(6_685_272_000_000, 6_606_009_000_000))
    + _rows("005930", 2025, "11012", op=(4_676_057_000_000, 10_443_878_000_000))
    + _rows("005930", 2025, "11014", op=(12_166_062_000_000, 9_183_371_000_000))
    + _rows("005930", 2025, "11011", op=(43_601_051_000_000, 32_725_961_000_000))
)


def test_사업보고서가_가장_최근이면_4분기_단독_값으로_바꾼다():
    table = build_table([SEC], {"005930": SEC_2025})

    # 4분기 = 연간 43.60조 − (1+2+3분기) 23.53조 = 20.07조, 전년 4분기 = 32.73조 − 26.23조 = 6.49조
    assert table["rows"][1] == ["영업이익", "200,737억원 (+209.2%)"]
    assert "2025년 4분기" in table["note"]
    assert "연간" in table["note"]  # 계산 방법을 알린다


def test_분기_보고서가_모자라면_4분기를_계산하지_않고_표시하지_않는다():
    without_q2 = [r for r in SEC_2025 if r["reprt_code"] != "11012"]

    table = build_table([SEC], {"005930": without_q2})

    assert table["rows"][1] == ["영업이익", "-"]
    assert "2025년 4분기" in table["note"]


def test_분기_순서로_가장_최근을_고른다():
    older_year_annual = _rows("005930", 2025, "11011", op=(1, 1))
    q1_2026 = _rows("005930", 2026, "11013", op=(2_000_000_000_000, 1_000_000_000_000))

    table = build_table([SEC], {"005930": older_year_annual + q1_2026})

    assert "2026년 1분기" in table["note"]


def test_데이터가_하나도_없으면_None():
    assert build_table([SEC, HYNIX], {"005930": [], "000660": []}) is None


def test_상세_표는_최근_분기부터_분기마다_네_줄이다():
    sec_2025_q3 = _rows("005930", 2025, "11014", revenue=(86_000_000_000_000, 79_000_000_000_000))

    table = build_detail_table([SEC], {"005930": SEC_Q1 + sec_2025_q3})

    assert table["columns"] == ["기간", "항목", "삼성전자"]
    assert [r[:2] for r in table["rows"]] == [
        ["2026년 1분기", "매출액"],
        ["2026년 1분기", "영업이익"],
        ["2026년 1분기", "당기순이익"],
        ["2026년 1분기", "영업이익률"],
        ["2025년 3분기", "매출액"],
        ["2025년 3분기", "영업이익"],
        ["2025년 3분기", "당기순이익"],
        ["2025년 3분기", "영업이익률"],
    ]
    assert table["rows"][0][2] == "791,405억원 (+10.0%)"  # 채팅 표와 같은 계산
    assert table["rows"][1][2] == "66,853억원 (+1.2%)"
    assert table["rows"][3][2] == "8.4% (-0.7%p)"
    assert table["rows"][4][2] == "860,000억원 (+8.9%)"
    assert table["rows"][5][2] == "-"  # 그 분기에 영업이익 행이 없다
    assert "연결" in table["note"]


def test_상세_표의_4분기는_연간에서_1_2_3분기를_뺀다():
    table = build_detail_table([SEC], {"005930": SEC_2025})

    assert [r[0] for r in table["rows"][::4]] == [
        "2025년 4분기",
        "2025년 3분기",
        "2025년 2분기",
        "2025년 1분기",
    ]
    assert table["rows"][1] == ["2025년 4분기", "영업이익", "200,737억원 (+209.2%)"]


def test_상세_표에서_분기_보고서가_모자라면_4분기는_표시하지_않는다():
    without_q2 = [r for r in SEC_2025 if r["reprt_code"] != "11012"]

    table = build_detail_table([SEC], {"005930": without_q2})

    assert ["2025년 4분기", "영업이익", "-"] in table["rows"]


def test_상세_표에서_공시가_없는_분기는_미공시다():
    hynix_q2 = _rows("000660", 2026, "11012", op=(5_000_000_000_000, 3_000_000_000_000))

    table = build_detail_table([SEC, HYNIX], {"005930": SEC_Q1, "000660": hynix_q2})

    assert table["columns"] == ["기간", "항목", "삼성전자", "SK하이닉스"]
    assert table["rows"][1] == ["2026년 2분기", "영업이익", "미공시", "50,000억원 (+66.7%)"]
    assert table["rows"][5] == ["2026년 1분기", "영업이익", "66,853억원 (+1.2%)", "미공시"]


def test_상세_표도_한_회사라도_연결이_없으면_모두_별도로_맞춘다():
    sec_both = SEC_Q1 + _rows(
        "005930", 2026, "11013", fs_div="OFS", op=(1_469_273_000_000, 2_007_380_000_000)
    )
    hynix_ofs_only = _rows(
        "000660", 2026, "11013", fs_div="OFS", op=(2_000_000_000_000, 1_000_000_000_000)
    )

    table = build_detail_table([SEC, HYNIX], {"005930": sec_both, "000660": hynix_ofs_only})

    assert "별도" in table["note"]
    assert table["rows"][1][2:] == ["14,693억원 (-26.8%)", "20,000억원 (+100.0%)"]


def test_상세_표에서_데이터가_없는_기업은_note에_미공시로_적는다():
    table = build_detail_table([SEC, HYNIX], {"005930": SEC_Q1, "000660": []})

    assert table["rows"][1] == ["2026년 1분기", "영업이익", "66,853억원 (+1.2%)", "미공시"]
    assert "SK하이닉스 미공시" in table["note"]


def test_상세_표에서_데이터가_하나도_없으면_None():
    assert build_detail_table([SEC, HYNIX], {"005930": [], "000660": []}) is None


def test_경쟁사는_같은_업종에서_자기를_뺀_목록이다():
    assert find_competitors("005930") == ("반도체", ["000660"])
    assert find_competitors("005380") == ("자동차", ["000270", "012330"])


def test_경쟁사_목록에_없는_기업은_None():
    assert find_competitors("015760") is None  # 한국전력


def test_경쟁사_목록은_수집_대상이고_업종은_2에서_최대_개수이며_기업은_한_업종에만_있다():
    collected = {stock for stock, _ in load_companies()}
    members = [stock for group in COMPETITOR_GROUPS.values() for stock in group]
    assert set(members) <= collected
    assert all(2 <= len(group) <= MAX_COMPANIES for group in COMPETITOR_GROUPS.values())
    assert len(members) == len(set(members))
