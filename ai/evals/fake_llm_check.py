"""가짜 LLM 검증(NFR-029). pytest가 아니라 실제 OpenAI와 DB가 필요하다.

같은 채팅 한 턴(일반·재무·사업의 내용)과 상세 보고서를 실제 OpenAI와 가짜 LLM(실측 분포 재생)으로 각각
돌려 소요의 p50·p95를 비교한다. 가짜로 잰 부하 결과가 실제와 크게 어긋나지 않는지 보는 검증이다.
턴은 지연 실측과 같은 조건(동시 4개)으로 돌린다.
실행(ai/에서, .env의 키·DB 접속 정보를 읽는다):
    set -a; . ../.env; set +a; POSTGRES_HOST=127.0.0.1 uv run python -m evals.fake_llm_check
"""

import json
import os
from uuid import uuid4

from app.financial import build_detail_table, write_report
from app.graph import build_graph
from app.main import _connect, compare_financials, make_llm, search_business
from app.store import find_companies, load_financials
from evals.llm_latency import REPORT_STOCKS, percentile, run_all, take
from loadtest.fake_llm import LATENCY, create_app, serve

TURNS = 20  # 종류마다
MESSAGES = {
    "일반 답변": ["안녕", "예금과 적금의 차이가 뭐야?", "ETF가 뭐야?", "복리가 뭐야?"],
    "재무 비교": [
        "삼성전자랑 SK하이닉스 실적 비교해줘",
        "현대자동차 영업이익 알려줘",
        "NAVER 매출 비교해줘",
    ],
    "사업의 내용": [
        "삼성전자 사업 내용 알려줘",
        "SK하이닉스 주요 제품이 뭐야?",
        "현대자동차 사업 구조 알려줘",
        "카카오 사업 소개해줘",
    ],
}


def measure(llm) -> dict[str, list[int]]:
    graph = build_graph(llm)
    tables = []
    with _connect() as conn:
        for stocks in REPORT_STOCKS:
            companies, _ = find_companies(conn, stocks)
            tables.append(
                build_detail_table(companies, load_financials(conn, [s for s, _ in companies]))
            )

    def turn(message: str) -> None:
        config = {
            "configurable": {
                "thread_id": f"th_{uuid4().hex}",
                "compare_financials": compare_financials,
                "search_business": search_business,
            }
        }
        graph.invoke({"message": message}, config)

    jobs = {
        kind: [lambda m=m: turn(m) for m in take(messages, TURNS)]
        for kind, messages in MESSAGES.items()
    }
    jobs["상세 보고서"] = [lambda t=t: write_report(llm, t) for t in take(tables, TURNS)]
    return {kind: sorted(ms for ms in run_all(calls) if ms) for kind, calls in jobs.items()}


def main() -> None:
    os.environ.pop("OPENAI_BASE_URL", None)
    real = measure(make_llm())

    samples = json.loads(LATENCY.read_text(encoding="utf-8"))["samples_ms"]
    url, stop = serve(create_app(samples, seed=1))
    os.environ["OPENAI_BASE_URL"] = url  # 앱 코드는 그대로, 환경변수만 가짜로 돌린다
    try:
        fake = measure(make_llm())
    finally:
        stop()

    print(
        f"{'턴':<12}{'n':>4}  {'실제 p50':>9}{'실제 p95':>9}  {'가짜 p50':>9}{'가짜 p95':>9}  p50 차이"
    )
    for kind, ordered in real.items():
        other = fake[kind]
        p50, p95 = percentile(ordered, 0.5), percentile(ordered, 0.95)
        f50, f95 = percentile(other, 0.5), percentile(other, 0.95)
        print(
            f"{kind:<10}{len(ordered):>4}/{len(other):<3}{p50:>9}{p95:>9}  {f50:>9}{f95:>9}"
            f"  {(f50 - p50) / p50:+.0%}"
        )


if __name__ == "__main__":
    main()
