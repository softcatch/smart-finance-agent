"""LLM 호출 지연 실측(NFR-029). pytest가 아니라 실제 OpenAI와 DB가 필요하다.

호출 종류별(의도 추출·일반 답변·사업의 내용 답변·상세 보고서·임베딩)로 실제 OpenAI 호출 시간을 재서
p50·p95를 적고, 가짜 LLM(loadtest/fake_llm.py)이 재생할 분포를 loadtest/latency.json에 쓴다.
앱이 쓰는 함수와 프롬프트를 그대로 부르고(질문·기업·표는 로컬 실데이터), 재는 시간은 OpenAI 호출만이다.
실행 시간을 줄이려고 호출 4개를 동시에 보낸다 — 한 호출이 다른 호출을 늦추지 않는다고 본다.
실행(ai/에서, .env의 키·DB 접속 정보를 읽는다):
    set -a; . ../.env; set +a; POSTGRES_HOST=127.0.0.1 uv run python -m evals.llm_latency
"""

import json
import math
import os
import time
from concurrent.futures import ThreadPoolExecutor
from itertools import cycle, islice
from pathlib import Path

from langchain_core.messages import HumanMessage, SystemMessage
from openai import OpenAIError

from app.business import answer_business, embed_texts
from app.financial import build_detail_table, write_report
from app.graph import EXTRACT_PROMPT, SYSTEM_PROMPT
from app.history import today_kst
from app.main import _connect, make_llm
from app.store import find_companies, load_financials, search_business_chunks
from app.transfer import TransferSlots

OUT = Path(__file__).resolve().parent.parent / "loadtest" / "latency.json"
SAMPLES = 30  # 호출 종류마다
WORKERS = 4

EXTRACT_MESSAGES = [
    "홍길동에게 1만원 보내줘",
    "엄마한테 3만원 보내줘",
    "계좌 123456789012로 5만원 송금해줘",
    "지난달에 얼마 보냈어?",
    "최근 7일 송금 내역 알려줘",
    "삼성전자 영업이익 알려줘",
    "삼성전자랑 SK하이닉스 실적 비교해줘",
    "현대자동차 경쟁사 비교해줘",
    "삼성전자 사업 내용 알려줘",
    "예금이랑 적금 차이가 뭐야?",
]
ANSWER_MESSAGES = [
    "안녕",
    "예금과 적금의 차이가 뭐야?",
    "ETF가 뭐야?",
    "주식 시장은 몇 시에 열려?",
    "복리가 뭐야?",
]
# 사업의 내용 질문은 부하 시나리오와 같은 일반 질문 꼴만 쓴다(숫자 질문은 환각 평가 몫이고, 요약이 짧아 더 빠르다).
GENERIC_BUSINESS = [
    "{} 사업 내용 알려줘",
    "{} 주요 제품이 뭐야?",
    "{} 사업 구조 알려줘",
    "{} 회사 소개해줘",
]
GENERIC_COMPANIES = [
    "삼성전자",
    "SK하이닉스",
    "현대자동차",
    "기아",
    "현대모비스",
    "LG에너지솔루션",
    "삼성SDI",
    "NAVER",
    "카카오",
    "한국전력공사",
]
REPORT_STOCKS = [
    ["005930", "000660"],
    ["005380", "000270", "012330"],
    ["373220", "006400"],
    ["035420", "035720"],
    ["005930"],
]


def timed(call) -> int | None:
    start = time.perf_counter()
    try:
        call()
    except OpenAIError as err:  # 429 같은 실패는 표본에서 빼고 개수만 적는다
        print(f"  실패: {type(err).__name__}: {str(err)[:80]}")
        return None
    return round((time.perf_counter() - start) * 1000)


def run_all(calls: list) -> list[int | None]:
    with ThreadPoolExecutor(max_workers=WORKERS) as pool:
        return list(pool.map(timed, calls))


def percentile(ordered: list[int], q: float) -> int:  # 가장 가까운 순위(nearest-rank)
    return ordered[math.ceil(q * len(ordered)) - 1]


def take(items: list, n: int) -> list:
    return list(islice(cycle(items), n))


def main() -> None:
    llm = make_llm()
    extractor = llm.with_structured_output(TransferSlots)
    extract_prompt = EXTRACT_PROMPT.format(pending="{}")
    cases = [
        {"company": company, "question": template.format(company)}
        for template in GENERIC_BUSINESS
        for company in GENERIC_COMPANIES
    ]

    # 사업의 내용 답변·보고서의 입력은 시간을 재기 전에 만든다(검색·표 계산은 재지 않는다).
    business_inputs = []
    with _connect() as conn:
        for case in take(cases, SAMPLES):
            found, _ = find_companies(conn, [case["company"]])
            vector = embed_texts([case["question"]])[0]
            chunks = {stock: search_business_chunks(conn, stock, vector) for stock, _ in found}
            business_inputs.append((case["question"], found, chunks))
        report_tables = []
        for stocks in REPORT_STOCKS:
            companies, _ = find_companies(conn, stocks)
            table = build_detail_table(companies, load_financials(conn, [s for s, _ in companies]))
            report_tables.append(table)

    plans = {
        "extract": [
            lambda m=m: extractor.invoke([SystemMessage(extract_prompt), HumanMessage(m)])
            for m in take(EXTRACT_MESSAGES, SAMPLES)
        ],
        "answer": [
            lambda m=m: llm.invoke([SystemMessage(SYSTEM_PROMPT), HumanMessage(m)])
            for m in take(ANSWER_MESSAGES, SAMPLES)
        ],
        "business": [
            lambda q=q, f=f, c=c: answer_business(llm, [HumanMessage(q)], f, c)
            for q, f, c in business_inputs
        ],
        "report": [lambda t=t: write_report(llm, t) for t in take(report_tables, SAMPLES)],
        "embedding": [lambda c=c: embed_texts([c["question"]]) for c in take(cases, SAMPLES)],
    }

    samples: dict[str, list[int]] = {}
    print(f"{'호출':<10}{'n':>4}{'min':>8}{'p50':>8}{'p95':>8}{'max':>8}  (ms)")
    for name, calls in plans.items():
        results = run_all(calls)
        ordered = sorted(r for r in results if r is not None)
        samples[name] = ordered
        failed = len(results) - len(ordered)
        print(
            f"{name:<10}{len(ordered):>4}{ordered[0]:>8}{percentile(ordered, 0.5):>8}"
            f"{percentile(ordered, 0.95):>8}{ordered[-1]:>8}"
            + (f"  실패 {failed}" if failed else "")
        )

    OUT.parent.mkdir(exist_ok=True)
    meta = {
        "model": os.getenv("OPENAI_MODEL") or "gpt-5-nano",
        "reasoning_effort": os.getenv("OPENAI_REASONING_EFFORT", "low"),
        "measured_at": today_kst().isoformat(),
        "workers": WORKERS,
    }
    lines = [f" {json.dumps(k)}: {json.dumps(v, ensure_ascii=False)}," for k, v in meta.items()]
    body = ",\n".join(f"  {json.dumps(k)}: {json.dumps(v)}" for k, v in samples.items())
    OUT.write_text("{\n" + "\n".join(lines) + f'\n "samples_ms": {{\n{body}\n }}\n}}\n', "utf-8")
    print(f"분포를 {OUT.relative_to(OUT.parent.parent)}에 썼다.")


if __name__ == "__main__":
    main()
