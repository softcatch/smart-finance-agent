"""송금 발화 평가(NFR-014 평가 세트의 첫 조각, NFR-055의 전후 비교). pytest가 아니라 실제 OpenAI가 필요하다.

발화(턴 목록)를 앱의 그래프에 흘리고, 송금 요청을 만드는 순간(create_transfer) 넘어온 값이 정답과 같은지 본다.
금액과 수취인(계좌번호 또는 별칭)이 모두 같아야 맞다. 요청이 안 만들어졌거나(되물음) 값이 다르면 틀린 것이다.
되묻기 재현율·틀린 확인 카드 수는 NFR-014에서 이 세트를 넓혀 잰다.
수취인이 계좌번호인 발화와 별칭인 발화를 나눠 센다 — 가명화(NFR-055)는 계좌번호 발화에만 닿는다.
LLM이 들어간 측정이라 같은 세트를 RUNS번 돌려 평균과 범위를 적는다.
실행(ai/에서, .env의 키를 읽는다):
    set -a; . ../.env; set +a; uv run python -m evals.transfer_utterances
"""

import json
from collections import Counter
from pathlib import Path

from app.graph import build_graph
from app.guard import mask_pii
from app.main import make_llm
from app.transfer import TransferSlots

CASES = Path(__file__).with_name("transfer_utterances.jsonl")
RUNS = 3
CARD = {"requestId": "tr_1", "recipientName": "김영희", "amount": 0}


# 송금 요청을 만들 때 받은 값(pending)이 정답과 같은지. 요청이 안 만들어졌으면 pending은 None이다.
def matches(case: dict, pending: dict | None) -> bool:
    if pending is None or pending.get("amount") != case["amount"]:
        return False
    if "account" in case:
        return pending.get("recipient_account_number") == case["account"]
    return pending.get("alias") == case["alias"]


# 한 대화를 그래프에 흘려 송금 요청이 만들어질 때 받은 값을 돌려준다(없으면 None)
def run_case(llm, extractor, case: dict, thread: str) -> dict | None:
    created: list[dict] = []

    def create_transfer(pending: dict) -> dict:
        created.append(dict(pending))
        return CARD

    graph = build_graph(llm, extractor)
    config = {"configurable": {"thread_id": thread, "create_transfer": create_transfer}}
    for turn in case["turns"]:
        graph.invoke({"message": mask_pii(turn)}, config)  # 엔드포인트가 그래프에 넣기 전에 하는 일
    return created[0] if created else None


def main() -> None:
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines()]
    llm = make_llm()
    extractor = llm.with_structured_output(TransferSlots)
    scores: dict[str, list[int]] = {"계좌번호": [], "별칭": []}
    failed: Counter = Counter()
    got: dict[str, dict | None] = {}
    for run in range(RUNS):
        run_score = Counter()
        for i, case in enumerate(cases):
            pending = run_case(llm, extractor, case, f"utter-{run}-{i}")
            if matches(case, pending):
                run_score["계좌번호" if "account" in case else "별칭"] += 1
            else:
                key = " / ".join(case["turns"])
                failed[key] += 1
                got[key] = pending
        for kind, per_run in scores.items():
            per_run.append(run_score[kind])
    sizes = {
        "계좌번호": sum("account" in c for c in cases),
        "별칭": sum("alias" in c for c in cases),
    }
    print(f"송금 발화 {len(cases)}개 × {RUNS}회: 금액·수취인 일치(평균, 범위)")
    for kind, per_run in scores.items():
        mean = sum(per_run) / RUNS
        print(
            f"  {kind} 발화 {mean:.1f}/{sizes[kind]} = {mean / sizes[kind]:.0%} "
            f"(범위 {min(per_run)}~{max(per_run)})"
        )
    total = [sum(v) for v in zip(*scores.values(), strict=True)]
    print(f"  전체 {sum(total) / RUNS:.1f}/{len(cases)} = {sum(total) / RUNS / len(cases):.0%}")
    for key, count in sorted(failed.items(), key=lambda kv: -kv[1]):
        print(f"  ✗ {key!r} ({RUNS}회 중 {count}회) — 받은 값 {got[key]}")


if __name__ == "__main__":
    main()
