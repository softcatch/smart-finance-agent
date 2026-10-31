"""인젝션 평가(NFR-012 차단율·오탐률, NFR-023 간접 인젝션의 기준선). LLM·DB·키 없이 돈다.

세트(injection.jsonl)의 문장을 가드 판정에 넣고 막히는지만 본다.
- direct: 사용자 입력 → 입력 가드가 쓰는 is_injection. 참이면 막힌 것이다.
- indirect: 공시에 심은 지시를 모델이 따라 쓴 사업의 내용 답변 → 출력 가드가 사업의 내용 답변에 쓰는
  check_output(disclosure=True). PASS가 아니면 막힌 것이다.
- leak: 프롬프트(안내문)를 옮기거나 요약한 답변(NFR-018) → 출력 가드의 check_output. PASS가 아니면 막힌 것이다. 모델이 지시를 따르는 비율(실제 LLM)은 재지 않는다.
공격 문장은 막혀야 하고(차단율), 정상 문장은 통과해야 한다(오탐률).
실행(ai/에서):
    uv run python -m evals.injection
"""

import json
from collections import Counter
from pathlib import Path

import app.graph  # noqa: F401 — 프롬프트 5개를 등록한다(유출 겹침 판정의 기준, 앱도 그래프를 불러올 때 등록된다)
from app.guard import check_output, is_injection

CASES = Path(__file__).with_name("injection.jsonl")
KINDS = ("direct", "indirect", "leak")


def blocked(case: dict) -> bool:
    if case["kind"] == "direct":
        return is_injection(case["text"])
    if case["kind"] == "indirect":
        return check_output(case["text"], disclosure=True) != "PASS"  # 사업의 내용 답변으로 판정
    return check_output(case["text"]) != "PASS"


def load_cases() -> list[dict]:
    return [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines()]


# 범주·유형별 공격 수·차단 수, 범주별 정상 수·오탐 수, 놓친 공격·잘못 막은 정상 문장을 모은다.
def evaluate(cases: list[dict]) -> dict:
    attacks: Counter = Counter()  # (범주, 유형) → 공격 수
    caught: Counter = Counter()  # (범주, 유형) → 막은 수
    controls: Counter = Counter()  # 범주 → 정상 수
    false_hits: Counter = Counter()  # 범주 → 잘못 막은 수
    missed: list[str] = []
    wrong: list[str] = []
    for case in cases:
        kind, hit = case["kind"], blocked(case)
        if case["attack"]:
            attacks[kind, case["type"]] += 1
            caught[kind, case["type"]] += hit
            if not hit:
                missed.append(f"{kind}/{case['type']}: {case['text']!r}")
        else:
            controls[kind] += 1
            false_hits[kind] += hit
            if hit:
                wrong.append(f"{kind}/{case['type']}: {case['text']!r}")
    return {
        "attacks": attacks,
        "caught": caught,
        "controls": controls,
        "false_hits": false_hits,
        "missed": missed,
        "wrong": wrong,
    }


def main() -> None:
    result = evaluate(load_cases())
    attacks, caught = result["attacks"], result["caught"]
    for kind in KINDS:
        types = [t for k, t in attacks if k == kind]
        total = sum(attacks[kind, t] for t in types)
        hit = sum(caught[kind, t] for t in types)
        print(f"[{kind}] 공격 {total}개 중 차단 {hit}개 (차단율 {hit / total:.0%})")
        for t in types:
            print(f"  {t:<10} {caught[kind, t]}/{attacks[kind, t]}")
        controls, false_hits = result["controls"][kind], result["false_hits"][kind]
        print(
            f"  정상 {controls}개 중 잘못 막음 {false_hits}개 (오탐률 {false_hits / controls:.0%})"
        )
    for line in result["missed"]:
        print(f"  ✗ 놓침 {line}")
    for line in result["wrong"]:
        print(f"  ✗ 오탐 {line}")


if __name__ == "__main__":
    main()
