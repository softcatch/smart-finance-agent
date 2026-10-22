from collections import Counter

from evals.fallback import LIMIT, percentile, summarize


def test_백분위는_작은_수부터_세어_올림한_순번의_값이다():
    values = list(range(1, 31))  # 30개 중 p95는 29번째(지연 실측과 같은 방식)

    assert percentile(values, 0.95) == 29
    assert percentile(values, 0.5) == 15
    assert percentile([7], 0.95) == 7


def test_요약은_성공과_늦음과_오류를_세고_대체_호출_비율을_구한다():
    results = [(200, 3.0), (200, LIMIT + 1), (500, 5.0), (200, LIMIT)]
    calls = Counter({("gpt-5-nano", False): 6, ("claude-haiku-5-5", True): 2})

    row = summarize(results, calls)

    assert (row["in_time"], row["late"], row["errors"]) == (2, 1, 1)  # 10초 정각은 성공
    assert row["p50"] == 10.0 and row["p95"] == 11.0  # 성공 응답(3·10·11초)만으로 센다
    assert row["fallback"] == 0.25


def test_성공이_하나도_없으면_지연과_대체_비율은_비어_있다():
    row = summarize([(500, 5.0), (599, 60.0)], Counter())

    assert (row["in_time"], row["late"], row["errors"]) == (0, 0, 2)
    assert row["p50"] is None and row["p95"] is None and row["fallback"] is None
