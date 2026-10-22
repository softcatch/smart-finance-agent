import base64
import json
from typing import ClassVar

import psycopg
import pytest
from langchain_core.runnables import RunnableLambda

from app import main
from app.main import log_chat as real_log_chat
from app.store import replace_business_chunks, upsert_companies
from app.transfer import TransferSlots


# ChatOpenAI 자리에 끼워 넣는 가짜: 모델을 만들 때 받은 인자를 기록한다.
class FakeOpenAI:
    kwargs: ClassVar[dict] = {}

    def __init__(self, **kwargs):
        FakeOpenAI.kwargs = kwargs

    def with_structured_output(self, schema):
        return RunnableLambda(lambda _: TransferSlots(is_transfer=False))


@pytest.fixture(autouse=True)
def fake_openai(monkeypatch):
    monkeypatch.setattr(main, "ChatOpenAI", FakeOpenAI)
    main.get_llm.cache_clear()  # lru_cache — 환경변수를 바꿔 가며 다시 만든다
    main.get_graph.cache_clear()
    yield
    main.get_llm.cache_clear()
    main.get_graph.cache_clear()


def test_추론_강도는_기본이_low(monkeypatch):
    monkeypatch.delenv("OPENAI_REASONING_EFFORT", raising=False)

    main.get_graph()

    assert FakeOpenAI.kwargs["reasoning_effort"] == "low"
    assert FakeOpenAI.kwargs["max_retries"] == 0  # 429 재시도 없음은 그대로


def test_환경변수로_추론_강도를_바꾼다(monkeypatch):
    monkeypatch.setenv("OPENAI_REASONING_EFFORT", "minimal")

    main.get_graph()

    assert FakeOpenAI.kwargs["reasoning_effort"] == "minimal"


def test_빈_값이면_추론_강도를_보내지_않는다(monkeypatch):
    # 추론을 쓰지 않는 모델에 reasoning_effort를 보내면 400이다 — 비우면 인자를 빼서 끈다.
    monkeypatch.setenv("OPENAI_REASONING_EFFORT", "")

    main.get_graph()

    assert "reasoning_effort" not in FakeOpenAI.kwargs


def _vec(i: int) -> list[float]:
    v = [0.0] * 1536
    v[i] = 1.0
    return v


REPORT = {"rcept_no": "20260814003699", "report_nm": "반기보고서 (2026.06)"}


# 실제 PostgreSQL(pgvector)로 확인한다. 연결만 테스트 DB로 바꾸고, 질문 임베딩은 가짜로 둔다.
def test_사업의_내용_검색은_찾은_기업의_청크만_질문_벡터로_가져온다(
    conn, database_url, monkeypatch
):
    upsert_companies(
        conn, [("005930", "00126380", "삼성전자"), ("000660", "00164779", "SK하이닉스")]
    )
    replace_business_chunks(
        conn, "005930", REPORT, [("개요", "TV와 반도체", _vec(0)), ("제품", "메모리 제품", _vec(1))]
    )
    replace_business_chunks(conn, "000660", REPORT, [("제품", "다른 회사", _vec(1))])
    asked = []
    monkeypatch.setattr(main, "_connect", lambda: psycopg.connect(database_url))
    monkeypatch.setattr(main, "embed_texts", lambda texts: asked.append(texts) or [_vec(1)])

    companies, missing, chunks = main.search_business(["삼성전자", "삼전"], "주요 제품이 뭐야?")

    assert companies == [("005930", "삼성전자")]
    assert missing == ["삼전"]
    assert asked == [["주요 제품이 뭐야?"]]
    assert [c["content"] for c in chunks["005930"]] == ["메모리 제품", "TV와 반도체"]


def test_찾을_기업이_없으면_질문을_임베딩하지_않는다(conn, database_url, monkeypatch):
    upsert_companies(conn, [("005930", "00126380", "삼성전자")])
    monkeypatch.setattr(main, "_connect", lambda: psycopg.connect(database_url))

    def embed(texts):
        raise AssertionError("호출되면 안 됨")

    monkeypatch.setattr(main, "embed_texts", embed)

    assert main.search_business(["삼전"], "사업 내용") == ([], ["삼전"], {})


# 개발 DB를 건드리지 않게 conftest가 main.log_chat을 꺼 두므로, 모듈을 불러올 때 잡아 둔 원래 함수를 쓴다.
def test_채팅_로그는_연결만_바꿔_실제_DB에_남는다(conn, database_url, monkeypatch):
    monkeypatch.setattr(main, "_connect", lambda: psycopg.connect(database_url))

    real_log_chat("th_9", "NUMBER_UNVERIFIED", {"business": 4100})

    assert conn.execute("SELECT thread_id, guard, node_ms FROM chat_log").fetchall() == [
        ("th_9", "NUMBER_UNVERIFIED", {"business": 4100})
    ]


def test_로그를_못_써도_예외가_나가지_않는다(monkeypatch):
    def down():
        raise psycopg.OperationalError("connection refused")

    monkeypatch.setattr(main, "_connect", down)

    real_log_chat("th_1", "PASS", {})  # 채팅은 로그 때문에 실패하지 않는다


def test_모델은_사용량_기록_함수가_연결된_핸들러를_쓴다():
    main.get_llm()

    (handler,) = FakeOpenAI.kwargs["callbacks"]

    assert handler.record is main.record_usage


def test_사용량은_연결만_바꿔_실제_DB에_남는다(conn, database_url, monkeypatch):
    monkeypatch.setattr(main, "_connect", lambda: psycopg.connect(database_url))

    main.record_usage(7, "gpt-5-nano", 1529, 550)

    assert conn.execute(
        "SELECT user_id, model, input_tokens, output_tokens FROM ai_usage"
    ).fetchall() == [(7, "gpt-5-nano", 1529, 550)]


def test_사용량을_못_써도_예외가_나가지_않는다(monkeypatch):
    def down():
        raise psycopg.OperationalError("connection refused")

    monkeypatch.setattr(main, "_connect", down)

    main.record_usage(7, "gpt-5-nano", 10, 5)  # 채팅은 사용량 기록 때문에 실패하지 않는다


def _bearer(claims: dict) -> str:
    # Spring이 보내는 JWT 모양(헤더.payload.서명, 패딩 없는 base64url). 서명은 FastAPI가 보지 않는다.
    payload = base64.urlsafe_b64encode(json.dumps(claims).encode()).rstrip(b"=").decode()
    return f"Bearer eyJhbGciOiJIUzI1NiJ9.{payload}.c2ln"


def test_JWT의_sub에서_회원_ID를_읽는다():
    assert main.member_id(_bearer({"sub": "7", "exp": 1790000000})) == 7


def test_회원_ID를_못_읽으면_None():
    broken = [
        None,  # Authorization 헤더 없음
        "Bearer t",  # JWT 모양이 아님
        "Bearer a.!!!.c",  # payload가 base64가 아님
        _bearer({"exp": 1}),  # sub 없음
        _bearer({"sub": "abc"}),  # 숫자가 아님
        "Bearer a." + base64.urlsafe_b64encode(b"[1]").decode() + ".c",  # payload가 객체가 아님
    ]

    assert [main.member_id(value) for value in broken] == [None] * len(broken)
