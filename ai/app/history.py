from datetime import date, datetime, timedelta, timezone
from typing import Literal

from app.guard import NAME_MARK

Period = Literal["today", "this_month", "last_month", "last_7_days"]

# 한국은 서머타임이 없어 고정 오프셋이면 된다(tz 데이터베이스가 필요 없다).
KST = timezone(timedelta(hours=9))
LABELS = {
    "today": "오늘",
    "this_month": "이번 달",
    "last_month": "지난달",
    "last_7_days": "최근 7일",
}
ASK_PERIOD = "조회할 기간을 알려 주세요. 오늘, 이번 달, 지난달, 최근 7일 중에서 고를 수 있습니다."


def today_kst() -> date:
    return datetime.now(KST).date()


# 기간 키워드를 날짜로 바꾼다 — 날짜 계산은 LLM이 아니라 코드가 한다.
def period_range(period: Period, today: date) -> tuple[date, date]:
    if period == "today":
        return today, today
    if period == "this_month":
        return today.replace(day=1), today
    if period == "last_7_days":
        return today - timedelta(days=6), today
    last_day = today.replace(day=1) - timedelta(days=1)  # last_month
    return last_day.replace(day=1), last_day


def _md(d: date) -> str:
    return f"{d.month}/{d.day}"


# 숫자는 Spring 응답 값을 그대로 쓴다(NFR-002). 문장은 코드 템플릿이라 LLM 호출이 없다.
# hide_names: 상대방 실명을 NAME_MARK로 바꾼다 — 화면에는 실명을 보이고 대화 기록에는 이쪽을 남긴다(NFR-055).
def history_reply(
    period: Period, start: date, end: date, summary: dict, hide_names: bool = False
) -> str:
    span = _md(start) if start == end else f"{_md(start)}~{_md(end)}"
    head = f"{LABELS[period]}({span})"
    if summary["count"] == 0:
        return f"{head}에는 송금 내역이 없습니다."
    sent, received = f"{summary['sentTotal']:,}원", f"{summary['receivedTotal']:,}원"
    lines = [
        f"{head} 보낸 금액은 {sent}, 받은 금액은 {received}이고, 내역은 모두 {summary['count']}건입니다.",
        "최근 내역",
    ]
    for item in summary["items"]:
        day = datetime.fromisoformat(item["createdAt"]).astimezone(KST).date()
        who = NAME_MARK if hide_names else item["counterpartName"]
        amount = f"{item['amount']:,}원"
        if item["direction"] == "SENT":
            lines.append(f"- {_md(day)} {who}에게 {amount} 보냄")
        else:
            lines.append(f"- {_md(day)} {who}에게서 {amount} 받음")
    rest = summary["count"] - len(summary["items"])
    if rest > 0:
        lines.append(f"그 밖에 {rest}건이 더 있습니다.")
    return "\n".join(lines)
