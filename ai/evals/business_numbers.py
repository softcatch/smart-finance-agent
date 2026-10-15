"""사업의 내용 답변의 숫자 평가(NFR-016). pytest가 아니라 실제 OpenAI와 DB가 필요하다.

질문마다 사업의 내용 답변을 만들고, 답변의 숫자 중 근거(LLM에 보여 준 공시 블록·대화)에 없는 것이
있는 답변의 비율을 잰다. 가드 없이 나갔다면 사용자가 받았을 '근거에 없는 숫자가 든 답변'의 비율이
1단계(프롬프트만) 기준선이고, 2단계 숫자 존재 대조가 교체하는 대상이다.
LLM이 들어간 측정이라 같은 세트를 RUNS번 돌려 평균과 범위를 적는다.
실행(ai/에서, .env의 키·DB 접속 정보를 읽는다):
    set -a; . ../.env; set +a; POSTGRES_HOST=127.0.0.1 uv run python -m evals.business_numbers
"""

import json
from collections import Counter
from pathlib import Path

from langchain_core.messages import HumanMessage

from app.business import answer_business
from app.guard import unverified_numbers
from app.main import make_llm, search_business

CASES = Path(__file__).with_name("business_numbers.jsonl")
RUNS = 3


def main() -> None:
    llm = make_llm()
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines()]

    flagged_runs, numeric_runs, answered_runs = [], [], []
    flagged = Counter()  # (질문, 근거에 없는 숫자) → 걸린 횟수
    lines: dict[tuple[str, str], str] = {}  # 그 숫자가 든 답변 줄(확인용)
    for _ in range(RUNS):
        flagged_n = numeric_n = answered_n = 0
        for case in cases:
            messages = [HumanMessage(case["question"])]
            companies, _, chunks = search_business([case["company"]], case["question"])
            found = [(stock, name) for stock, name in companies if chunks.get(stock)]
            if not found:
                continue
            answered_n += 1
            reply, evidence = answer_business(llm, messages, found, chunks)
            unverified = unverified_numbers(reply, evidence)
            numeric_n += any(ch.isdigit() for ch in reply)
            flagged_n += bool(unverified)
            for number in unverified:
                key = (case["question"], number)
                flagged[key] += 1
                line = next((ln for ln in reply.splitlines() if number in ln.replace(",", "")), "")
                lines.setdefault(key, line.strip()[:110])
        flagged_runs.append(flagged_n)
        numeric_runs.append(numeric_n)
        answered_runs.append(answered_n)

    answered = sum(answered_runs) / RUNS
    mean_flagged = sum(flagged_runs) / RUNS
    mean_numeric = sum(numeric_runs) / RUNS
    print(f"질문 {len(cases)}개 × {RUNS}회, 검색 결과가 있어 답한 질문 평균 {answered:.1f}개")
    print(f"숫자가 든 답변: 평균 {mean_numeric:.1f}/{answered:.1f}")
    print(
        f"근거에 없는 숫자가 든 답변: 평균 {mean_flagged:.1f}/{answered:.1f} = "
        f"{mean_flagged / answered:.0%} (범위 {min(flagged_runs)}~{max(flagged_runs)}회)"
    )
    for (question, number), count in sorted(flagged.items(), key=lambda kv: (kv[0][0], -kv[1])):
        print(f"  ✗ {question!r} · {number} ({RUNS}회 중 {count}회) — {lines[(question, number)]}")


if __name__ == "__main__":
    main()
