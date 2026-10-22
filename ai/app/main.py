import base64
import json
import os
from functools import lru_cache, partial
from time import perf_counter
from typing import Annotated, Any
from uuid import uuid4

import openai
import psycopg
from fastapi import BackgroundTasks, Depends, FastAPI, Header, Request, Response
from fastapi.responses import JSONResponse
from langchain_anthropic import ChatAnthropic
from langchain_openai import ChatOpenAI
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from pydantic import BaseModel

from app.business import embed_texts
from app.financial import (
    NO_DATA_REPORT,
    REPORT_UNVERIFIED,
    build_detail_table,
    write_report,
)
from app.graph import build_graph
from app.guard import OUTPUT_REPLIES, check_output, mask_pii, unverified_numbers
from app.metrics import (
    GUARD,
    HTTP_SECONDS,
    IN_FLIGHT,
    NODE_SECONDS,
    LlmMetrics,
    update_threadpool,
)
from app.spring import create_transfer_request, get_transfer_history
from app.store import (
    find_companies,
    load_detail,
    load_financials,
    save_detail,
    search_business_chunks,
    write_ai_usage,
    write_chat_log,
)
from app.transfer import TransferSlots

app = FastAPI(title="smart-ai")


# 내부 전용(Spring만 호출). 컨테이너 헬스 체크에도 쓴다.
@app.get("/internal/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


# HTTP 요청 지연과 처리 중인 요청(NFR-036). 경로는 라우트 틀(/internal/details/{detail_id})로 묶는다.
@app.middleware("http")
async def observe_http(request: Request, call_next):
    if request.url.path == "/metrics":
        return await call_next(request)
    IN_FLIGHT.inc()
    start, status = perf_counter(), 500  # 예외로 끝나면 500
    try:
        response = await call_next(request)
        status = response.status_code
        return response
    finally:
        IN_FLIGHT.dec()
        route = request.scope.get("route")
        path = route.path if route else "unmatched"
        HTTP_SECONDS.labels(request.method, path, status).observe(perf_counter() - start)


# Prometheus가 긁는 곳. 내부망 전용이다. async라 동기 스레드풀이 가득 차도 읽힌다(스레드풀 지표도 여기서 구한다).
@app.get("/metrics", include_in_schema=False)
async def metrics() -> Response:
    update_threadpool()
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)


# 호출 종류별 타임아웃(초, NFR-056). 실측 p95(latency.json의 30개 중 29번째: 의도 추출 4.2·답변 5.0·
# 사업의 내용 7.8·상세 보고서 22.8초)에 여유를 두되 Spring 읽기 제한(채팅 10초·상세 30초)을 넘지 않는다.
# OpenAI·Claude 호출에 같은 값을 쓴다. 재검색의 재작성은 answer 값을 쓴다.
TIMEOUTS = {"extract": 5, "answer": 7, "business": 10, "report": 30}
# 이 오류면 같은 요청을 Claude로 한 번 더 보낸다: 429, 5xx, 연결 실패(타임아웃은 연결 오류의 하위).
# 400·401 같은 요청·설정 오류는 모델을 바꿔도 같아서 대체하지 않는다.
FALLBACK_ON = (openai.RateLimitError, openai.InternalServerError, openai.APIConnectionError)


def make_llm(timeout: float | None = None) -> ChatOpenAI:
    # OpenAI 429는 재시도하지 않는다(SDK 기본 2회 재시도를 끈다).
    # gpt-5 계열은 추론 강도가 지연을 좌우한다. 같은 모델로 잰 값: 기본(medium)은 호출당 4~11초,
    # low는 약 2.7초, minimal은 약 1.2초다. minimal은 빠르지만 의도 추출을 자주 놓친다(13건 중 3건 —
    # 후속 발화를 송금으로 이어 받지 못하고, 범위 밖 기간을 최근 7일로 고른다). 그래서 low를 쓴다.
    # 추론을 쓰지 않는 모델은 이 인자를 거부하므로 OPENAI_REASONING_EFFORT를 비우면 뺀다.
    effort = os.getenv("OPENAI_REASONING_EFFORT", "low")
    options = {"reasoning_effort": effort} if effort else {}
    return ChatOpenAI(
        model=os.getenv("OPENAI_MODEL") or "gpt-5-nano",
        max_retries=0,
        timeout=timeout,
        callbacks=[LlmMetrics(record_usage)],
        **options,
    )


# 장애 대체용 Claude(NFR-056). 키가 없으면 None이라 OpenAI만 쓴다. 대체 자체가 재시도라 SDK 재시도는 끈다.
# max_tokens는 필수 인자다(기본은 모델 최대치라 크게 잡힌다). 짧은 답변과 보고서에 넉넉한 값이다.
def make_claude(timeout: float) -> ChatAnthropic | None:
    if not os.getenv("ANTHROPIC_API_KEY"):
        return None
    return ChatAnthropic(
        model=os.getenv("ANTHROPIC_MODEL") or "claude-haiku-5-5",
        max_retries=0,
        timeout=timeout,
        max_tokens=4096,
        callbacks=[LlmMetrics(record_usage, fallback=True)],
    )


# kind의 타임아웃을 둔 OpenAI 모델에, 키가 있으면 Claude를 대체로 붙인다. schema가 있으면 구조화 출력이다 —
# with_fallbacks 결과에는 with_structured_output이 없어서 양쪽 모델에 같은 스키마를 먼저 건 뒤 묶는다.
# 구조화 출력 방식은 각 클래스의 기본값이다(OpenAI는 json_schema, Claude는 강제 도구 호출).
def make_chain(kind: str, schema=None):
    timeout = TIMEOUTS[kind]
    models = [m for m in (make_llm(timeout), make_claude(timeout)) if m]
    if schema:
        models = [m.with_structured_output(schema) for m in models]
    if len(models) == 1:
        return models[0]
    return models[0].with_fallbacks(models[1:], exceptions_to_handle=FALLBACK_ON)


# 상세 보고서용. 처음 요청 때 만든다 — import 시점에 만들면 OPENAI_API_KEY 없는 CI·헬스 테스트가 깨진다.
@lru_cache
def get_llm():
    return make_chain("report")


@lru_cache
def get_graph():
    return build_graph(
        make_chain("answer"),
        make_chain("extract", TransferSlots),
        business_llm=make_chain("business"),
    )


# 요청마다 연결을 연다.
def _connect() -> psycopg.Connection:
    return psycopg.connect(
        host=os.getenv("POSTGRES_HOST", "localhost"),
        dbname=os.getenv("POSTGRES_DB", "smart"),
        user=os.getenv("POSTGRES_USER", "smart"),
        password=os.getenv("POSTGRES_PASSWORD", ""),
        connect_timeout=5,
    )


# 재무 비교용 조회: 말한 기업 이름 → (찾은 기업, 못 찾은 이름, 회사별 재무 행).
def compare_financials(names: list[str]):
    with _connect() as conn:
        companies, missing = find_companies(conn, names)
        rows = load_financials(conn, [stock for stock, _ in companies]) if companies else {}
    return companies, missing, rows


# 사업의 내용 답변용 검색: 말한 기업 이름 + 질문 → (찾은 기업, 못 찾은 이름, 종목코드별 청크).
# 질문은 기업을 찾은 뒤에만 임베딩한다(찾을 기업이 없으면 OpenAI를 부르지 않는다).
def search_business(names: list[str], question: str):
    with _connect() as conn:
        companies, missing = find_companies(conn, names)
        if not companies:
            return companies, missing, {}
        vector = embed_texts([question])[0]
        chunks = {stock: search_business_chunks(conn, stock, vector) for stock, _ in companies}
    return companies, missing, chunks


# 채팅 한 턴의 판정·노드별 소요 시간을 로그 테이블에 남긴다(NFR-026). 응답 뒤에 쓰고, 실패해도 채팅은 무관하다.
def log_chat(thread_id: str, guard: str, node_ms: dict[str, int]) -> None:
    try:
        with _connect() as conn:
            write_chat_log(conn, thread_id, guard, node_ms)
    except psycopg.Error:
        pass


# LLM 호출 한 번의 사용량을 남긴다(NFR-054). 호출이 끝날 때 바로 쓰고, 실패해도 채팅은 무관하다.
def record_usage(
    user_id: int, model: str, input_tokens: int, output_tokens: int, fallback: bool = False
) -> None:
    try:
        with _connect() as conn:
            write_ai_usage(conn, user_id, model, input_tokens, output_tokens, fallback)
    except psycopg.Error:
        pass


# 상세 보기 ID(FR-016)를 발급하고 분석 조건을 저장한다. 저장에 실패하면 ID 없이 답한다(채팅은 이어진다).
def issue_detail(detail: dict) -> str | None:
    detail_id = f"dt_{uuid4().hex}"
    try:
        with _connect() as conn:
            save_detail(conn, detail_id, detail["kind"], detail["stocks"])
    except psycopg.Error:
        return None
    return detail_id


# Spring이 이미 서명을 확인해 넘긴 JWT의 sub(회원 ID). 내부 전용이라 서명은 다시 보지 않는다. 못 읽으면 None.
def member_id(authorization: str | None) -> int | None:
    try:
        payload = (authorization or "").removeprefix("Bearer ").split(".")[1]
        return int(json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))["sub"])
    except (IndexError, ValueError, KeyError, TypeError):
        return None


class ChatRequest(BaseModel):
    threadId: str  # 체크포인터의 thread_id — 같은 값이면 같은 대화(FR-015)
    message: str


@app.post("/internal/chat")
def chat(
    req: ChatRequest,
    background: BackgroundTasks,
    graph: Annotated[Any, Depends(get_graph)],
    authorization: Annotated[str | None, Header()] = None,
) -> dict:
    token = (authorization or "").removeprefix("Bearer ")
    # 그래프 입력도 체크포인터에 저장되므로, 원문이 남지 않게 넣기 전에 가린다.
    # JWT는 문자열 값이 아니라 함수에 담아 넘긴다 — 문자열은 체크포인터 메타데이터에 저장된다.
    config = {
        "configurable": {
            "thread_id": req.threadId,
            "create_transfer": partial(create_transfer_request, token),
            "get_history": partial(get_transfer_history, token),
            "compare_financials": compare_financials,
            "search_business": search_business,
        },
        # 이 요청의 LLM 호출마다 이 사용자의 사용량을 남긴다(NFR-054). 그래프 안의 호출까지 이어진다.
        "metadata": {"user_id": member_id(authorization)},
    }
    # 노드가 끝날 때마다 걸린 시간을 잰다(updates). 마지막 values가 최종 상태다.
    # 같은 노드를 두 번 지나면(재검색, NFR-052) 시간을 더한다.
    node_ms: dict[str, int] = {}
    out: dict = {}
    last = perf_counter()
    for mode, chunk in graph.stream(
        {"message": mask_pii(req.message)}, config, stream_mode=["updates", "values"]
    ):
        if mode == "updates":
            now = perf_counter()
            node = next(iter(chunk))
            node_ms[node] = node_ms.get(node, 0) + round((now - last) * 1000)
            NODE_SECONDS.labels(node).observe(now - last)
            last = now
        else:
            out = chunk
    GUARD.labels("chat", out["guard"]).inc()
    background.add_task(log_chat, req.threadId, out["guard"], node_ms)
    data = {"reply": out["reply"], "guard": out["guard"]}
    if out.get("transfer_card"):  # 확인 카드는 있을 때만(E-11)
        data["transferCard"] = out["transfer_card"]
    if out.get("table"):  # 재무 비교 표도 있을 때만(E-11)
        data["table"] = out["table"]
    if out.get("detail") and (detail_id := issue_detail(out["detail"])):  # 분석 답변일 때만(E-11)
        data["detailId"] = detail_id
    return {"success": True, "data": data, "error": None}


# 상세 보기(I-02, FR-016): 클릭할 때 저장해 둔 분석 조건으로 전체 표와 보고서를 만든다.
# 표는 코드가 계산하고, 보고서는 표의 값으로만 쓰며 출력 가드를 거친다. 걸리면 고정 문장이고 표는 그대로 준다.
@app.get("/internal/details/{detail_id}")
def detail(
    detail_id: str,
    llm: Annotated[Any, Depends(get_llm)],
    authorization: Annotated[str | None, Header()] = None,
):
    with _connect() as conn:
        condition = load_detail(conn, detail_id)
        if condition is None:
            return JSONResponse(
                status_code=404,
                content={
                    "success": False,
                    "data": None,
                    "error": {
                        "code": "DETAIL_NOT_FOUND",
                        "message": "상세 보기 대상을 찾을 수 없습니다",
                    },
                },
            )
        companies, _ = find_companies(conn, condition["stock_codes"])
        rows = load_financials(conn, [stock for stock, _ in companies])
    table = build_detail_table(companies, rows)
    if table is None:
        return {"success": True, "data": {"report": NO_DATA_REPORT}, "error": None}
    report, evidence = write_report(
        llm.with_config(metadata={"user_id": member_id(authorization)}), table
    )
    code = check_output(report, unverified_numbers(report, evidence))
    GUARD.labels("detail", code).inc()
    if code != "PASS":  # 표에는 '아래 출처'가 없어 숫자 판정 문장만 표 기준으로 바꾼다
        report = REPORT_UNVERIFIED if code == "NUMBER_UNVERIFIED" else OUTPUT_REPLIES[code]
    return {"success": True, "data": {"report": report, "table": table}, "error": None}
