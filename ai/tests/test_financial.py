from app.financial import build_table, period_label

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


def test_사업보고서는_연간으로_표시한다():
    rows = _rows("005930", 2025, "11011", op=(43_601_051_000_000, 32_725_961_000_000))

    table = build_table([SEC], {"005930": rows})

    assert "2025년 연간" in table["note"]


def test_분기_순서로_가장_최근을_고른다():
    older_year_annual = _rows("005930", 2025, "11011", op=(1, 1))
    q1_2026 = _rows("005930", 2026, "11013", op=(2_000_000_000_000, 1_000_000_000_000))

    table = build_table([SEC], {"005930": older_year_annual + q1_2026})

    assert "2026년 1분기" in table["note"]


def test_데이터가_하나도_없으면_None():
    assert build_table([SEC, HYNIX], {"005930": [], "000660": []}) is None
