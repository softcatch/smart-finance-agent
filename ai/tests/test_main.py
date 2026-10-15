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
    main.get_graph.cache_clear()  # lru_cache — 환경변수를 바꿔 가며 다시 만든다
    yield
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
