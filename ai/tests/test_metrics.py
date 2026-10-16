import httpx
import openai
import pytest
from fastapi.testclient import TestClient
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.language_models.fake_chat_models import FakeListChatModel
from langchain_core.runnables import RunnableLambda
from prometheus_client import REGISTRY

from app.business import embed_texts
from app.graph import build_graph
from app.main import app, get_graph
from app.metrics import LlmMetrics
from app.transfer import TransferSlots


def _value(name: str, **labels) -> float:
    return REGISTRY.get_sample_value(name, labels) or 0.0


def test_채팅_한_턴이_HTTP_지연과_노드_시간과_가드_판정을_남긴다():
    app.dependency_overrides[get_graph] = lambda: build_graph(
        FakeListChatModel(responses=["답변입니다"]),
        RunnableLambda(lambda _: TransferSlots(is_transfer=False)),
    )
    http = {"method": "POST", "path": "/internal/chat", "status": "200"}
    before = (
        _value("http_request_seconds_count", **http),
        _value("chat_node_seconds_count", node="answer"),
        _value("guard_decisions_total", where="chat", code="PASS"),
    )
    try:
        TestClient(app).post("/internal/chat", json={"threadId": "th_m", "message": "안녕"})
    finally:
        app.dependency_overrides.clear()

    after = (
        _value("http_request_seconds_count", **http),
        _value("chat_node_seconds_count", node="answer"),
        _value("guard_decisions_total", where="chat", code="PASS"),
    )
    assert [a - b for a, b in zip(after, before, strict=True)] == [1, 1, 1]
    assert _value("http_requests_in_flight") == 0  # 끝난 요청은 처리 중에서 빠진다


def test_metrics는_스레드풀_상한과_처리_중_요청을_내보낸다():
    body = TestClient(app).get("/metrics").text

    assert "threadpool_limit 40.0" in body  # anyio 기본 스레드풀 — 부하 테스트에서 찾은 상한
    assert "threadpool_borrowed " in body
    assert "http_requests_in_flight " in body


def test_LLM_호출은_시간을_세고_오류는_종류별로_센다():
    ok = _value("llm_call_seconds_count", outcome="ok")
    FakeListChatModel(responses=["응답"], callbacks=[LlmMetrics()]).invoke("질문")
    assert _value("llm_call_seconds_count", outcome="ok") == ok + 1

    class Boom(BaseChatModel):
        @property
        def _llm_type(self) -> str:
            return "boom"

        def _generate(self, messages, stop=None, run_manager=None, **kwargs):
            request = httpx.Request("POST", "http://openai")
            raise openai.RateLimitError(
                "한도", response=httpx.Response(429, request=request), body=None
            )

    failed = _value("llm_call_seconds_count", outcome="error")
    errors = _value("openai_errors_total", type="RateLimitError")
    with pytest.raises(openai.RateLimitError):
        Boom(callbacks=[LlmMetrics()]).invoke("질문")

    assert _value("llm_call_seconds_count", outcome="error") == failed + 1
    assert _value("openai_errors_total", type="RateLimitError") == errors + 1


def test_임베딩_호출은_시간을_세고_OpenAI_오류는_종류별로_센다(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "fake")
    calls = _value("embedding_call_seconds_count")
    monkeypatch.setattr(
        "app.business.OpenAIEmbeddings.embed_documents", lambda self, texts: [[0.0]]
    )
    embed_texts(["삼성전자 사업"])
    assert _value("embedding_call_seconds_count") == calls + 1

    def refuse(self, texts):
        raise openai.APIConnectionError(request=httpx.Request("POST", "http://openai"))

    errors = _value("openai_errors_total", type="APIConnectionError")
    monkeypatch.setattr("app.business.OpenAIEmbeddings.embed_documents", refuse)
    with pytest.raises(openai.APIConnectionError):
        embed_texts(["삼성전자 사업"])

    assert _value("openai_errors_total", type="APIConnectionError") == errors + 1
