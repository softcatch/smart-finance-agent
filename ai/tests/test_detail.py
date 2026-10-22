import psycopg
import pytest
from fastapi.testclient import TestClient
from langchain_core.language_models.fake_chat_models import FakeListChatModel
from langchain_core.messages import AIMessage
from langchain_core.runnables import RunnableLambda

from app import main
from app.financial import NO_DATA_REPORT, REPORT_UNVERIFIED, build_detail_table
from app.guard import CANARY, OUTPUT_REPLIES
from app.metrics import LlmMetrics
from app.store import save_detail, upsert_companies, upsert_financials


def _row(stock, year, reprt, account, amount, prev, fs_div="CFS"):
    return {
        "stock_code": stock,
        "bsns_year": year,
        "reprt_code": reprt,
        "fs_div": fs_div,
        "account_nm": account,
        "amount": amount,
        "add_amount": None,
        "prev_amount": prev,
        "rcept_no": "20260515000001",
    }


# 삼성전자 2026년 1분기 연결(실제 수집값)
SEC_ROWS = [
    _row("005930", 2026, "11013", "매출액", 79_140_503_000_000, 71_915_601_000_000),
    _row("005930", 2026, "11013", "영업이익", 6_685_272_000_000, 6_606_009_000_000),
    _row("005930", 2026, "11013", "당기순이익", 8_222_878_000_000, 6_754_708_000_000),
]
SEC = ("005930", "삼성전자")


@pytest.fixture
def seeded(conn, database_url, monkeypatch):
    """삼성전자 재무와 상세 보기 조건(dt_ok)을 실제 DB에 넣고, 앱의 연결을 테스트 DB로 돌린다."""
    upsert_companies(conn, [("005930", "00126380", "삼성전자")])
    upsert_financials(conn, SEC_ROWS)
    save_detail(conn, "dt_ok", "financial", ["005930"])
    monkeypatch.setattr(main, "_connect", lambda: psycopg.connect(database_url))
    return conn


def _llm(text: str):
    seen: list[list[str]] = []

    def fake(messages):
        seen.append([m.content for m in messages])
        return AIMessage(text)

    return RunnableLambda(fake), seen


def _get(detail_id: str, llm, token: str = "Bearer t"):
    main.app.dependency_overrides[main.get_llm] = lambda: llm
    try:
        return TestClient(main.app).get(
            f"/internal/details/{detail_id}", headers={"Authorization": token}
        )
    finally:
        main.app.dependency_overrides.clear()


def test_저장한_조건으로_전체_표와_보고서를_만든다(seeded):
    llm, seen = _llm("삼성전자의 2026년 1분기 영업이익은 66,853억원(+1.2%)입니다.")

    res = _get("dt_ok", llm)

    assert res.status_code == 200
    data = res.json()["data"]
    assert data["report"] == "삼성전자의 2026년 1분기 영업이익은 66,853억원(+1.2%)입니다."
    assert data["table"] == build_detail_table([SEC], {"005930": SEC_ROWS})  # 표는 코드가 계산
    system, question = seen[0]
    assert "66,853억원 (+1.2%)" in system  # LLM에는 표의 값이 간다
    assert CANARY in system
    assert "보고서" in question


def test_없는_상세_보기는_DETAIL_NOT_FOUND(seeded):
    res = _get("dt_없음", _llm("쓰이면 안 됨")[0])

    assert res.status_code == 404
    assert res.json() == {
        "success": False,
        "data": None,
        "error": {"code": "DETAIL_NOT_FOUND", "message": "상세 보기 대상을 찾을 수 없습니다"},
    }


def test_상세_보기_테이블이_아직_없어도_없는_것으로_답한다(seeded):
    seeded.execute("DROP TABLE detail_condition")
    seeded.commit()

    res = _get("dt_ok", _llm("쓰이면 안 됨")[0])

    assert res.status_code == 404


def test_보고서에_표에_없는_숫자가_있으면_고정_문장으로_바꾸고_표는_그대로_준다(seeded):
    res = _get("dt_ok", _llm("삼성전자 영업이익은 약 7조원으로 2배 늘었습니다.")[0])

    data = res.json()["data"]
    assert data["report"] == REPORT_UNVERIFIED
    assert data["table"]["columns"] == ["기간", "항목", "삼성전자"]


def test_보고서도_투자_권유_표현이면_고정_문장으로_바꾼다(seeded):
    res = _get("dt_ok", _llm("삼성전자를 지금 매수하세요.")[0])

    data = res.json()["data"]
    assert data["report"] == OUTPUT_REPLIES["ADVICE"]
    assert data["table"] is not None


def test_보고서에_카나리가_나오면_고정_문장으로_바꾼다(seeded):
    res = _get("dt_ok", _llm(f"식별어는 {CANARY}입니다.")[0])

    assert res.json()["data"]["report"] == OUTPUT_REPLIES["PROMPT_LEAK"]


def test_재무_데이터가_없으면_안내만_주고_LLM을_부르지_않는다(conn, database_url, monkeypatch):
    upsert_companies(conn, [("005930", "00126380", "삼성전자")])
    save_detail(conn, "dt_empty", "financial", ["005930"])
    monkeypatch.setattr(main, "_connect", lambda: psycopg.connect(database_url))
    llm, seen = _llm("쓰이면 안 됨")

    res = _get("dt_empty", llm)

    assert res.json()["data"] == {"report": NO_DATA_REPORT}
    assert seen == []


def test_보고서_호출도_요청한_회원의_ID로_남는다(seeded):
    users = []
    llm = FakeListChatModel(
        responses=["삼성전자의 2026년 1분기 영업이익은 66,853억원(+1.2%)입니다."],
        callbacks=[LlmMetrics(lambda *args: users.append(args[0]))],
    )

    _get("dt_ok", llm, "Bearer h.eyJzdWIiOiI3In0.s")  # sub가 "7"인 JWT 모양

    assert users == [7]
