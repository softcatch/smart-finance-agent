import json

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, MessagesState, StateGraph

from app.guard import is_injection, mask_pii
from app.spring import RecipientNotFound
from app.transfer import TransferSlots, ask_for, merge

SYSTEM_PROMPT = "너는 SMART 금융 비서다. 사용자의 금융 질문에 한국어로 짧게 답한다."
INJECTION_REPLY = "요청하신 내용은 처리할 수 없습니다."
EXTRACT_PROMPT = (
    "사용자 메시지에서 송금 정보를 뽑는다. 송금 요청이거나 아래 진행 중인 송금 정보를 이어서 "
    "말하면 is_transfer=true. 말하지 않은 값은 null로 둔다. 금액은 원 단위 정수, 별칭은 들은 "
    "그대로 쓴다.\n진행 중인 송금: {pending}"
)
NOT_FOUND_REPLY = "계좌나 별칭을 찾을 수 없습니다. 받는 분의 계좌번호나 별칭을 다시 알려 주세요."


# messages: 이 대화의 사용자·AI 메시지 기록(LLM에 목록째 넘김). message는 이번 턴 입력.
# pending: 진행 중인 송금 값(금액·수취인). 다 모여 요청을 만들면 비운다.
# transfer_card: 이번 턴에 만든 확인 카드. 턴마다 비운다(이전 턴 카드가 남지 않게).
class ChatState(MessagesState, total=False):
    message: str
    reply: str
    guard: str
    is_transfer: bool
    pending: dict
    transfer_card: dict | None


# 입력 가드 → (인젝션이면 바로 종료) → 송금 값 추출 → 일반 답변 | 되묻기 | 송금 요청 생성.
# LLM·추출기를 인자로 받아 테스트에서 가짜를 넣는다. 추출기를 안 주면 llm의 구조화 출력을 쓴다.
# 대화 기록은 체크포인터가 thread_id별로 들고 있다 — invoke할 때 config에 thread_id를 넘긴다.
# 송금 요청 생성 함수는 config["configurable"]["create_transfer"]로 받는다(JWT를 쥔 함수).
def build_graph(llm: BaseChatModel, extractor=None):
    extractor = extractor or llm.with_structured_output(TransferSlots)

    def input_guard(state: ChatState) -> ChatState:
        message = mask_pii(state["message"])
        if is_injection(message):  # 막힌 문장은 기록에 안 넣는다
            return {"reply": INJECTION_REPLY, "guard": "INJECTION", "transfer_card": None}
        return {"messages": [HumanMessage(message)], "guard": "PASS", "transfer_card": None}

    def extract(state: ChatState) -> ChatState:
        pending = state.get("pending") or {}
        prompt = EXTRACT_PROMPT.format(pending=json.dumps(pending, ensure_ascii=False))
        slots = extractor.invoke([SystemMessage(prompt), state["messages"][-1]])
        if not slots.is_transfer:  # 송금이 아니면 진행 중이던 것도 접는다
            return {"is_transfer": False, "pending": {}}
        return {"is_transfer": True, "pending": merge(pending, slots)}

    def answer(state: ChatState) -> ChatState:
        ai = llm.invoke([SystemMessage(SYSTEM_PROMPT), *state["messages"]])
        return {"messages": [ai], "reply": ai.content}

    def ask(state: ChatState) -> ChatState:
        reply = ask_for(state["pending"])
        return {"messages": [AIMessage(reply)], "reply": reply}

    def create_request(state: ChatState, config: RunnableConfig) -> ChatState:
        try:
            card = config["configurable"]["create_transfer"](state["pending"])
        except RecipientNotFound:  # 금액은 남기고 수취인만 다시 묻는다
            kept = {k: v for k, v in state["pending"].items() if k == "amount"}
            return {
                "pending": kept,
                "messages": [AIMessage(NOT_FOUND_REPLY)],
                "reply": NOT_FOUND_REPLY,
            }
        # 금액·이름은 LLM이 아니라 Spring 응답 값으로 쓴다(NFR-002)
        reply = f"{card['recipientName']}님께 {card['amount']:,}원을 보낼까요? 아래 확인 카드에서 [보내기]를 눌러 주세요."
        return {
            "pending": {},
            "transfer_card": card,
            "messages": [AIMessage(reply)],
            "reply": reply,
        }

    def after_extract(state: ChatState) -> str:
        if not state["is_transfer"]:
            return "answer"
        return "ask" if ask_for(state["pending"]) else "create_request"

    graph = StateGraph(ChatState)
    graph.add_node("input_guard", input_guard)
    graph.add_node("extract", extract)
    graph.add_node("answer", answer)
    graph.add_node("ask", ask)
    graph.add_node("create_request", create_request)
    graph.add_edge(START, "input_guard")
    graph.add_conditional_edges(
        "input_guard", lambda state: END if state["guard"] == "INJECTION" else "extract"
    )
    graph.add_conditional_edges("extract", after_extract)
    graph.add_edge("answer", END)
    graph.add_edge("ask", END)
    graph.add_edge("create_request", END)
    # ponytail: 메모리 체크포인터 — 워커 1개 기준, 재시작하면 기록이 사라진다. 문제가 확인되면 PostgreSQL.
    return graph.compile(checkpointer=InMemorySaver())
