"""개인정보 처리 평가(NFR-055). 가짜 LLM만 써서 키·DB가 필요 없다.

문장 세트(privacy.jsonl)를 앱의 그래프에 흘리고, LLM(의도 추출기·답변 모델)이 받은 모든 글을 모은다.
- 가려야 할 값(pii)이 그 글에 그대로 들어 있으면 '원문 유출'이다. 종류별로 센다.
- pii가 없는 정상 문장은 의도 추출기가 받은 사용자 글이 원문과 다르면 '오탐'(잘못 가림)이다.
문장은 턴 목록이다(송금 값을 이어 받는 두 턴 이상의 대화도 있다). 추출기 자리의 가짜는 LLM이 하듯
메시지에 보이는 수취인 표기(12자리 계좌번호 또는 [계좌N])를 그대로 뽑는다. 그래서 진행 중인 송금 값이
다음 턴 프롬프트에 실리는 길도 함께 본다.
실행(ai/에서):
    uv run python -m evals.privacy
"""

import json
import re
from collections import Counter
from pathlib import Path

from langchain_core.messages import AIMessage
from langchain_core.runnables import RunnableLambda

from app.graph import build_graph
from app.guard import mask_pii
from app.transfer import TransferSlots

CASES = Path(__file__).with_name("privacy.jsonl")
CARD = {"requestId": "tr_1", "recipientName": "김영희", "amount": 5000}
_ACCOUNT = re.compile(r"\[계좌\d+\]|\d{12}")
_AMOUNT = re.compile(r"([\d,]+)\s*원")


# 가려야 할 값 중 LLM이 받은 글에 그대로 남은 것 → {종류: [값]}. 없으면 빈 사전.
def leaked_values(seen: list[str], pii: dict[str, list[str]]) -> dict[str, list[str]]:
    found = {
        kind: [value for value in values if any(value in text for text in seen)]
        for kind, values in pii.items()
    }
    return {kind: values for kind, values in found.items() if values}


# 정상 문장을 LLM이 받은 글이 원문과 달라졌는지(잘못 가렸는지)
def altered(turns: list[str], received: list[str]) -> bool:
    return received != turns


# 한 대화(턴 목록)를 그래프에 흘려 → (LLM이 받은 모든 글, 턴마다 추출기가 받은 사용자 글)
def run_case(turns: list[str], thread: str) -> tuple[list[str], list[str]]:
    seen: list[str] = []
    received: list[str] = []

    def extract(messages) -> TransferSlots:
        seen.extend(m.content for m in messages)
        prompt, text = messages[0].content, messages[-1].content
        received.append(text)
        account, amount = _ACCOUNT.search(text), _AMOUNT.search(text)
        in_progress = not prompt.endswith("진행 중인 송금: {}")
        return TransferSlots(
            is_transfer=bool(account or in_progress),
            recipient_account_number=account[0] if account else None,
            amount=int(amount[1].replace(",", "")) if amount else None,
        )

    def answer(messages) -> AIMessage:
        seen.extend(m.content for m in messages)
        return AIMessage("네")

    graph = build_graph(RunnableLambda(answer), RunnableLambda(extract))
    config = {"configurable": {"thread_id": thread, "create_transfer": lambda pending: CARD}}
    for turn in turns:
        graph.invoke({"message": mask_pii(turn)}, config)  # 엔드포인트가 그래프에 넣기 전에 하는 일
    return seen, received


def load_cases() -> list[dict]:
    return [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines()]


# 세트 전체를 돌려 종류별 가려야 할 값 수·유출 수, 정상 문장 수·오탐 수, 확인할 예를 모은다.
def evaluate(cases: list[dict]) -> dict:
    total: Counter = Counter()
    leaked: Counter = Counter()
    examples: list[str] = []
    controls = changed = 0
    for i, case in enumerate(cases):
        seen, received = run_case(case["turns"], f"privacy-{i}")
        if case["pii"]:
            for kind, values in case["pii"].items():
                total[kind] += len(values)
            for kind, values in leaked_values(seen, case["pii"]).items():
                leaked[kind] += len(values)
                examples.append(f"{kind} {values} ← {case['turns']}")
        else:
            controls += 1
            if altered(case["turns"], received):
                changed += 1
                examples.append(f"오탐 {case['turns']} → {received}")
    return {
        "total": total,
        "leaked": leaked,
        "pii_cases": len(cases) - controls,
        "controls": controls,
        "changed": changed,
        "examples": examples,
    }


def main() -> None:
    result = evaluate(load_cases())
    total, leaked = result["total"], result["leaked"]
    print(
        f"개인정보 문장 {result['pii_cases']}개, 가려야 할 값 {sum(total.values())}개 — "
        "LLM이 받은 글에 원문이 남은 값:"
    )
    for kind in total:
        print(f"  {kind:<6} {leaked[kind]}/{total[kind]}")
    print(f"  합계   {sum(leaked.values())}/{sum(total.values())}")
    controls, changed = result["controls"], result["changed"]
    print(f"정상 문장 {controls}개 중 잘못 가린 문장 {changed}개 (오탐률 {changed / controls:.0%})")
    for line in result["examples"]:
        print(f"  ✗ {line}")


if __name__ == "__main__":
    main()
