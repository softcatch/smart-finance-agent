"""기업명 인식 평가(NFR-044). pytest가 아니라 실제 OpenAI와 DB가 필요하다.

각 발화에서 재무 의도(is_financial)를 잡고 뽑은 기업 이름이 기대한 종목으로 이어지는지 센다.
LLM이 들어간 측정이라 같은 세트를 RUNS번 돌려 평균과 범위를 적는다.
실행(ai/에서, .env의 키·DB 접속 정보를 읽는다):
    set -a; . ../.env; set +a; uv run python -m evals.company_names
"""

import json
from collections import Counter
from pathlib import Path

from langchain_core.messages import HumanMessage, SystemMessage

from app.graph import EXTRACT_PROMPT
from app.main import compare_financials, make_llm
from app.transfer import TransferSlots

CASES = Path(__file__).with_name("company_names.jsonl")
RUNS = 3


def main() -> None:
    extractor = make_llm().with_structured_output(TransferSlots)
    prompt = EXTRACT_PROMPT.format(pending="{}")
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines()]

    intent_runs, name_runs = [], []
    failed = Counter()  # 발화별로 몇 번의 실행에서 틀렸는지
    for _ in range(RUNS):
        intent_ok = name_ok = 0
        for case in cases:
            slots = extractor.invoke([SystemMessage(prompt), HumanMessage(case["message"])])
            found, _, _ = compare_financials(slots.companies) if slots.companies else ([], [], {})
            intent_ok += slots.is_financial
            if slots.is_financial and case["stock_code"] in [stock for stock, _ in found]:
                name_ok += 1
            else:
                failed[case["message"]] += 1
        intent_runs.append(intent_ok)
        name_runs.append(name_ok)

    total = len(cases)

    def summary(runs: list[int]) -> str:
        mean = sum(runs) / len(runs)
        return f"평균 {mean:.1f}/{total} = {mean / total:.0%} (범위 {min(runs)}~{max(runs)}, {len(runs)}회)"

    print(f"재무 의도 인식: {summary(intent_runs)}")
    print(f"기업명 인식(정식 이름 일치): {summary(name_runs)}")
    for message, count in failed.most_common():
        print(f"  ✗ {message!r}: {RUNS}회 중 {count}회 실패")


if __name__ == "__main__":
    main()
