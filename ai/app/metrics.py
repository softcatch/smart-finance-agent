"""Prometheus 지표(NFR-036). main.py의 /metrics가 내보낸다. uvicorn 프로세스 하나 기준이다(레지스트리가 프로세스별).

부하 테스트에서 병목을 한 화면에서 읽으려는 지표만 둔다: 요청 지연과 처리 중인 요청, 동기 엔드포인트 스레드풀 사용량,
LLM·임베딩 호출 시간과 OpenAI 오류, LLM 토큰 수, 채팅 노드별 시간, 가드 판정 횟수.
"""

import time
from uuid import UUID

import anyio.to_thread
from langchain_core.callbacks import BaseCallbackHandler
from prometheus_client import Counter, Gauge, Histogram

# LLM을 기다리는 수 초 구간(읽기 제한 10초, 상세 보고서 30초)까지 보이게 버킷을 넓게 둔다.
BUCKETS = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 3, 5, 7.5, 10, 15, 20, 30)

HTTP_SECONDS = Histogram(
    "http_request_seconds", "HTTP 요청 처리 시간", ["method", "path", "status"], buckets=BUCKETS
)
IN_FLIGHT = Gauge("http_requests_in_flight", "처리 중인 HTTP 요청(스레드를 기다리는 것 포함)")
NODE_SECONDS = Histogram(
    "chat_node_seconds", "채팅 그래프 노드별 소요 시간", ["node"], buckets=BUCKETS
)
LLM_SECONDS = Histogram("llm_call_seconds", "LLM 호출 시간", ["outcome"], buckets=BUCKETS)
EMBEDDING_SECONDS = Histogram("embedding_call_seconds", "임베딩 호출 시간", buckets=BUCKETS)
OPENAI_ERRORS = Counter("openai_errors_total", "OpenAI 호출 오류", ["type"])
LLM_TOKENS = Counter(
    "llm_tokens_total", "LLM 토큰 수(추론 토큰은 출력에 든다). 비용 계산용", ["direction"]
)
GUARD = Counter("guard_decisions_total", "가드 판정 횟수", ["where", "code"])

# 동기 엔드포인트는 anyio 스레드풀(기본 40개)에서 돈다. 가득 차면 요청이 줄을 선다 — 부하 테스트에서 찾은 병목이다.
THREADS_BORROWED = Gauge("threadpool_borrowed", "동기 엔드포인트 스레드풀에서 쓰는 스레드 수")
THREADS_LIMIT = Gauge("threadpool_limit", "동기 엔드포인트 스레드풀 상한")


def update_threadpool() -> None:
    """스레드풀 사용량을 지금 값으로 갱신한다. 스레드풀은 이벤트 루프별이라 /metrics(async) 안에서만 부른다."""
    limiter = anyio.to_thread.current_default_thread_limiter()
    THREADS_BORROWED.set(limiter.borrowed_tokens)
    THREADS_LIMIT.set(limiter.total_tokens)


class LlmMetrics(BaseCallbackHandler):
    """채팅 모델 호출마다 시간·토큰·오류를 센다. 구조화 출력·답변·보고서가 모두 지나간다."""

    def __init__(self) -> None:
        self.started: dict[UUID, float] = {}

    def on_chat_model_start(self, serialized, messages, *, run_id: UUID, **kwargs) -> None:
        self.started[run_id] = time.perf_counter()

    def on_llm_end(self, response, *, run_id: UUID, **kwargs) -> None:
        for generations in response.generations:
            for generation in generations:
                usage = generation.message.usage_metadata or {}
                LLM_TOKENS.labels("input").inc(usage.get("input_tokens", 0))
                LLM_TOKENS.labels("output").inc(usage.get("output_tokens", 0))
        self._observe(run_id, "ok")

    def on_llm_error(self, error: BaseException, *, run_id: UUID, **kwargs) -> None:
        OPENAI_ERRORS.labels(type(error).__name__).inc()
        self._observe(run_id, "error")

    def _observe(self, run_id: UUID, outcome: str) -> None:
        if (start := self.started.pop(run_id, None)) is not None:
            LLM_SECONDS.labels(outcome).observe(time.perf_counter() - start)
