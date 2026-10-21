"""사업의 내용 답변의 숫자 평가(NFR-016·052). pytest가 아니라 실제 OpenAI와 DB가 필요하다.

질문마다 앱의 그래프로 사업의 내용 턴을 돌리고(의도 추출만 고정), 답변의 숫자 중 근거(LLM에 보여 준
공시 블록)에 없는 것이 있는 답변의 비율을 잰다. 첫 답변의 비율이 1단계(프롬프트만) 기준선이고,
걸린 턴은 재검색(NFR-052)을 한 번 더 돌아 재검색 후 통과율과 재검색이 더한 시간을 함께 잰다.
LLM이 들어간 측정이라 같은 세트를 RUNS번 돌려 평균과 범위를 적는다.
실행(ai/에서, .env의 키·DB 접속 정보를 읽는다):
    set -a; . ../.env; set +a; POSTGRES_HOST=127.0.0.1 uv run python -m evals.business_numbers
"""

import json
from collections import Counter
from pathlib import Path
from statistics import median
from time import perf_counter

from langchain_core.runnables import RunnableLambda

from app.graph import build_graph
from app.main import make_llm, search_business
from app.transfer import TransferSlots

CASES = Path(__file__).with_name("business_numbers.jsonl")
RUNS = 3


# 한 턴을 그래프로 돌려 노드 출력을 순서대로 모은다 → [(노드, 출력, 초)]. 의도는 사업의 내용으로 고정한다.
def _turn(llm, case: dict, thread: str) -> list[tuple[str, dict, float]]:
    slots = TransferSlots(is_transfer=False, is_business=True, companies=[case["company"]])
    graph = build_graph(llm, RunnableLambda(lambda _: slots))
    config = {"configurable": {"thread_id": thread, "search_business": search_business}}
    steps, last = [], perf_counter()
    for chunk in graph.stream({"message": case["question"]}, config, stream_mode="updates"):
        ((node, update),) = chunk.items()
        now = perf_counter()
        steps.append((node, update or {}, now - last))
        last = now
    return steps


def main() -> None:
    llm = make_llm()
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines()]

    flagged_runs, numeric_runs, answered_runs, passed_runs = [], [], [], []
    flagged = Counter()  # (질문, 근거에 없는 숫자) → 첫 답변에서 걸린 횟수
    lines: dict[tuple[str, str], str] = {}  # 그 숫자가 든 답변 줄(확인용)
    retried = Counter()  # 질문 → 재검색 뒤 통과한 횟수(키가 있으면 재검색한 질문)
    queries: dict[str, str] = {}  # 질문 → 고친 검색어(처음 것)
    first_s, retry_s = (
        [],
        [],
    )  # 첫 답변(business)의 시간, 재검색이 더한 시간(rewrite + 두 번째 business)
    for run in range(RUNS):
        flagged_n = numeric_n = answered_n = passed_n = 0
        for i, case in enumerate(cases):
            steps = _turn(llm, case, f"eval-{run}-{i}")
            business = [(update, s) for node, update, s in steps if node == "business"]
            first, seconds = business[0]
            if "unverified" not in first:  # 검색 결과가 없어 LLM을 부르지 않은 턴
                continue
            answered_n += 1
            first_s.append(seconds)
            numeric_n += any(ch.isdigit() for ch in first["reply"])
            flagged_n += bool(first["unverified"])
            for number in first["unverified"]:
                key = (case["question"], number)
                flagged[key] += 1
                line = next(
                    (ln for ln in first["reply"].splitlines() if number in ln.replace(",", "")), ""
                )
                lines.setdefault(key, line.strip()[:110])
            if len(business) == 2:
                rewrite_s = next(s for node, _, s in steps if node == "rewrite")
                retry_s.append(rewrite_s + business[1][1])
                queries.setdefault(
                    case["question"], next(u for n, u, _ in steps if n == "rewrite")["query"]
                )
                passed = not business[1][0].get("unverified")
                passed_n += passed
                retried[case["question"]] += passed
        flagged_runs.append(flagged_n)
        numeric_runs.append(numeric_n)
        answered_runs.append(answered_n)
        passed_runs.append(passed_n)

    answered = sum(answered_runs) / RUNS
    mean_flagged = sum(flagged_runs) / RUNS
    mean_numeric = sum(numeric_runs) / RUNS
    mean_passed = sum(passed_runs) / RUNS
    print(f"질문 {len(cases)}개 × {RUNS}회, 검색 결과가 있어 답한 질문 평균 {answered:.1f}개")
    print(f"숫자가 든 답변: 평균 {mean_numeric:.1f}/{answered:.1f}")
    print(
        f"첫 답변에 근거에 없는 숫자(= 재검색한 턴): 평균 {mean_flagged:.1f}/{answered:.1f} = "
        f"{mean_flagged / answered:.0%} (범위 {min(flagged_runs)}~{max(flagged_runs)}회)"
    )
    for (question, number), count in sorted(flagged.items(), key=lambda kv: (kv[0][0], -kv[1])):
        print(f"  ✗ {question!r} · {number} ({RUNS}회 중 {count}회) — {lines[(question, number)]}")
    left = mean_flagged - mean_passed
    print(
        f"재검색 뒤 통과: 평균 {mean_passed:.1f}/{mean_flagged:.1f}, "
        f"최종 근거에 없는 숫자가 든 답변: 평균 {left:.1f}/{answered:.1f} = {left / answered:.0%} "
        f"(범위 {min(f - p for f, p in zip(flagged_runs, passed_runs))}~"
        f"{max(f - p for f, p in zip(flagged_runs, passed_runs))}회)"
    )
    for question, passed in sorted(retried.items()):
        print(f"  ↻ {question!r} → {queries[question]!r} (통과 {passed}회)")
    print(
        f"첫 검색·답변(business) 시간: 중앙값 {median(first_s):.1f}초, 최대 {max(first_s):.1f}초 ({len(first_s)}턴)"
    )
    if retry_s:
        print(
            f"재검색이 더한 시간: 중앙값 {median(retry_s):.1f}초, 최대 {max(retry_s):.1f}초 "
            f"({len(retry_s)}턴, 재작성 + 임베딩·검색 + 재답변)"
        )


if __name__ == "__main__":
    main()
