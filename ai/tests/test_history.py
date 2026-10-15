from datetime import date

from app.history import ASK_PERIOD, history_reply, period_range

# Spring I-04의 실제 응답 모양(createdAt은 UTC ISO 문자열)
SUMMARY = {
    "sentTotal": 120000,
    "receivedTotal": 50000,
    "count": 3,
    "items": [
        {
            "transferId": 3,
            "direction": "RECEIVED",
            "counterpartName": "김영희",
            "counterpartAccountNumber": "545202533867",
            "amount": 50000,
            "createdAt": "2026-10-14T15:30:00.750040Z",  # KST로는 10/15 00:30
        },
        {
            "transferId": 2,
            "direction": "SENT",
            "counterpartName": "이철수",
            "counterpartAccountNumber": "123456789012",
            "amount": 70000,
            "createdAt": "2026-10-03T06:44:34.678254Z",
        },
    ],
    "nextCursor": 2,
    "hasNext": True,
}


def test_오늘은_하루():
    assert period_range("today", date(2026, 10, 15)) == (date(2026, 10, 15), date(2026, 10, 15))


def test_이번_달은_1일부터_오늘까지():
    assert period_range("this_month", date(2026, 10, 15)) == (date(2026, 10, 1), date(2026, 10, 15))


def test_지난달은_전월_1일부터_말일까지():
    assert period_range("last_month", date(2026, 10, 15)) == (date(2026, 9, 1), date(2026, 9, 30))


def test_지난달_연초는_작년_12월():
    assert period_range("last_month", date(2026, 1, 10)) == (date(2025, 12, 1), date(2025, 12, 31))


def test_지난달_말일이_짧은_달():
    assert period_range("last_month", date(2026, 3, 31)) == (date(2026, 2, 1), date(2026, 2, 28))


def test_최근_7일은_오늘_포함():
    assert period_range("last_7_days", date(2026, 10, 15)) == (
        date(2026, 10, 9),
        date(2026, 10, 15),
    )
    assert period_range("last_7_days", date(2026, 3, 3)) == (date(2026, 2, 25), date(2026, 3, 3))


def test_답변의_숫자는_Spring_응답_값이고_시각은_한국_시간():
    reply = history_reply("this_month", date(2026, 10, 1), date(2026, 10, 15), SUMMARY)

    assert reply.splitlines() == [
        "이번 달(10/1~10/15) 보낸 금액은 120,000원, 받은 금액은 50,000원이고, 내역은 모두 3건입니다.",
        "최근 내역",
        "- 10/15 김영희에게서 50,000원 받음",
        "- 10/3 이철수에게 70,000원 보냄",
        "그 밖에 1건이 더 있습니다.",
    ]


def test_하루_조회는_날짜를_한_번만_쓴다():
    reply = history_reply("today", date(2026, 10, 15), date(2026, 10, 15), SUMMARY)

    assert reply.startswith("오늘(10/15) 보낸 금액은")


def test_내역이_없으면_없다고_답한다():
    empty = {"sentTotal": 0, "receivedTotal": 0, "count": 0, "items": [], "hasNext": False}

    reply = history_reply("last_month", date(2026, 9, 1), date(2026, 9, 30), empty)

    assert reply == "지난달(9/1~9/30)에는 송금 내역이 없습니다."


def test_기간을_안내하는_문장():
    assert "이번 달" in ASK_PERIOD
