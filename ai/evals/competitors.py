"""경쟁사 비교 평가(FR-019). pytest가 아니라 실제 OpenAI와 DB가 필요하다.

문장마다 채팅 그래프를 끝까지 돌려, 비교 대상이 기대한 기업(종목코드, 순서까지)으로 나오는지 센다.
기업은 정식 이름으로 말한 문장이다 — 별칭·줄임말은 기업명 인식 평가(company_names)가 잰다.
stocks가 null인 문장은 재무 비교 표가 나오면 안 된다(사업의 내용·일반 질문).
LLM이 들어간 측정이라 같은 세트를 RUNS번 돌려 평균과 범위를 적는다.
실행(ai/에서, .env의 키·DB 접속 정보를 읽는다):
    set -a; . ../.env; set +a; POSTGRES_HOST=127.0.0.1 uv run python -m evals.competitors
"""

import json
from collections import Counter
from pathlib import Path
from uuid import uuid4

from app.main import compare_financials, get_graph

CASES = Path(__file__).with_name("competitors.jsonl")
RUNS = 3


def main() -> None:
    graph = get_graph()
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines()]
    runs = []
    failed = Counter()  # 문장별로 몇 번의 실행에서 틀렸는지
    for _ in range(RUNS):
        ok = 0
        for case in cases:
            config = {
                "configurable": {
                    "thread_id": f"th_{uuid4().hex}",
                    "compare_financials": compare_financials,
                    "search_business": lambda names, question: (
                        [],
                        [],
                        {},
                    ),  # 사업 질문은 조회하지 않는다
                }
            }
            out = graph.invoke({"message": case["message"]}, config)
            stocks = out["detail"]["stocks"] if out.get("detail") else None
            if stocks == case["stocks"]:
                ok += 1
            else:
                failed[case["message"]] += 1
        runs.append(ok)

    total = len(cases)
    mean = sum(runs) / len(runs)
    print(
        f"기대한 비교 대상: 평균 {mean:.1f}/{total} = {mean / total:.0%} "
        f"(범위 {min(runs)}~{max(runs)}, {len(runs)}회)"
    )
    for message, count in failed.most_common():
        print(f"  ✗ {message!r}: {RUNS}회 중 {count}회 실패")


if __name__ == "__main__":
    main()
