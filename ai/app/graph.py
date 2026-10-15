from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, MessagesState, StateGraph

from app.guard import is_injection, mask_pii

SYSTEM_PROMPT = "너는 SMART 금융 비서다. 사용자의 금융 질문에 한국어로 짧게 답한다."
INJECTION_REPLY = "요청하신 내용은 처리할 수 없습니다."


# messages: 이 대화의 사용자·AI 메시지 기록(LLM에 목록째 넘김). message는 이번 턴 입력.
class ChatState(MessagesState, total=False):
    message: str
    reply: str
    guard: str


# 입력 가드 → (인젝션이면 바로 종료) → LLM 답변. LLM을 인자로 받아 테스트에서 가짜를 넣는다.
# 대화 기록은 체크포인터가 thread_id별로 들고 있다 — invoke할 때 config에 thread_id를 넘긴다.
def build_graph(llm: BaseChatModel):
    def input_guard(state: ChatState) -> ChatState:
        message = mask_pii(state["message"])
        if is_injection(message):
            return {"reply": INJECTION_REPLY, "guard": "INJECTION"}  # 막힌 문장은 기록에 안 넣는다
        return {"messages": [HumanMessage(message)], "guard": "PASS"}

    def answer(state: ChatState) -> ChatState:
        ai = llm.invoke([SystemMessage(SYSTEM_PROMPT), *state["messages"]])
        return {"messages": [ai], "reply": ai.content}

    graph = StateGraph(ChatState)
    graph.add_node("input_guard", input_guard)
    graph.add_node("answer", answer)
    graph.add_edge(START, "input_guard")
    graph.add_conditional_edges(
        "input_guard", lambda state: END if state["guard"] == "INJECTION" else "answer"
    )
    graph.add_edge("answer", END)
    # ponytail: 메모리 체크포인터 — 워커 1개 기준, 재시작하면 기록이 사라진다. 문제가 확인되면 PostgreSQL.
    return graph.compile(checkpointer=InMemorySaver())
