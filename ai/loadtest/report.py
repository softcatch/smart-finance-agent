"""부하 테스트 결과 보고(NFR-030·032·035). k6 요약(--summary-export)을 표로 만들고 스트레스의 한계 TPS를 구한다.

표준 라이브러리만 쓴다(호스트의 python3로 돈다).
    python3 ai/loadtest/report.py history <summary.json>
    python3 ai/loadtest/report.py stress <결과 폴더>            # step-<TPS>.json들과 meta.json
    python3 ai/loadtest/report.py scenario <summary.json> [--prom http://localhost:9090 --window 5m]
"""

import argparse
import json
import re
import urllib.parse
import urllib.request
from pathlib import Path

LATENCY = Path(__file__).with_name("latency.json")
# 명세서 '트래픽 추정' 시트: DAU, 세션당 요청 수, 피크 창(초), 피크 집중도 가정
DAU, REQUESTS_PER_SESSION, PEAK_WINDOW, PEAK_SHARE = 34_608, 5.65, 1800, 0.172
API_P95_MS = 500
ERROR_RATE = 0.01


def sum_p95(samples_ms: dict[str, list[int]], kinds: list[str]) -> int:
    """호출 종류들의 지연을 하나씩 뽑아 더한 값의 p95. k6 scenario.js의 sumP95와 같은 난수열·횟수를 쓴다."""
    seed, sums = 1, []
    for _ in range(20000):
        total = 0
        for kind in kinds:
            seed = (seed * 1664525 + 1013904223) % 4294967296
            values = samples_ms[kind]
            total += values[int(seed / 4294967296 * len(values))]
        sums.append(total)
    sums.sort()
    return sums[-(-95 * len(sums) // 100) - 1]


def chat_limits(samples_ms: dict[str, list[int]]) -> dict[str, int]:
    """채팅 p95 합격 기준(ms): 가짜 지연의 p95 + 1초."""
    return {
        "chat_financial": sum_p95(samples_ms, ["extract"]) + 1000,
        "chat_business": sum_p95(samples_ms, ["extract", "embedding", "business"]) + 1000,
        "chat_transfer": sum_p95(samples_ms, ["extract"]) + 1000,
        "detail": sum_p95(samples_ms, ["report"]) + 1000,
    }


def load_limits() -> dict[str, int]:
    return chat_limits(json.loads(LATENCY.read_text(encoding="utf-8"))["samples_ms"])


def stat(summary: dict, metric: str, key: str):
    return (summary["metrics"].get(metric) or {}).get(key)


def counts(summary: dict, name: str) -> tuple[int, int]:
    """요청 이름의 (실패, 성공) 요청 수. k6 Rate 지표에서 passes는 실패한 요청이다(http_req_failed)."""
    rate = summary["metrics"].get(f"http_req_failed{{name:{name},phase:hold}}") or {}
    return rate.get("passes", 0), rate.get("fails", 0)


NAMES = ["login", "new_thread", "accounts", "transfers", "confirm", "signup", "open_account", "charge",
         "alias", "chat_financial", "chat_business", "chat_transfer", "detail"]  # fmt: skip


def step_row(offered: int, summary: dict, hold_seconds: int, limits: dict[str, int]) -> dict:
    """스트레스 한 단계의 요약. 사업의 내용 채팅은 읽기 제한 때문에 부하와 무관하게 실패해서(#66) 실패율에서 뺀다."""
    failed = {n: counts(summary, n)[0] for n in NAMES}
    total = {n: failed[n] + counts(summary, n)[1] for n in NAMES}
    others = [n for n in NAMES if n != "chat_business"]
    requests = sum(total.values())
    row = {
        "offered": offered,
        "requests_per_sec": requests / hold_seconds,
        "ok_per_sec": (requests - sum(failed.values())) / hold_seconds,
        "api_p95": stat(summary, "http_req_duration{kind:api,phase:hold}", "p(95)"),
        "fail_rate": sum(failed[n] for n in others) / max(1, sum(total[n] for n in others)),
        "business_ok": f"{total['chat_business'] - failed['chat_business']}/{total['chat_business']}",
        "dropped": int(stat(summary, "dropped_iterations", "count") or 0),
    }
    for name in ("chat_financial", "chat_transfer"):
        row[name] = stat(summary, f"http_req_duration{{name:{name},phase:hold}}", "p(95)")
    row["pass"] = (
        row["api_p95"] is not None and row["api_p95"] < API_P95_MS
        and all(row[n] is not None and row[n] < limits[n] for n in ("chat_financial", "chat_transfer"))
        and row["fail_rate"] < ERROR_RATE
        and row["dropped"] == 0
    )  # fmt: skip
    return row


def stress_limit(rows: list[dict]) -> int | None:
    """TPS를 올리다가 처음 기준을 못 지킨 단계 바로 앞 단계의 TPS. 첫 단계부터 못 지키면 None."""
    last = None
    for row in sorted(rows, key=lambda r: r["offered"]):
        if not row["pass"]:
            break
        last = row["offered"]
    return last


def peak_share(limit_tps: float) -> float:
    """명세서 B29: 한계 TPS로 버틸 수 있는 최대 피크 집중도 = 한계 TPS × 피크 창 ÷ (DAU × 세션당 요청 수)."""
    return limit_tps * PEAK_WINDOW / (DAU * REQUESTS_PER_SESSION)


def ms(value) -> str:
    return "-" if value is None else f"{value:,.0f}ms"


def tps_of(path: Path) -> int:
    """step-30.json·recovery-5.json 같은 파일 이름에서 TPS."""
    return int(re.search(r"(\d+)", path.stem).group())


def stress_report(folder: Path) -> str:
    meta = json.loads((folder / "meta.json").read_text(encoding="utf-8"))
    limits = load_limits()
    rows = []
    for path in sorted(folder.glob("step-*.json"), key=tps_of):
        summary = json.loads(path.read_text(encoding="utf-8"))
        rows.append(step_row(tps_of(path), summary, meta["hold"], limits))
    lines = [
        "| 목표 TPS | 요청/초 | 성공/초 | 일반 API p95 | 재무 채팅 p95 | 송금 채팅 p95 | 실패율(사업 제외) | 사업 채팅 성공 | 도착률 못 채움 | 기준 |",
        "|---|---|---|---|---|---|---|---|---|---|",
    ]
    for r in rows:
        lines.append(
            f"| {r['offered']} | {r['requests_per_sec']:.1f} | {r['ok_per_sec']:.1f} | {ms(r['api_p95'])} | "
            f"{ms(r['chat_financial'])} | {ms(r['chat_transfer'])} | {r['fail_rate']:.1%} | {r['business_ok']} | "
            f"{r['dropped']} | {'통과' if r['pass'] else '미달'} |"
        )
    limit = stress_limit(rows)
    best = max(rows, key=lambda r: r["ok_per_sec"])
    lines += [
        "",
        f"기준: 일반 API p95 < {API_P95_MS}ms, 재무·송금 채팅 p95 < {limits['chat_financial']:,}ms, 실패율(사업의 내용 채팅 제외) < {ERROR_RATE:.0%}, 도착률을 채움.",
        f"한계 TPS(기준을 지키는 마지막 단계): {limit if limit is not None else '첫 단계부터 미달'}",
        f"성공 처리량 최대: {best['ok_per_sec']:.1f}/초 (목표 {best['offered']} TPS 단계)",
    ]
    if limit is not None:
        lines.append(
            f"최대 피크 집중도 역산: {peak_share(limit):.1%} (가정 {PEAK_SHARE:.1%}보다 {'크다 — 1대로 버팀' if peak_share(limit) > PEAK_SHARE else '작다 — 1대로 못 버팀'})"
        )
    for path in folder.glob("recovery-*.json"):  # 마지막 단계 뒤에 낮은 부하로 다시 돌린 결과
        row = step_row(
            tps_of(path), json.loads(path.read_text(encoding="utf-8")), meta["hold"], limits
        )
        lines.append(
            f"마지막 단계 뒤 {row['offered']} TPS 재실행: 일반 API p95 {ms(row['api_p95'])}, 실패율 {row['fail_rate']:.1%} — "
            + ("기준 통과, 회복했다" if row["pass"] else "기준 미달, 회복하지 못했다")
        )
    return "\n".join(lines)


def history_report(summary: dict) -> str:
    cells: dict[tuple[str, str], float] = {}
    for name, values in summary["metrics"].items():
        if m := re.fullmatch(r"http_req_duration\{size:(\d+),depth:(\w+)\}", name):
            cells[(m.group(1), m.group(2))] = values["p(95)"]
    depths = list(dict.fromkeys(d for _, d in cells))
    lines = ["| 건수 | " + " | ".join(depths) + " |", "|---|" + "---|" * len(depths)]
    for size in dict.fromkeys(s for s, _ in cells):
        lines.append(
            f"| {int(size):,} | " + " | ".join(ms(cells.get((size, d))) for d in depths) + " |"
        )
    return "\n".join(lines)


def prom_query(base: str, expr: str) -> dict[str, float]:
    url = f"{base}/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    result = json.load(urllib.request.urlopen(url, timeout=10))["data"]["result"]
    return {
        ",".join(f"{k}={v}" for k, v in r["metric"].items()): float(r["value"][1]) for r in result
    }


def scenario_report(summary: dict, prom: str | None, window: str) -> str:
    lines = ["| 요청 | 건수 | 실패 | p50 | p95 |", "|---|---|---|---|---|"]
    for name in NAMES:
        failed, ok = counts(summary, name)
        lat = f"http_req_duration{{name:{name},phase:hold}}"
        if failed + ok:
            lines.append(
                f"| {name} | {failed + ok} | {failed} | {ms(stat(summary, lat, 'med'))} | {ms(stat(summary, lat, 'p(95)'))} |"
            )
    if prom:  # OpenAI 오류는 우리 에러(요청 실패)와 따로 센다
        errors = prom_query(prom, f"sum by (type) (increase(openai_errors_total[{window}]))")
        calls = prom_query(prom, f"sum(increase(llm_call_seconds_count[{window}]))")
        lines += [
            "",
            f"OpenAI 오류(종류별, {window}): {errors or '없음'}",
            f"LLM 호출 수({window}): {calls or '-'}",
        ]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=["history", "stress", "scenario"])
    parser.add_argument("path")
    parser.add_argument("--prom")
    parser.add_argument("--window", default="5m")
    args = parser.parse_args()
    if args.kind == "stress":
        print(stress_report(Path(args.path)))
        return
    summary = json.loads(Path(args.path).read_text(encoding="utf-8"))
    print(
        history_report(summary)
        if args.kind == "history"
        else scenario_report(summary, args.prom, args.window)
    )


if __name__ == "__main__":
    main()
