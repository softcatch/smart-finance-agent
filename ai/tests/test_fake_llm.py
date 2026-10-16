import base64
import json
import math
import struct

import pytest
from fastapi.testclient import TestClient

from app.business import embed_texts
from app.financial import build_detail_table, write_report
from app.graph import build_graph
from app.main import make_llm
from app.transfer import TransferSlots
from loadtest.fake_llm import (
    ANSWERS,
    EMBEDDING_DIM,
    KINDS,
    LATENCY,
    Delays,
    create_app,
    embedding_vector,
    extract_slots,
    serve,
)

ONE_MS = {kind: [1] for kind in KINDS}


def test_실측_분포_파일에_호출_종류별_표본이_있다():
    data = json.loads(LATENCY.read_text(encoding="utf-8"))

    for kind in KINDS:
        samples = data["samples_ms"][kind]
        assert len(samples) >= 30
        assert all(isinstance(ms, int) and ms > 0 for ms in samples)


def test_지연_표본이_빠진_호출_종류가_있으면_만들지_못한다():
    with pytest.raises(ValueError, match="embedding"):
        create_app({kind: [1] for kind in KINDS if kind != "embedding"})


@pytest.mark.parametrize(
    ("message", "expected"),
    [
        ("홍길동에게 1만원 보내줘", {"is_transfer": True, "amount": 10000, "alias": "홍길동"}),
        (
            "123456789012로 5000원 보내줘",
            {"is_transfer": True, "amount": 5000, "recipient_account_number": "123456789012"},
        ),
        ("지난달 송금 내역 알려줘", {"is_history": True, "period": "last_month"}),
        ("삼성전자 영업이익 알려줘", {"is_financial": True, "companies": ["삼성전자"]}),
        (
            "삼성전자랑 SK하이닉스 실적 비교해줘",
            {"is_financial": True, "companies": ["삼성전자", "SK하이닉스"]},
        ),
        ("삼성전자 사업 내용 알려줘", {"is_business": True, "companies": ["삼성전자"]}),
        ("안녕", {}),
    ],
)
def test_문장_꼴대로_의도를_켜고_스키마에_맞는다(message, expected):
    slots = TransferSlots.model_validate(extract_slots(message))  # 앱이 읽는 스키마 그대로

    for field, value in expected.items():
        assert getattr(slots, field) == value
    flags = [slots.is_transfer, slots.is_history, slots.is_financial, slots.is_business]
    assert sum(flags) == (1 if expected else 0)  # 의도는 하나만 켠다


def test_임베딩은_같은_글이면_같은_단위벡터다():
    vector = embedding_vector("삼성전자 사업")

    assert len(vector) == EMBEDDING_DIM
    assert vector == embedding_vector("삼성전자 사업")
    assert vector != embedding_vector("SK하이닉스 사업")
    assert math.isclose(sum(x * x for x in vector), 1.0, rel_tol=1e-6)


def test_호출_종류를_요청_모양으로_가른다(monkeypatch):
    kinds = []
    monkeypatch.setattr(Delays, "next", lambda self, kind: kinds.append(kind) or 0)
    client = TestClient(create_app(ONE_MS))

    def chat(system: str, **extra):
        messages = [{"role": "system", "content": system}, {"role": "user", "content": "질문"}]
        return client.post("/v1/chat/completions", json={"messages": messages, **extra})

    chat("의도를 뽑는다", response_format={"type": "json_schema", "json_schema": {}})
    chat("너는 비서다")
    chat("아래 <공시> 블록만 근거로 답한다")
    chat("아래 <표>는 코드가 계산했다")
    client.post("/v1/embeddings", json={"input": ["질문"]})

    assert kinds == ["extract", "answer", "business", "report", "embedding"]


def test_답변은_종류별_글이고_구조화_출력은_JSON이다():
    client = TestClient(create_app(ONE_MS))
    messages = [{"role": "system", "content": "너는 비서다"}, {"role": "user", "content": "안녕"}]

    plain = client.post("/v1/chat/completions", json={"messages": messages}).json()
    structured = client.post(
        "/v1/chat/completions",
        json={"messages": messages, "response_format": {"type": "json_schema"}},
    ).json()

    assert plain["choices"][0]["message"]["content"] == ANSWERS["answer"]
    assert TransferSlots.model_validate_json(structured["choices"][0]["message"]["content"])


def test_임베딩은_요청한_형식으로_돌려준다():
    client = TestClient(create_app(ONE_MS))

    as_float = client.post("/v1/embeddings", json={"input": ["가"], "encoding_format": "float"})
    as_base64 = client.post(
        "/v1/embeddings", json={"input": ["가", "나"], "encoding_format": "base64"}
    )

    assert as_float.json()["data"][0]["embedding"] == pytest.approx(
        embedding_vector("가"), abs=1e-6
    )
    packed = base64.b64decode(as_base64.json()["data"][1]["embedding"])
    assert list(struct.unpack(f"<{EMBEDDING_DIM}f", packed)) == pytest.approx(
        embedding_vector("나"), abs=1e-6
    )


def test_지연은_표본에서_뽑고_시드가_같으면_같은_순서다():
    samples = {kind: list(range(100, 2100, 100)) for kind in KINDS}
    a, b, c = (Delays(samples, seed) for seed in (7, 7, 8))
    first = [a.next("extract") for _ in range(50)]

    assert first == [b.next("extract") for _ in range(50)]
    assert first != [c.next("extract") for _ in range(50)]
    assert all(ms * 1000 in samples["extract"] for ms in first)


def test_호출_종류마다_난수열이_따로라서_섞여_불려도_종류별_순서는_같다():
    samples = {kind: list(range(100, 2100, 100)) for kind in KINDS}
    alone, mixed = Delays(samples, 1), Delays(samples, 1)

    expected = [alone.next("report") for _ in range(20)]
    got = []
    for _ in range(20):
        mixed.next("extract")  # 다른 종류가 사이사이 불려도
        got.append(mixed.next("report"))

    assert got == expected


@pytest.fixture
def fake_llm_url(monkeypatch):
    """지연 1ms짜리 가짜 LLM을 실제 포트에 띄우고, 앱이 쓰는 환경변수를 거기로 돌린다."""
    url, stop = serve(create_app(ONE_MS))
    monkeypatch.setenv("OPENAI_BASE_URL", url)
    monkeypatch.setenv("OPENAI_API_KEY", "fake")
    yield
    stop()


def _chunk():
    return {
        "section": "1. 사업의 개요",
        "content": "TV와 반도체를 만듭니다.",
        "report_nm": "반기보고서 (2026.06)",
        "rcept_no": "20260814000001",
    }


def _rows():
    return {
        "005930": [
            {
                "stock_code": "005930",
                "bsns_year": 2026,
                "reprt_code": "11013",
                "fs_div": "CFS",
                "account_nm": account,
                "amount": amount,
                "prev_amount": amount - 1_000_000_000,
            }
            for account, amount in (
                ("매출액", 79_000_000_000_000),
                ("영업이익", 6_000_000_000_000),
                ("당기순이익", 8_000_000_000_000),
            )
        ]
    }


def test_앱의_LLM_호출_경로가_가짜_LLM으로_끝까지_돈다(fake_llm_url):
    graph = build_graph(make_llm())  # 코드 변경 없이 환경변수만 바꿨다

    def ask(message: str, thread: str):
        config = {
            "configurable": {
                "thread_id": thread,
                "create_transfer": lambda pending: {
                    "recipientName": "김영희",
                    "amount": pending["amount"],
                },
                "compare_financials": lambda names: (
                    [("005930", "삼성전자")],
                    [],
                    _rows(),
                ),
                "search_business": lambda names, question: (
                    [("005930", "삼성전자")],
                    [],
                    {"005930": [_chunk()]},
                ),
            }
        }
        return graph.invoke({"message": message}, config)

    general = ask("안녕", "th_general")
    business = ask("삼성전자 사업 내용 알려줘", "th_business")
    financial = ask("삼성전자 영업이익 알려줘", "th_financial")
    transfer = ask("123456789012로 5000원 보내줘", "th_transfer")

    assert (general["reply"], general["guard"]) == (ANSWERS["answer"], "PASS")
    assert business["reply"].startswith(ANSWERS["business"]) and business["guard"] == "PASS"
    assert financial["table"]["columns"] == ["항목", "삼성전자"]
    assert transfer["reply"].startswith("김영희님께 5,000원")


def test_상세_보고서와_임베딩도_가짜_LLM으로_돈다(fake_llm_url):
    table = build_detail_table([("005930", "삼성전자")], _rows())

    report, _ = write_report(make_llm(), table)
    vectors = embed_texts(["삼성전자 사업"])

    assert report == ANSWERS["report"]
    assert len(vectors) == 1 and len(vectors[0]) == EMBEDDING_DIM
