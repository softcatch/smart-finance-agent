import pytest

from evals.business_retrieval import gold_ranks, mrr, recall_at_k


def test_정답_청크의_순위는_정답_문자열이_든_청크의_위치다():
    contents = ["가나다", "수주잔고 114,918,238", "라마바", "31,723,101 합계"]

    assert gold_ranks(contents, ["114,918,238", "31,723,101"]) == [2, 4]
    assert gold_ranks(contents, ["없는 문자열"]) == []


def test_Recall과_MRR은_첫_정답_순위로_구한다():
    first_ranks = [1, 5, 6, 2]

    assert recall_at_k(first_ranks) == 0.75  # 1·5·2위는 상위 5개 안, 6위는 밖
    assert mrr(first_ranks) == pytest.approx((1 + 1 / 5 + 1 / 6 + 1 / 2) / 4)
