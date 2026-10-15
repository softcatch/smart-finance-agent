import os
from functools import lru_cache, partial
from typing import Annotated, Any

from fastapi import Depends, FastAPI, Header
from langchain_openai import ChatOpenAI
from pydantic import BaseModel

from app.graph import build_graph
from app.guard import mask_pii
from app.spring import create_transfer_request

app = FastAPI(title="smart-ai")


# 내부 전용(Spring만 호출). 컨테이너 헬스 체크에도 쓴다.
@app.get("/internal/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


# 처음 요청 때 만든다 — import 시점에 만들면 OPENAI_API_KEY 없는 CI·헬스 테스트가 깨진다.
@lru_cache
def get_graph():
    # OpenAI 429는 재시도하지 않는다(SDK 기본 2회 재시도를 끈다).
    llm = ChatOpenAI(model=os.getenv("OPENAI_MODEL") or "gpt-5-nano", max_retries=0)
    return build_graph(llm)


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
            }
        },
    )
    data = {"reply": out["reply"], "guard": out["guard"]}
    if out.get("transfer_card"):  # 확인 카드는 있을 때만(E-11)
        data["transferCard"] = out["transfer_card"]
    return {"success": True, "data": data, "error": None}
