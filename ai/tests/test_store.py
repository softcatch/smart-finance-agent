import psycopg
import pytest

from app.store import (
    business_rcept_no,
    ensure_schema,
    find_companies,
    load_detail,
    load_financials,
    replace_business_chunks,
    save_detail,
    search_business_chunks,
    upsert_companies,
    upsert_financials,
    write_ai_usage,
    write_chat_log,
)


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


def test_정정본에서_빠진_계정과_재무제표는_남기지_않는다(conn):
    # 원본: 연결·별도 영업이익과 매출액. 정정본: 별도 영업이익만 남음(FR-022 — 같은 회사·기간을 통째로 바꾼다)
    upsert_financials(
        conn, [_row(fs_div="CFS"), _row(fs_div="OFS"), _row(fs_div="CFS", account_nm="매출액")]
    )

    upsert_financials(conn, [_row(fs_div="OFS", amount=1)])

    rows = _all(conn)
    assert len(rows) == 1
    assert (rows[0][3], rows[0][4], rows[0][5]) == ("OFS", "영업이익", 1)


def test_다른_기간과_다른_회사의_행은_건드리지_않는다(conn):
    upsert_financials(
        conn,
        [_row(), _row(reprt_code="11012"), _row(bsns_year=2024), _row(stock_code="000660")],
    )

    upsert_financials(conn, [_row(amount=5)])  # 005930·2025·11013만 교체

    assert len(_all(conn)) == 4


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


def _seed_company(conn):
    upsert_companies(
        conn,
        [
            ("005930", "00126380", "삼성전자"),
            ("000660", "00164779", "SK하이닉스"),
            ("035420", "00266961", "NAVER"),
        ],
    )


def test_기업을_이름으로_찾는다_공백과_대소문자는_무시(conn):
    _seed_company(conn)

    found, missing = find_companies(conn, ["삼성 전자", "sk하이닉스", "naver"])

    assert found == [("005930", "삼성전자"), ("000660", "SK하이닉스"), ("035420", "NAVER")]
    assert missing == []


def test_종목코드로도_찾고_말한_순서를_지키며_중복은_한_번(conn):
    _seed_company(conn)

    found, _ = find_companies(conn, ["000660", "삼성전자", "005930"])

    assert found == [("000660", "SK하이닉스"), ("005930", "삼성전자")]


def test_못_찾은_이름은_그대로_돌려준다(conn):
    _seed_company(conn)

    found, missing = find_companies(conn, ["삼성전자", "없는회사", "삼전"])

    assert found == [("005930", "삼성전자")]
    assert missing == ["없는회사", "삼전"]  # 별칭은 아직 지원하지 않는다(NFR-044)


def test_회사별로_재무_행을_묶어_읽는다(conn):
    upsert_financials(
        conn,
        [
            _row(),
            _row(account_nm="매출액"),
            _row(stock_code="000660"),
            _row(stock_code="035420"),
        ],
    )

    rows = load_financials(conn, ["005930", "000660"])

    assert sorted(rows) == ["000660", "005930"]
    assert len(rows["005930"]) == 2
    assert {"bsns_year", "reprt_code", "fs_div", "account_nm", "amount", "prev_amount"} <= rows[
        "005930"
    ][0].keys()


def test_행이_없는_회사는_빈_목록(conn):
    assert load_financials(conn, ["005930"]) == {"005930": []}


def _vec(i: int, value: float = 1.0) -> list[float]:
    v = [0.0] * 1536
    v[i] = value
    return v


REPORT = {"rcept_no": "20260814003699", "report_nm": "반기보고서 (2026.06)"}


def test_사업의_내용을_넣고_접수번호를_읽는다(conn):
    assert business_rcept_no(conn, "005930") is None

    n = replace_business_chunks(
        conn,
        "005930",
        REPORT,
        [("1. 사업의 개요", "본문1", _vec(0)), ("2. 제품", "본문2", _vec(1))],
    )

    assert n == 2
    assert business_rcept_no(conn, "005930") == "20260814003699"
    rows = conn.execute(
        "SELECT seq, report_nm, section, content FROM business_chunk "
        "WHERE stock_code = '005930' ORDER BY seq"
    ).fetchall()
    assert rows == [
        (0, "반기보고서 (2026.06)", "1. 사업의 개요", "본문1"),
        (1, "반기보고서 (2026.06)", "2. 제품", "본문2"),
    ]


def test_새_보고서가_오면_그_회사만_통째로_바꾼다(conn):
    old = {"rcept_no": "20260515002181", "report_nm": "분기보고서 (2026.03)"}
    replace_business_chunks(
        conn, "005930", old, [("a", "x", _vec(0)), ("b", "y", _vec(1)), ("c", "z", _vec(2))]
    )
    replace_business_chunks(conn, "000660", old, [("a", "다른 회사", _vec(0))])

    replace_business_chunks(conn, "005930", REPORT, [("a", "새 본문", _vec(3))])

    assert business_rcept_no(conn, "005930") == "20260814003699"
    contents = conn.execute(
        "SELECT stock_code, content FROM business_chunk ORDER BY stock_code, seq"
    ).fetchall()
    assert contents == [("000660", "다른 회사"), ("005930", "새 본문")]


def test_넣다가_실패하면_기존_청크가_남는다(conn):
    old = {"rcept_no": "20260515002181", "report_nm": "분기보고서 (2026.03)"}
    replace_business_chunks(conn, "005930", old, [("a", "옛 본문", _vec(0))])

    with pytest.raises(psycopg.Error):  # 차원이 맞지 않는 벡터
        replace_business_chunks(conn, "005930", REPORT, [("a", "새 본문", [0.1, 0.2])])
    conn.rollback()

    assert business_rcept_no(conn, "005930") == "20260515002181"
    assert conn.execute("SELECT content FROM business_chunk").fetchall() == [("옛 본문",)]


def _three_chunks(conn):
    replace_business_chunks(
        conn,
        "005930",
        REPORT,
        [
            ("제품", "TV와 반도체", _vec(0)),
            ("수주", "수주잔고", _vec(1)),
            ("연구", "R&D 투자", _vec(2)),
        ],
    )


def test_질문_벡터와_가까운_청크부터_돌려준다(conn):
    _three_chunks(conn)

    rows = search_business_chunks(conn, "005930", _vec(1, 0.9), limit=2)

    assert len(rows) == 2
    assert rows[0] == {
        "section": "수주",
        "content": "수주잔고",
        "report_nm": "반기보고서 (2026.06)",
        "rcept_no": "20260814003699",
    }


def test_검색은_그_회사_청크만_본다(conn):
    _three_chunks(conn)
    replace_business_chunks(conn, "000660", REPORT, [("메모리", "다른 회사 본문", _vec(1))])

    contents = [r["content"] for r in search_business_chunks(conn, "005930", _vec(1))]

    assert contents[0] == "수주잔고"
    assert "다른 회사 본문" not in contents


def test_청크가_없는_회사는_빈_목록(conn):
    assert search_business_chunks(conn, "005930", _vec(0)) == []


def test_채팅_로그를_남긴다(conn):
    write_chat_log(conn, "th_1", "PASS", {"extract": 120, "answer": 2500})

    row = conn.execute("SELECT thread_id, guard, node_ms FROM chat_log").fetchone()

    assert row == ("th_1", "PASS", {"extract": 120, "answer": 2500})


def test_로그_테이블이_아직_없어도_만들어서_남긴다(conn):
    conn.execute("DROP TABLE chat_log")
    conn.commit()

    write_chat_log(conn, "th_1", "ADVICE", {"answer": 10})

    assert conn.execute("SELECT guard FROM chat_log").fetchall() == [("ADVICE",)]


def test_상세_보기_조건을_저장하고_읽는다(conn):
    save_detail(conn, "dt_1", "financial", ["005930", "000660"])

    assert load_detail(conn, "dt_1") == {"kind": "financial", "stock_codes": ["005930", "000660"]}


def test_없는_상세_보기는_None(conn):
    assert load_detail(conn, "dt_없음") is None


def test_상세_보기_테이블이_아직_없어도_만들어서_저장하고_읽기는_None(conn):
    conn.execute("DROP TABLE detail_condition")
    conn.commit()
    assert load_detail(conn, "dt_1") is None  # 아직 없으니 하나도 없다

    save_detail(conn, "dt_1", "financial", ["005930"])

    assert load_detail(conn, "dt_1")["stock_codes"] == ["005930"]


def test_AI_사용량을_호출마다_한_줄씩_남긴다(conn):
    write_ai_usage(conn, 7, "gpt-5-nano", 1529, 550)
    write_ai_usage(conn, 7, "gpt-5-nano", 300, 40)

    rows = conn.execute(
        "SELECT user_id, model, input_tokens, output_tokens, fallback FROM ai_usage ORDER BY id"
    ).fetchall()

    assert rows == [(7, "gpt-5-nano", 1529, 550, False), (7, "gpt-5-nano", 300, 40, False)]


def test_대체_호출은_대체_여부가_참으로_남는다(conn):
    write_ai_usage(conn, 7, "claude-haiku-5-5", 120, 30, fallback=True)

    assert conn.execute("SELECT model, fallback FROM ai_usage").fetchall() == [
        ("claude-haiku-5-5", True)
    ]


def test_사용량_테이블이_아직_없어도_만들어서_남긴다(conn):
    conn.execute("DROP TABLE ai_usage")
    conn.commit()

    write_ai_usage(conn, 7, "gpt-5-nano", 10, 5)

    assert conn.execute("SELECT user_id, input_tokens FROM ai_usage").fetchall() == [(7, 10)]
