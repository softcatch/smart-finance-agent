"""사업의 내용 검색 품질 평가(NFR-039). pytest가 아니라 실제 OpenAI(임베딩)와 DB가 필요하다.

질문마다 그 회사의 청크 전체를 지금의 검색(search_business_chunks)이 세우는 순서로 받아, 정답 청크의 순위로
Recall@5와 MRR을 잰다. 정답 청크는 business_retrieval.jsonl의 `gold` 문자열이 하나라도 든 청크다
(다시 수집하면 청크 번호가 바뀌어서 본문 문자열로 둔다). `gold`가 빈 질문은 정답이 본문에 없어 지표에서 뺀다.
- Recall@5: 정답 청크가 하나라도 상위 5개에 든 질문의 비율 (답변에는 정답이 든 청크 하나면 된다)
- MRR: 첫 정답 청크 순위의 역수의 평균 (순위는 그 회사 청크 전체에서 센다)
임베딩은 같은 질문이면 같은 벡터라 LLM 평가처럼 여러 번 돌리지 않는다.
실행(ai/에서, .env의 키·DB 접속 정보를 읽는다):
    set -a; . ../.env; set +a; POSTGRES_HOST=127.0.0.1 uv run python -m evals.business_retrieval
"""

import json
from pathlib import Path

from app.business import embed_texts
from app.main import _connect
from app.store import find_companies, search_business_chunks

CASES = Path(__file__).with_name("business_retrieval.jsonl")
K = 5  # 답변에 쓰는 청크 수(search_business_chunks의 기본 limit)와 같다


def gold_ranks(contents: list[str], gold: list[str]) -> list[int]:
    """순서대로 놓인 청크 본문에서 정답 청크의 순위(1부터)."""
    return [rank for rank, text in enumerate(contents, 1) if any(g in text for g in gold)]


def recall_at_k(first_ranks: list[int], k: int = K) -> float:
    return sum(rank <= k for rank in first_ranks) / len(first_ranks)


def mrr(first_ranks: list[int]) -> float:
    return sum(1 / rank for rank in first_ranks) / len(first_ranks)


def main() -> None:
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines()]
    first_ranks, skipped = [], []
    with _connect() as conn:
        for case in cases:
            if not case["gold"]:
                skipped.append(case["question"])
                continue
            stock = find_companies(conn, [case["company"]])[0][0][0]
            vector = embed_texts([case["question"]])[0]
            chunks = search_business_chunks(conn, stock, vector, limit=100_000)  # 회사의 청크 전부
            ranks = gold_ranks([c["content"] for c in chunks], case["gold"])
            if not ranks:
                raise SystemExit(
                    f"라벨이 코퍼스와 맞지 않는다(일치하는 청크 0개): {case['question']!r}"
                )
            first_ranks.append(ranks[0])
            print(
                f"{'✓' if ranks[0] <= K else '✗'} 첫 정답 {ranks[0]:>3}위 (정답 청크 {len(ranks)}개 중 "
                f"순위 {ranks[:4]}, 전체 {len(chunks)}개) · {case['question']} · 1위 [{chunks[0]['section']}]"
            )
    n = len(first_ranks)
    print(f"정답이 본문에 없어 뺀 질문 {len(skipped)}개: {skipped}")
    print(
        f"질문 {n}개: Recall@{K} = {sum(r <= K for r in first_ranks)}/{n} = "
        f"{recall_at_k(first_ranks):.0%}, MRR = {mrr(first_ranks):.3f}"
    )


if __name__ == "__main__":
    main()
