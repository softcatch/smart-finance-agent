from uuid import uuid4

import httpx
import openai
import pytest
from fastapi.testclient import TestClient
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.language_models.fake_chat_models import FakeListChatModel
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, ChatResult, LLMResult
from langchain_core.runnables import RunnableLambda
from prometheus_client import REGISTRY

from app.business import embed_texts
from app.graph import build_graph
from app.main import app, get_graph
from app.metrics import LlmMetrics
from app.transfer import TransferSlots

# sub가 "7"·"8"인 JWT 모양(헤더.payload.서명). 서명은 FastAPI가 보지 않는다.
BEARER_7 = "Bearer h.eyJzdWIiOiI3In0.s"
BEARER_8 = "Bearer h.eyJzdWIiOiI4In0.s"


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


def test_LLM_토큰을_입력과_출력으로_모델별로_센다():
    usage = {"input_tokens": 120, "output_tokens": 30, "total_tokens": 150}
    result = LLMResult(
        generations=[[ChatGeneration(message=AIMessage(content="응답", usage_metadata=usage))]]
    )
    labels = [("input", "gpt-5-nano"), ("output", "gpt-5-nano"), ("input", "other")]
    before = [_value("llm_tokens_total", direction=d, model=m) for d, m in labels]
    handler, run_id = LlmMetrics(), uuid4()

    handler.on_chat_model_start(None, [], run_id=run_id, metadata={"ls_model_name": "gpt-5-nano"})
    handler.on_llm_end(result, run_id=run_id)

    after = [_value("llm_tokens_total", direction=d, model=m) for d, m in labels]
    assert [a - b for a, b in zip(after, before, strict=True)] == [120, 30, 0]


# 모델 이름과 토큰 수를 돌려주는 가짜 채팅 모델
class _Usage(BaseChatModel):
    model_name: str = "gpt-5-nano"

    @property
    def _llm_type(self) -> str:
        return "usage"

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        usage = {"input_tokens": 120, "output_tokens": 30, "total_tokens": 150}
        return ChatResult(
            generations=[ChatGeneration(message=AIMessage("응답", usage_metadata=usage))]
        )


def test_사용자가_있는_호출은_모델과_토큰을_기록한다():
    calls = []
    llm = _Usage(callbacks=[LlmMetrics(lambda *args: calls.append(args))])

    llm.invoke("질문", config={"metadata": {"user_id": 7}})
    llm.invoke("질문", config={"metadata": {"user_id": 8}})

    assert calls == [(7, "gpt-5-nano", 120, 30, False), (8, "gpt-5-nano", 120, 30, False)]


def test_대체용_모델의_호출은_대체_여부가_참으로_남는다():
    calls = []
    llm = _Usage(
        model_name="claude-haiku-5-5",
        callbacks=[LlmMetrics(lambda *args: calls.append(args), fallback=True)],
    )

    llm.invoke("질문", config={"metadata": {"user_id": 7}})

    assert calls == [(7, "claude-haiku-5-5", 120, 30, True)]


def test_대체용_모델의_오류는_OpenAI_오류_지표에_섞이지_않는다():
    class Boom(_Usage):
        def _generate(self, messages, stop=None, run_manager=None, **kwargs):
            request = httpx.Request("POST", "http://anthropic")
            raise openai.RateLimitError(
                "한도", response=httpx.Response(429, request=request), body=None
            )

    errors = _value("openai_errors_total", type="RateLimitError")
    failed = _value("llm_call_seconds_count", outcome="error")

    with pytest.raises(openai.RateLimitError):
        Boom(callbacks=[LlmMetrics(fallback=True)]).invoke("질문")

    assert _value("openai_errors_total", type="RateLimitError") == errors  # 센 것은 호출 시간뿐이다
    assert _value("llm_call_seconds_count", outcome="error") == failed + 1


def test_사용자가_없는_호출은_기록하지_않고_지표만_센다():
    calls = []
    llm = _Usage(callbacks=[LlmMetrics(lambda *args: calls.append(args))])
    ok = _value("llm_call_seconds_count", outcome="ok")

    llm.invoke("질문")  # 수집기·평가 스크립트처럼 사용자가 없는 호출

    assert calls == []
    assert _value("llm_call_seconds_count", outcome="ok") == ok + 1


def test_오류로_끝난_호출은_사용량을_기록하지_않는다():
    calls = []

    class Boom(_Usage):
        def _generate(self, messages, stop=None, run_manager=None, **kwargs):
            raise RuntimeError("실패")

    with pytest.raises(RuntimeError):
        Boom(callbacks=[LlmMetrics(lambda *args: calls.append(args))]).invoke(
            "질문", config={"metadata": {"user_id": 7}}
        )

    assert calls == []


def test_기록이_실패해도_호출은_성공하고_지표는_센다():
    def broken(*args):
        raise RuntimeError("DB 장애")

    ok = _value("llm_call_seconds_count", outcome="ok")

    reply = _Usage(callbacks=[LlmMetrics(broken)]).invoke(
        "질문", config={"metadata": {"user_id": 7}}
    )

    assert reply.content == "응답"
    assert _value("llm_call_seconds_count", outcome="ok") == ok + 1


def test_채팅_한_턴의_모든_LLM_호출이_요청한_회원의_ID로_남는다(monkeypatch):
    users = []
    llm = FakeListChatModel(
        # 첫 답변의 숫자가 근거에 없어 재검색한다: 답변 → 재작성 → 재답변, 한 턴에 LLM 호출 3번
        responses=["수주잔고는 1,500억원입니다.", "수주잔고 현황", "수주잔고는 1,000억원입니다."],
        callbacks=[LlmMetrics(lambda *args: users.append(args[0]))],
    )
    extractor = RunnableLambda(
        lambda _: TransferSlots(is_transfer=False, is_business=True, companies=["삼성전자"])
    )
    chunk = {
        "section": "4. 매출 및 수주상황",
        "content": "수주잔고 1,000억원",
        "report_nm": "반기보고서",
        "rcept_no": "1",
    }
    monkeypatch.setattr(
        "app.main.search_business",
        lambda names, question: ([("005930", "삼성전자")], [], {"005930": [chunk]}),
    )
    app.dependency_overrides[get_graph] = lambda: build_graph(llm, extractor)
    body = {"threadId": "th_u", "message": "삼성전자 수주잔고"}
    try:
        TestClient(app).post("/internal/chat", json=body, headers={"Authorization": BEARER_7})
        TestClient(app).post("/internal/chat", json=body, headers={"Authorization": BEARER_8})
        TestClient(app).post("/internal/chat", json=body)  # 사용자를 모르면 남기지 않는다
    finally:
        app.dependency_overrides.clear()

    assert users == [7, 7, 7, 8, 8, 8]
