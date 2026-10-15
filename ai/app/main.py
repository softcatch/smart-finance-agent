import os
from functools import lru_cache, partial
from typing import Annotated, Any

import psycopg
from fastapi import Depends, FastAPI, Header
from langchain_openai import ChatOpenAI
from pydantic import BaseModel

from app.graph import build_graph
from app.guard import mask_pii
from app.spring import create_transfer_request, get_transfer_history
from app.store import find_companies, load_financials

app = FastAPI(title="smart-ai")


# 내부 전용(Spring만 호출). 컨테이너 헬스 체크에도 쓴다.
@app.get("/internal/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


def make_llm() -> ChatOpenAI:
    # OpenAI 429는 재시도하지 않는다(SDK 기본 2회 재시도를 끈다).
    # gpt-5 계열은 추론 강도가 지연을 좌우한다. 같은 모델로 잰 값: 기본(medium)은 호출당 4~11초,
    # low는 약 2.7초, minimal은 약 1.2초다. minimal은 빠르지만 의도 추출을 자주 놓친다(13건 중 3건 —
    # 후속 발화를 송금으로 이어 받지 못하고, 범위 밖 기간을 최근 7일로 고른다). 그래서 low를 쓴다.
    # 추론을 쓰지 않는 모델은 이 인자를 거부하므로 OPENAI_REASONING_EFFORT를 비우면 뺀다.
    effort = os.getenv("OPENAI_REASONING_EFFORT", "low")
    options = {"reasoning_effort": effort} if effort else {}
    return ChatOpenAI(model=os.getenv("OPENAI_MODEL") or "gpt-5-nano", max_retries=0, **options)


# 처음 요청 때 만든다 — import 시점에 만들면 OPENAI_API_KEY 없는 CI·헬스 테스트가 깨진다.
@lru_cache
def get_graph():
    return build_graph(make_llm())


# 재무 비교용 조회: 말한 기업 이름 → (찾은 기업, 못 찾은 이름, 회사별 재무 행). 요청마다 연결을 연다.
def compare_financials(names: list[str]):
    with psycopg.connect(
        host=os.getenv("POSTGRES_HOST", "localhost"),
        dbname=os.getenv("POSTGRES_DB", "smart"),
        user=os.getenv("POSTGRES_USER", "smart"),
        password=os.getenv("POSTGRES_PASSWORD", ""),
        connect_timeout=5,
    ) as conn:
        companies, missing = find_companies(conn, names)
        rows = load_financials(conn, [stock for stock, _ in companies]) if companies else {}
    return companies, missing, rows


class ChatRequest(BaseModel):
    threadId: str  # 체크포인터의 thread_id — 같은 값이면 같은 대화(FR-015)
    message: str


@app.post("/internal/chat")
def chat(
    req: ChatRequest,
    graph: Annotated[Any, Depends(get_graph)],
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    token = (authorization or "").removeprefix("Bearer ")
    # 그래프 입력도 체크포인터에 저장되므로, 원문이 남지 않게 넣기 전에 가린다.
    # JWT는 문자열 값이 아니라 함수에 담아 넘긴다 — 문자열은 체크포인터 메타데이터에 저장된다.
    out = graph.invoke(
        {"message": mask_pii(req.message)},
        {
            "configurable": {
                "thread_id": req.threadId,
                "create_transfer": partial(create_transfer_request, token),
                "get_history": partial(get_transfer_history, token),
                "compare_financials": compare_financials,
            }
        },
    )
    data = {"reply": out["reply"], "guard": out["guard"]}
    if out.get("transfer_card"):  # 확인 카드는 있을 때만(E-11)
        data["transferCard"] = out["transfer_card"]
    if out.get("table"):  # 재무 비교 표도 있을 때만(E-11)
        data["table"] = out["table"]
    return {"success": True, "data": data, "error": None}
