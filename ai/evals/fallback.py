"""장애 대체(NFR-056) 측정. pytest가 아니라 가짜 LLM과 실제 앱 서버를 띄워서 몇 분 걸린다.

가짜 OpenAI·Anthropic 서버(loadtest/fake_llm.py)에 오류나 멈춤을 넣고 실제 FastAPI 앱에 채팅을 건다.
Spring이 거는 것과 같은 HTTP 경로이고 앱 코드는 그대로다(환경변수만 바꾼다). 턴마다 의도 추출(구조화 출력)과
일반 답변으로 LLM을 두 번 부른다. 지연은 실제 OpenAI를 재서 남긴 분포(latency.json)이고, 실측하지 못한
Claude의 지연도 같은 분포를 쓴다. 같은 시나리오를 대체(Claude)가 있을 때와 없을 때 잰다.
실행(ai/에서):
    uv run python -m evals.fallback
"""

import base64
import json
import math
import os
import threading
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import httpx

from app import main
from loadtest.fake_llm import LATENCY, Faults, create_app, serve

TURNS = 60
WORKERS = 12
LIMIT = 10.0  # Spring 채팅 읽기 제한(초). 이보다 늦은 응답은 사용자에게 가지 못한다
TOKEN = "Bearer h." + base64.urlsafe_b64encode(b'{"sub":"9"}').decode().rstrip("=") + ".s"

# (이름, OpenAI 장애, Anthropic 장애, 대체가 있을 때만 재는지). 비율은 LLM 호출 하나당이다.
SCENARIOS = [
    ("정상", Faults(), Faults(), False),
    ("OpenAI 오류 20%(503)", Faults(error_rate=0.2), Faults(), False),
    ("OpenAI 전체 장애(503)", Faults(error_rate=1.0), Faults(), False),
    ("OpenAI 멈춤 20%(30초)", Faults(hang_rate=0.2), Faults(), False),
    ("OpenAI·Claude 전체 장애", Faults(error_rate=1.0), Faults(error_rate=1.0), True),
]


# 작은 수부터 세어 올림한 순번의 값(30개 중 p95는 29번째 — 지연 실측과 같은 방식)
def percentile(values: list[float], q: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(q * len(ordered)) - 1)]


# results: [(HTTP 상태, 턴 초)], calls: (모델, 대체 여부) → 성공한 LLM 호출 수
def summarize(results: list[tuple[int, float]], calls: Counter) -> dict:
    ok = [seconds for status, seconds in results if status == 200]
    total = sum(calls.values())
    return {
        "in_time": sum(s <= LIMIT for s in ok),
        "late": sum(s > LIMIT for s in ok),
        "errors": len(results) - len(ok),
        "p50": percentile(ok, 0.5) if ok else None,
        "p95": percentile(ok, 0.95) if ok else None,
        "fallback": sum(n for (_, fallback), n in calls.items() if fallback) / total
        if total
        else None,
    }


def run(samples: dict, openai: Faults, anthropic: Faults, claude: bool) -> dict:
    fake_url, stop_fake = serve(create_app(samples, 1, openai=openai, anthropic=anthropic))
    os.environ["OPENAI_API_KEY"] = "fake"
    os.environ["OPENAI_BASE_URL"] = fake_url
    os.environ["ANTHROPIC_API_URL"] = fake_url.removesuffix("/v1")
    os.environ["ANTHROPIC_API_KEY"] = "fake" if claude else ""
    calls, lock = Counter(), threading.Lock()

    def count(user_id, model, input_tokens, output_tokens, fallback=False):
        with lock:
            calls[(model, fallback)] += 1

    main.record_usage = count  # DB 대신 센다. 모델을 만들 때 이 함수가 연결된다
    main.log_chat = lambda *args: None
    main.get_graph.cache_clear()
    app_url, stop_app = serve(main.app)

    def turn(_) -> tuple[int, float]:
        start = time.perf_counter()
        try:
            res = httpx.post(
                f"{app_url.removesuffix('/v1')}/internal/chat",
                json={"threadId": f"fb-{uuid4().hex}", "message": "안녕하세요"},
                headers={"Authorization": TOKEN},
                timeout=60,
            )
            status = res.status_code
        except httpx.HTTPError:
            status = 599
        return status, time.perf_counter() - start

    try:
        with ThreadPoolExecutor(WORKERS) as pool:
            results = list(pool.map(turn, range(TURNS)))
    finally:
        stop_app()
        stop_fake()
    return summarize(results, calls)


def seconds(value: float | None) -> str:
    return "-" if value is None else f"{value:.1f}"


def measure() -> None:
    samples = json.loads(LATENCY.read_text(encoding="utf-8"))["samples_ms"]
    print(
        f"가짜 LLM, 턴 {TURNS}개·동시 {WORKERS}, 턴마다 LLM 2번(의도 추출·일반 답변). 성공 = 200이고 {LIMIT:.0f}초 안"
    )
    print(
        f"{'시나리오':<24}{'대체':<5}{'성공':>6}{'늦음':>6}{'오류':>6}{'p50(초)':>9}{'p95(초)':>9}{'대체 호출':>10}"
    )
    for name, openai, anthropic, only_with in SCENARIOS:
        for claude in (True,) if only_with else (False, True):
            row = run(samples, openai, anthropic, claude)
            fallback = "-" if row["fallback"] is None else f"{row['fallback']:.0%}"
            print(
                f"{name:<22}{'있음' if claude else '없음':<5}{row['in_time']:>6}{row['late']:>6}"
                f"{row['errors']:>6}{seconds(row['p50']):>9}{seconds(row['p95']):>9}{fallback:>10}",
                flush=True,
            )


if __name__ == "__main__":
    measure()
