from typing import ClassVar

import pytest
from langchain_core.runnables import RunnableLambda

from app import main
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


def test_추론_강도는_기본이_minimal(monkeypatch):
    monkeypatch.delenv("OPENAI_REASONING_EFFORT", raising=False)

    main.get_graph()

    assert FakeOpenAI.kwargs["reasoning_effort"] == "minimal"
    assert FakeOpenAI.kwargs["max_retries"] == 0  # 429 재시도 없음은 그대로


def test_환경변수로_추론_강도를_바꾼다(monkeypatch):
    monkeypatch.setenv("OPENAI_REASONING_EFFORT", "low")

    main.get_graph()

    assert FakeOpenAI.kwargs["reasoning_effort"] == "low"


def test_빈_값이면_추론_강도를_보내지_않는다(monkeypatch):
    # 추론을 쓰지 않는 모델에 reasoning_effort를 보내면 400이다 — 비우면 인자를 빼서 끈다.
    monkeypatch.setenv("OPENAI_REASONING_EFFORT", "")

    main.get_graph()

    assert "reasoning_effort" not in FakeOpenAI.kwargs
