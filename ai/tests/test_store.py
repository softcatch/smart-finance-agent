from app.store import ensure_schema, upsert_companies, upsert_financials


def _row(**kw):
    base = {
        "stock_code": "005930",
        "bsns_year": 2025,
        "reprt_code": "11013",
        "fs_div": "CFS",
        "account_nm": "영업이익",
        "amount": 6_600_000_000_000,
        "add_amount": 6_600_000_000_000,
        "prev_amount": 640_000_000_000,
        "rcept_no": "20250515000001",
    }
    return {**base, **kw}


def _all(conn):
    return conn.execute(
        "SELECT stock_code, bsns_year, reprt_code, fs_div, account_nm, amount, prev_amount, rcept_no "
        "FROM financial ORDER BY stock_code, bsns_year, reprt_code, fs_div, account_nm"
    ).fetchall()


def test_스키마를_여러_번_만들어도_된다(conn):
    ensure_schema(conn)
    ensure_schema(conn)


def test_재무_행을_넣고_읽는다(conn):
    assert upsert_financials(conn, [_row()]) == 1

    assert _all(conn) == [
        (
            "005930",
            2025,
            "11013",
            "CFS",
            "영업이익",
            6_600_000_000_000,
            640_000_000_000,
            "20250515000001",
        )
    ]


def test_같은_키를_다시_넣으면_덮어쓴다_정정_공시(conn):
    upsert_financials(conn, [_row()])

    upsert_financials(conn, [_row(amount=6_700_000_000_000, rcept_no="20250601000009")])

    rows = _all(conn)
    assert len(rows) == 1
    assert rows[0][5] == 6_700_000_000_000
    assert rows[0][7] == "20250601000009"  # 정정본의 접수번호


def test_키가_다르면_별개_행이다(conn):
    upsert_financials(
        conn,
        [
            _row(fs_div="CFS"),
            _row(fs_div="OFS"),  # 연결·별도
            _row(account_nm="매출액"),
            _row(reprt_code="11012"),
            _row(stock_code="000660"),
        ],
    )

    assert len(_all(conn)) == 5


def test_금액이_없어도_넣는다(conn):
    upsert_financials(conn, [_row(amount=None, add_amount=None, prev_amount=None)])

    assert _all(conn)[0][5] is None


def test_기업을_넣고_이름이_바뀌면_갱신한다(conn):
    upsert_companies(conn, [("005930", "00126380", "삼성전자")])
    upsert_companies(conn, [("005930", "00126380", "삼성전자(주)")])

    rows = conn.execute("SELECT stock_code, corp_code, name FROM company").fetchall()
    assert rows == [("005930", "00126380", "삼성전자(주)")]
