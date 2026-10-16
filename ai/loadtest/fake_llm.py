"""가짜 LLM(NFR-029): OpenAI 호환 서버. 부하 테스트에서 LLM 자리를 대신한다.

FastAPI가 쓰는 두 경로만 흉내 낸다 — /v1/chat/completions(일반 답변·구조화 출력)와 /v1/embeddings.
응답 지연은 실제 OpenAI를 재서 남긴 분포(latency.json)에서 호출 종류별로 뽑는다. 시드가 같으면 같은 순서다.
내용은 부하 시나리오의 문장 꼴만 알아듣는 규칙 기반이다(의도 인식 품질은 evals가 실제 LLM으로 잰다).
앱은 코드 변경 없이 환경변수만 바꾼다:
    uvicorn loadtest.fake_llm:app --port 9000        (ai/에서)
    OPENAI_BASE_URL=http://localhost:9000/v1  OPENAI_API_KEY=fake  (FastAPI 쪽)
FAKE_LLM_SEED로 시드를 바꾼다(기본 1).
"""

import asyncio
import base64
import hashlib
import json
import math
import os
import random
import re
import struct
import time
from pathlib import Path

from fastapi import FastAPI, Request

LATENCY = Path(__file__).with_name("latency.json")
EMBEDDING_DIM = 1536  # text-embedding-3-small
KINDS = ("extract", "answer", "business", "report", "embedding")

# 수집 대상 30곳의 DART 법인명. 문장에 이 이름이 보이면 companies에 넣는다.
COMPANIES = [
    "삼성전자",
    "SK하이닉스",
    "LG에너지솔루션",
    "삼성바이오로직스",
    "현대자동차",
    "기아",
    "셀트리온",
    "KB금융",
    "NAVER",
    "POSCO홀딩스",
    "신한지주",
    "현대모비스",
    "LG화학",
    "삼성SDI",
    "삼성물산",
    "카카오",
    "하나금융지주",
    "삼성생명",
    "한국전력공사",
    "SK텔레콤",
    "케이티",
    "LG전자",
    "삼성전기",
    "한화에어로스페이스",
    "HD현대중공업",
    "크래프톤",
    "SK이노베이션",
    "S-Oil",
    "우리금융지주",
    "고려아연",
]
PERIODS = {
    "오늘": "today",
    "이번 달": "this_month",
    "지난달": "last_month",
    "최근 7일": "last_7_days",
}
FINANCIAL_WORDS = ("실적", "영업이익", "매출", "순이익", "비교")
BUSINESS_WORDS = ("사업", "소개", "제품", "수주", "투자")

# 답변 글: 숫자가 없어 숫자 가드를 건드리지 않고, 길이는 실제 답변과 비슷하게 둔다.
ANSWERS = {
    "answer": "안녕하세요. SMART 금융 비서입니다. 송금과 내역 조회, 기업 재무 비교와 사업 내용 요약을 "
    "도와드립니다. 궁금한 내용을 편하게 말씀해 주세요.",
    "business": "공시 본문을 기준으로 정리하면, 이 회사는 주요 제품과 서비스를 여러 사업 부문으로 나누어 "
    "운영합니다. 부문마다 고객과 시장이 다르고, 보고서는 사업의 개요와 주요 제품, 판매 방식, 원재료와 "
    "생산 설비를 차례로 설명합니다. 질문하신 내용에 해당하는 부분은 아래 출처의 공시 본문에서 "
    "확인하실 수 있습니다. 본문에서 찾지 못한 내용은 답변에 포함하지 않았습니다.",
    "report": "표에 적힌 값을 기준으로 정리합니다. 분기별로 매출액과 영업이익, 당기순이익의 방향이 기업마다 "
    "다르게 나타납니다. 괄호 안의 전년 동기 대비 증감률을 보면 최근 분기로 올수록 기업 간 차이가 "
    "뚜렷해지는 항목이 있습니다. 영업이익률은 매출액 대비 영업이익의 비율이라 기업의 사업 구조에 따라 "
    "수준이 달라집니다. 미공시로 표시된 기업은 해당 분기의 공시가 아직 없어 비교에서 제외했습니다. "
    "자세한 값은 아래 표를 확인해 주세요.",
}


class Delays:
    """호출 종류별로 실측 표본에서 하나씩 뽑는다. 종류마다 시드가 고정된 난수열을 따로 쓴다."""

    def __init__(self, samples_ms: dict[str, list[int]], seed: int):
        self.samples = samples_ms
        self.rngs = {kind: random.Random(f"{seed}:{kind}") for kind in samples_ms}

    def next(self, kind: str) -> float:
        return self.rngs[kind].choice(self.samples[kind]) / 1000


def _amount(text: str) -> int | None:
    if m := re.search(r"(\d+)\s*만\s*원?", text):
        return int(m[1]) * 10_000
    if m := re.search(r"([\d,]+)\s*원", text):
        return int(m[1].replace(",", ""))
    return None


def extract_slots(message: str) -> dict:
    """사용자 메시지 → TransferSlots(스키마의 모든 필드). 내역 > 송금 > 재무 > 사업 순으로 하나만 켠다."""
    slots = {
        "is_transfer": False,
        "is_history": False,
        "period": None,
        "is_financial": False,
        "is_business": False,
        "companies": [],
        "amount": None,
        "recipient_account_number": None,
        "alias": None,
    }
    companies = sorted((c for c in COMPANIES if c in message), key=message.index)
    if "내역" in message or "얼마 보냈" in message:
        slots["is_history"] = True
        slots["period"] = next((v for k, v in PERIODS.items() if k in message), None)
    elif "보내" in message or "송금" in message:
        account = re.search(r"\d{10,14}", message)
        alias = re.search(r"(\S+?)(?:에게|한테)", message)
        slots["is_transfer"] = True
        slots["amount"] = _amount(message)
        slots["recipient_account_number"] = account[0] if account else None
        slots["alias"] = None if account or not alias else alias[1]
    elif any(w in message for w in FINANCIAL_WORDS):
        slots["is_financial"] = True
        slots["companies"] = companies
    elif any(w in message for w in BUSINESS_WORDS):
        slots["is_business"] = True
        slots["companies"] = companies
    return slots


def embedding_vector(text: str) -> list[float]:
    """같은 글이면 같은 단위 벡터. 값은 의미가 없고 모양(1536차원)만 같다."""
    rng = random.Random(hashlib.sha256(text.encode()).digest())
    vector = [rng.gauss(0, 1) for _ in range(EMBEDDING_DIM)]
    norm = math.sqrt(sum(x * x for x in vector))
    return [x / norm for x in vector]


def _text(message: dict) -> str:
    content = message.get("content")
    if isinstance(content, list):  # 조각 목록이면 글 조각만 잇는다
        return "".join(part.get("text", "") for part in content)
    return content or ""


def create_app(samples_ms: dict[str, list[int]], seed: int = 1) -> FastAPI:
    if missing := set(KINDS) - samples_ms.keys():
        raise ValueError(f"지연 표본이 없는 호출 종류: {sorted(missing)}")
    app = FastAPI(title="fake-llm")
    delays = Delays(samples_ms, seed)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "UP"}

    @app.post("/v1/chat/completions")
    async def chat_completions(request: Request) -> dict:
        body = await request.json()
        messages = body["messages"]
        system = "\n".join(_text(m) for m in messages if m["role"] in ("system", "developer"))
        user = next((_text(m) for m in reversed(messages) if m["role"] == "user"), "")
        if (body.get("response_format") or {}).get("type") == "json_schema":
            kind, content = "extract", json.dumps(extract_slots(user), ensure_ascii=False)
        else:
            kind = "business" if "<공시>" in system else "report" if "<표>" in system else "answer"
            content = ANSWERS[kind]
        await asyncio.sleep(delays.next(kind))
        return {
            "id": "chatcmpl-fake",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": body.get("model", "fake"),
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": content, "refusal": None},
                    "finish_reason": "stop",
                }
            ],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
        }

    @app.post("/v1/embeddings")
    async def embeddings(request: Request) -> dict:
        body = await request.json()
        texts = [body["input"]] if isinstance(body["input"], str) else body["input"]
        await asyncio.sleep(delays.next("embedding"))
        base64_format = body.get("encoding_format") == "base64"  # openai SDK의 기본값
        data = []
        for index, text in enumerate(texts):
            vector = embedding_vector(text)
            if base64_format:
                vector = base64.b64encode(struct.pack(f"<{EMBEDDING_DIM}f", *vector)).decode()
            data.append({"object": "embedding", "index": index, "embedding": vector})
        usage = {"prompt_tokens": 0, "total_tokens": 0}
        return {"object": "list", "data": data, "model": body.get("model", "fake"), "usage": usage}

    return app


app = create_app(
    json.loads(LATENCY.read_text(encoding="utf-8"))["samples_ms"],
    int(os.getenv("FAKE_LLM_SEED", "1")),
)
