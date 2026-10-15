import httpx
import pytest

from app.spring import RecipientNotFound, create_transfer_request

CARD = {
    "requestId": "tr_7f3a",
    "recipientName": "김영희",
    "recipientAccountNumber": "123456789012",
    "fromAccountNumber": "987654321098",
    "amount": 10000,
    "expiresAt": "2026-09-27T10:05:00+09:00",
}


# httpx.post 자리에 끼워 넣는 가짜: 받은 호출을 calls에 쌓고 정해 둔 응답을 돌려준다
def _fake_post(monkeypatch, calls: list, status: int = 201, body: dict | None = None):
    def post(url, **kwargs):
        calls.append((url, kwargs))
        payload = body if body is not None else {"success": True, "data": CARD, "error": None}
        return httpx.Response(status, json=payload, request=httpx.Request("POST", url))

    monkeypatch.setattr(httpx, "post", post)


def test_JWT와_송금_값을_Spring에_전달하고_확인_카드를_돌려준다(monkeypatch):
    monkeypatch.setenv("SMART_SERVER_BASE_URL", "http://server:8080")
    calls: list = []
    _fake_post(monkeypatch, calls)

    card = create_transfer_request("jwt-1", {"amount": 10000, "alias": "엄마"})

    url, kwargs = calls[0]
    assert url == "http://server:8080/internal/transfer-requests"
    assert kwargs["headers"] == {"Authorization": "Bearer jwt-1"}
    assert kwargs["json"] == {"recipientAccountNumber": None, "alias": "엄마", "amount": 10000}
    assert card == CARD


def test_계좌번호로_보내면_alias는_비운다(monkeypatch):
    calls: list = []
    _fake_post(monkeypatch, calls)

    create_transfer_request("jwt-1", {"amount": 5000, "recipient_account_number": "123456789012"})

    assert calls[0][1]["json"] == {
        "recipientAccountNumber": "123456789012",
        "alias": None,
        "amount": 5000,
    }


def test_수취인을_못_찾으면_RecipientNotFound(monkeypatch):
    error = {"success": False, "data": None, "error": {"code": "ALIAS_NOT_FOUND", "message": ""}}
    _fake_post(monkeypatch, [], status=404, body=error)

    with pytest.raises(RecipientNotFound):
        create_transfer_request("jwt-1", {"amount": 10000, "alias": "없는사람"})


def test_Spring_오류는_그대로_올린다(monkeypatch):
    _fake_post(monkeypatch, [], status=500, body={"success": False})

    with pytest.raises(httpx.HTTPStatusError):
        create_transfer_request("jwt-1", {"amount": 10000, "alias": "엄마"})
