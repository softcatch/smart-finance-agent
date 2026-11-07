"""송금 발화 평가(NFR-014 평가 세트의 첫 조각, NFR-055의 전후 비교). pytest가 아니라 실제 OpenAI가 필요하다.

발화(턴 목록)를 앱의 그래프에 흘리고, 송금 요청을 만드는 순간(create_transfer) 넘어온 값이 정답과 같은지 본다.
금액과 수취인(계좌번호 또는 별칭)이 모두 같아야 맞다. 요청이 안 만들어졌거나(되물음) 값이 다르면 틀린 것이다.
되묻기 재현율·틀린 확인 카드 수는 NFR-014에서 이 세트를 넓혀 잰다.
수취인이 계좌번호인 발화와 별칭인 발화를 나눠 센다 — 가명화(NFR-055)는 계좌번호 발화에만 닿는다.
틀린 발화는 의도 추출기가 턴마다 뽑은 값으로 실패 종류를 나눈다(송금 의도 놓침·수취인 빈 값·조사 포함 등).
LLM이 들어간 측정이라 같은 세트를 RUNS번 돌려 평균과 범위를 적는다.
실행(ai/에서, .env의 키를 읽는다):
    set -a; . ../.env; set +a; uv run python -m evals.transfer_utterances
"""

import json
from collections import Counter
from pathlib import Path

import openai
from langchain_core.runnables import RunnableLambda

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


# 틀린 발화의 실패 종류. slots는 의도 추출기가 턴마다 뽑은 값이다. 계좌번호는 가명화돼 [계좌N]으로 뽑히므로
# 있는지만 본다. 수취인이 정답을 품고 더 길면(엄마한테) 조사·꾸밈이 붙은 것이다.
def failure_kind(case: dict, slots: list[TransferSlots]) -> str:
    if not any(s.is_transfer for s in slots):
        return "송금 의도 놓침"
    field = "recipient_account_number" if "account" in case else "alias"
    got = next((getattr(s, field) for s in reversed(slots) if getattr(s, field)), None)
    amount = next((s.amount for s in reversed(slots) if s.amount is not None), None)
    if got is None:
        return "수취인 빈 값"
    if field == "alias" and got != case["alias"]:
        return "수취인에 조사·꾸밈" if case["alias"] in got else "수취인 다른 값"
    if amount != case["amount"]:
        return "금액 틀림·빠짐"
    return "값은 맞는데 요청 안 됨"


# 한 대화를 그래프에 흘려 → (송금 요청이 만들어질 때 받은 값(없으면 None), 턴마다 추출기가 뽑은 값)
def run_case(llm, extractor, case: dict, thread: str) -> tuple[dict | None, list[TransferSlots]]:
    created: list[dict] = []
    slots: list[TransferSlots] = []

    def create_transfer(pending: dict) -> dict:
        created.append(dict(pending))
        return CARD

    def extract(messages) -> TransferSlots:
        slots.append(extractor.invoke(messages))
        return slots[-1]

    graph = build_graph(llm, RunnableLambda(extract))
    config = {"configurable": {"thread_id": thread, "create_transfer": create_transfer}}
    for turn in case["turns"]:
        graph.invoke({"message": mask_pii(turn)}, config)  # 엔드포인트가 그래프에 넣기 전에 하는 일
    return (created[0] if created else None), slots


def main() -> None:
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines()]
    # 응답 없이 멈추거나 끊긴 호출 하나가 몇 분짜리 측정을 망치지 않게 타임아웃을 두고 일시 오류는 다시 보낸다.
    # 앱의 동작이 아니라 측정 도구의 안전장치다(정확도에는 영향이 없다).
    # 의도 추출기와 답변 모델(되묻기·일반 답변) 모두에 건다 — 한쪽만 걸면 다른 쪽의 타임아웃 하나가 측정을 멈춘다.
    model = make_llm(timeout=30)
    retry = {
        "retry_if_exception_type": (openai.APIConnectionError, openai.InternalServerError),
        "stop_after_attempt": 3,
    }
    llm = model.with_retry(**retry)
    extractor = model.with_structured_output(TransferSlots).with_retry(**retry)
    scores: dict[str, list[int]] = {"계좌번호": [], "별칭": []}
    failed: Counter = Counter()
    kinds: Counter = Counter()  # (발화 종류, 실패 종류) → 건수
    got: dict[str, dict | None] = {}
    for run in range(RUNS):
        run_score = Counter()
        for i, case in enumerate(cases):
            pending, slots = run_case(llm, extractor, case, f"utter-{run}-{i}")
            kind = "계좌번호" if "account" in case else "별칭"
            if matches(case, pending):
                run_score[kind] += 1
            else:
                key = " / ".join(case["turns"])
                failed[key] += 1
                kinds[kind, failure_kind(case, slots)] += 1
                got[key] = [s.model_dump(include={"is_transfer", "amount", "alias"}) for s in slots]
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
    print(f"실패 종류({RUNS}회 합계):")
    for (kind, reason), count in sorted(kinds.items(), key=lambda kv: -kv[1]):
        print(f"  {kind} 발화 · {reason}: {count}")
    for key, count in sorted(failed.items(), key=lambda kv: -kv[1]):
        print(f"  ✗ {key!r} ({RUNS}회 중 {count}회) — 추출기가 뽑은 값(마지막 회) {got[key]}")


if __name__ == "__main__":
    main()
