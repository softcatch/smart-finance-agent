import time

import openai
import pytest
from langchain_anthropic.chat_models import AnthropicAPIError
from langchain_core.messages import HumanMessage, SystemMessage
from langchain_core.runnables import RunnableLambda
from langchain_openai import ChatOpenAI

from app import main
from app.transfer import TransferSlots
from loadtest.fake_llm import ANSWERS, KINDS, NO_FAULTS, Faults, create_app, serve

ONE_MS = {kind: [1] for kind in KINDS}


@pytest.fixture
def servers(monkeypatch):
    """가짜 LLM(OpenAI·Anthropic 경로)을 실제 포트에 띄우고 앱의 환경변수를 거기로 돌린다.
    돌려주는 start()는 사용량 기록(record_usage 인자)을 모은 목록을 돌려준다."""
    stops, records = [], []
    monkeypatch.setattr(main, "record_usage", lambda *args: records.append(args))

    def start(openai_faults=NO_FAULTS, anthropic_faults=NO_FAULTS, claude=True):
        url, stop = serve(create_app(ONE_MS, 1, openai=openai_faults, anthropic=anthropic_faults))
        stops.append(stop)
        monkeypatch.setenv("OPENAI_API_KEY", "fake")
        monkeypatch.setenv("OPENAI_BASE_URL", url)
        monkeypatch.setenv("ANTHROPIC_API_URL", url.removesuffix("/v1"))
        if claude:
            monkeypatch.setenv("ANTHROPIC_API_KEY", "fake")
        return records

    yield start
    for stop in stops:
        stop()


def _ask(chain):
    return chain.with_config(metadata={"user_id": 7}).invoke([HumanMessage("안녕")])


def test_정상이면_OpenAI가_답하고_Claude는_부르지_않는다(servers):
    records = servers()

    reply = _ask(main.make_chain("answer"))

    assert reply.content == ANSWERS["answer"]
    assert records == [(7, "gpt-5-nano", 0, 0, False)]


@pytest.mark.parametrize("status", [429, 500, 503])
def test_OpenAI가_429나_5xx면_Claude가_답하고_대체_호출로_남는다(servers, status):
    records = servers(openai_faults=Faults(error_rate=1.0, status=status))

    reply = _ask(main.make_chain("answer"))

    assert reply.content == ANSWERS["answer"]
    assert records == [(7, "claude-haiku-5-5", 0, 0, True)]  # 실패한 OpenAI 호출은 남지 않는다


def test_연결에_실패해도_Claude로_간다(servers, monkeypatch):
    records = servers()
    monkeypatch.setenv("OPENAI_BASE_URL", "http://127.0.0.1:1/v1")  # 연결 거부

    _ask(main.make_chain("answer"))

    assert records == [(7, "claude-haiku-5-5", 0, 0, True)]


@pytest.mark.parametrize(("kind", "schema"), [("answer", None), ("extract", TransferSlots)])
def test_타임아웃이면_기다리지_않고_Claude로_간다(servers, monkeypatch, kind, schema):
    monkeypatch.setattr(main, "TIMEOUTS", {**main.TIMEOUTS, kind: 0.3})
    records = servers(openai_faults=Faults(hang_rate=1.0, hang_s=3))

    start = time.perf_counter()
    main.make_chain(kind, schema).with_config(metadata={"user_id": 7}).invoke(
        [HumanMessage("안녕")]
    )

    assert time.perf_counter() - start < 2  # 3초 멈춤을 기다리지 않았다
    assert records == [(7, "claude-haiku-5-5", 0, 0, True)]


def test_요청_오류_400대는_대체하지_않는다(servers):
    records = servers(openai_faults=Faults(error_rate=1.0, status=401))

    with pytest.raises(openai.AuthenticationError):
        _ask(main.make_chain("answer"))

    assert records == []  # Claude를 부르지 않았다


def test_둘_다_실패하면_Claude의_오류가_올라와_채팅만_실패한다(servers):
    records = servers(
        openai_faults=Faults(error_rate=1.0, status=503),
        anthropic_faults=Faults(error_rate=1.0, status=503),
    )

    with pytest.raises(AnthropicAPIError):  # 대체의 오류는 다시 대체하지 않는다(한 번만 더 보낸다)
        _ask(main.make_chain("answer"))

    assert records == []


def test_키가_없으면_대체_없이_OpenAI만_쓴다(servers):
    servers(openai_faults=Faults(error_rate=1.0, status=503), claude=False)

    chain = main.make_chain("answer")

    assert isinstance(chain, ChatOpenAI)
    with pytest.raises(openai.InternalServerError):
        _ask(chain)


def test_의도_추출도_대체되고_같은_스키마가_나온다(servers):
    messages = [SystemMessage("추출"), HumanMessage("삼성전자 영업이익 알려줘")]
    servers()
    normal = main.make_chain("extract", TransferSlots).invoke(messages)
    servers(openai_faults=Faults(error_rate=1.0, status=503))  # 새 가짜 서버로 환경변수를 바꾼다

    fallback = main.make_chain("extract", TransferSlots).invoke(messages)

    assert isinstance(fallback, TransferSlots)
    assert fallback == normal
    assert fallback.is_financial and fallback.companies == ["삼성전자"]


def test_Claude는_키가_있을_때만_만들고_모델은_설정으로_바꾼다(monkeypatch):
    assert main.make_claude(5) is None

    monkeypatch.setenv("ANTHROPIC_API_KEY", "fake")
    claude = main.make_claude(5)
    monkeypatch.setenv("ANTHROPIC_MODEL", "claude-other")

    assert (claude.model, claude.max_retries, claude.default_request_timeout) == (
        "claude-haiku-5-5",
        0,
        5,
    )
    assert main.make_claude(5).model == "claude-other"
    (handler,) = claude.callbacks
    assert handler.fallback is True and handler.record is main.record_usage


def test_호출_종류마다_타임아웃이_다르고_대체_호출에도_같은_값이다(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "fake")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "fake")

    assert main.TIMEOUTS == {"extract": 5, "answer": 7, "business": 10, "report": 30}
    for kind, timeout in main.TIMEOUTS.items():
        chain = main.make_chain(kind)
        assert chain.runnable.request_timeout == timeout
        assert chain.fallbacks[0].default_request_timeout == timeout


def test_그래프와_상세_보고서는_호출_종류별_체인을_쓴다(monkeypatch):
    kinds = []
    monkeypatch.setattr(
        main,
        "make_chain",
        lambda kind, schema=None: kinds.append((kind, schema)) or RunnableLambda(lambda _: None),
    )
    main.get_graph.cache_clear()
    main.get_llm.cache_clear()
    try:
        main.get_graph()
        main.get_llm()
    finally:
        main.get_graph.cache_clear()
        main.get_llm.cache_clear()

    assert kinds == [
        ("answer", None),
        ("extract", TransferSlots),
        ("business", None),
        ("report", None),
    ]
