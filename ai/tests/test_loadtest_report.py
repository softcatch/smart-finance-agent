import json
from pathlib import Path

import pytest

from loadtest.report import (
    DAU,
    REQUESTS_PER_SESSION,
    counts,
    history_report,
    peak_share,
    step_row,
    stress_limit,
    stress_report,
    sum_p95,
)

LIMITS = {"chat_financial": 5196, "chat_transfer": 5196}


def _summary(
    failed: dict[str, int], ok: dict[str, int], p95: dict[str, float], api=100.0, dropped=0
):
    """k6 --summary-export의 모양을 흉내 낸다. Rate 지표의 passes는 실패한 요청이다."""
    metrics = {
        "http_req_duration{kind:api,phase:hold}": {"p(95)": api},
        "dropped_iterations": {"count": dropped},
    }
    for name in set(failed) | set(ok):
        metrics[f"http_req_failed{{name:{name},phase:hold}}"] = {
            "passes": failed.get(name, 0),
            "fails": ok.get(name, 0),
        }
    for name, value in p95.items():
        metrics[f"http_req_duration{{name:{name},phase:hold}}"] = {"p(95)": value}
    return {"metrics": metrics}


def test_채팅_기준값의_난수열은_k6_스크립트와_같은_상수를_쓴다():
    js = (Path(__file__).parents[2] / "loadtest/k6/scenario.js").read_text(encoding="utf-8")

    for constant in ("1664525", "1013904223", "4294967296", "20000"):
        assert constant in js  # 둘 중 하나만 바꾸면 합격 기준이 어긋난다


def test_호출_지연의_합의_p95를_구한다():
    assert sum_p95({"a": [100], "b": [200]}, ["a", "b"]) == 300
    uniform = {"a": list(range(1, 101))}
    assert 90 <= sum_p95(uniform, ["a"]) <= 100  # 균등 분포의 p95는 95 근처


def test_사업의_내용_채팅은_실패율에서_빼고_나머지로_기준을_본다():
    summary = _summary(
        failed={"chat_business": 40},
        ok={"chat_business": 60, "chat_financial": 100, "chat_transfer": 50, "login": 100},
        p95={"chat_financial": 4000, "chat_transfer": 4500},
    )

    row = step_row(20, summary, hold_seconds=60, limits=LIMITS)

    assert row["fail_rate"] == 0
    assert row["business_ok"] == "60/100"
    assert row["pass"] is True
    assert row["ok_per_sec"] == pytest.approx(
        310 / 60
    )  # 성공한 요청(사업 60 포함)을 유지 시간으로 나눈다


@pytest.mark.parametrize(
    ("change", "reason"),
    [
        ({"api": 600.0}, "일반 API p95가 500ms를 넘는다"),
        ({"p95": {"chat_financial": 6000, "chat_transfer": 4000}}, "채팅 p95가 기준을 넘는다"),
        ({"failed": {"login": 5}}, "실패율이 1%를 넘는다"),
        ({"dropped": 3}, "목표 도착률을 못 채웠다"),
    ],
)
def test_기준_하나라도_넘으면_미달이다(change, reason):
    base = {
        "failed": {},
        "ok": {"chat_financial": 100, "chat_transfer": 100, "login": 100},
        "p95": {"chat_financial": 4000, "chat_transfer": 4000},
    }
    summary = _summary(**{**base, **change})

    assert step_row(30, summary, 60, LIMITS)["pass"] is False, reason


def test_한계_TPS는_처음_기준을_못_지킨_단계_바로_앞이다():
    rows = [
        {"offered": tps, "pass": ok}
        for tps, ok in [(30, False), (10, True), (20, True), (40, True)]
    ]

    # 30에서 처음 미달 — 그 뒤에 통과한 단계(40)가 있어도 한계는 20이다
    assert stress_limit(rows) == 20
    assert stress_limit([{"offered": 5, "pass": False}]) is None


def test_피크_집중도_역산은_명세서_수식과_같다():
    assert DAU == round(14_420_000 * 0.4 * 0.05 * 0.4 * 0.3)  # 트래픽 추정 시트의 DAU
    assert peak_share(28) == pytest.approx(28 * 1800 / (34_608 * REQUESTS_PER_SESSION))


def test_내역_표는_건수와_깊이별_p95다():
    summary = {
        "metrics": {
            "http_req_duration{size:1000,depth:first}": {"p(95)": 19.4},
            "http_req_duration{size:1000,depth:p50}": {"p(95)": 17.8},
            "http_req_duration{size:100000,depth:first}": {"p(95)": 87.6},
            "http_req_duration{size:100000,depth:p50}": {"p(95)": 54.3},
            "http_req_failed": {"value": 0},
        }
    }

    table = history_report(summary).splitlines()

    assert table[0] == "| 건수 | first | p50 |"
    assert table[2] == "| 1,000 | 19ms | 18ms |"
    assert table[3] == "| 100,000 | 88ms | 54ms |"


def test_요청_이름의_실패와_성공_수를_센다():
    summary = _summary(failed={"login": 3}, ok={"login": 7}, p95={})

    assert counts(summary, "login") == (3, 7)
    assert counts(summary, "alias") == (0, 0)  # 없는 이름은 0건


def test_스트레스_보고서는_한계_TPS와_회복을_적는다(tmp_path):
    (tmp_path / "meta.json").write_text('{"hold": 60}', encoding="utf-8")
    good = {
        "failed": {},
        "ok": {"chat_financial": 100, "chat_transfer": 100, "login": 100},
        "p95": {"chat_financial": 4000, "chat_transfer": 4000},
    }
    for name, change in [
        ("step-10", {}),
        ("step-20", {}),
        ("step-40", {"api": 900.0}),
        ("recovery-10", {}),
    ]:
        summary = _summary(**{**good, **change})
        (tmp_path / f"{name}.json").write_text(json.dumps(summary), encoding="utf-8")

    report = stress_report(tmp_path)

    assert "한계 TPS(기준을 지키는 마지막 단계): 20" in report
    assert "마지막 단계 뒤 10 TPS 재실행" in report
    assert "회복했다" in report
