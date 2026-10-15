from typing import TypedDict

from langchain_core.language_models import BaseChatModel
from langgraph.graph import END, START, StateGraph

from app.guard import is_injection, mask_pii

SYSTEM_PROMPT = "너는 SMART 금융 비서다. 사용자의 금융 질문에 한국어로 짧게 답한다."
INJECTION_REPLY = "요청하신 내용은 처리할 수 없습니다."


class ChatState(TypedDict, total=False):
    message: str
    reply: str
    guard: str


# 입력 가드 → (인젝션이면 바로 종료) → LLM 답변. LLM을 인자로 받아 테스트에서 가짜를 넣는다.
def build_graph(llm: BaseChatModel):
    def input_guard(state: ChatState) -> ChatState:
        message = mask_pii(state["message"])
        if is_injection(message):
            return {"message": message, "reply": INJECTION_REPLY, "guard": "INJECTION"}
        return {"message": message, "guard": "PASS"}

    def answer(state: ChatState) -> ChatState:
        return {
            "reply": llm.invoke([("system", SYSTEM_PROMPT), ("human", state["message"])]).content
        }

    graph = StateGraph(ChatState)
    graph.add_node("input_guard", input_guard)
    graph.add_node("answer", answer)
    graph.add_edge(START, "input_guard")
    graph.add_conditional_edges(
        "input_guard", lambda state: END if state["guard"] == "INJECTION" else "answer"
    )
    graph.add_edge("answer", END)
    return graph.compile()
