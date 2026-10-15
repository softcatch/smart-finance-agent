import os
from functools import lru_cache
from typing import Annotated, Any

from fastapi import Depends, FastAPI
from langchain_openai import ChatOpenAI
from pydantic import BaseModel

from app.graph import build_graph

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
    threadId: str  # 이번엔 전달만 받는다(대화 맥락은 FR-015)
    message: str


@app.post("/internal/chat")
def chat(req: ChatRequest, graph: Annotated[Any, Depends(get_graph)]) -> dict:
    out = graph.invoke({"message": req.message})
    return {
        "success": True,
        "data": {"reply": out["reply"], "guard": out["guard"]},
        "error": None,
    }
