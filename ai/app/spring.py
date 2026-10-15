import os
from datetime import date

import httpx


class RecipientNotFound(Exception):
    pass


def _server_url() -> str:
    return os.getenv("SMART_SERVER_BASE_URL", "http://localhost:8080")


# Spring 송금 요청 생성(I-03). 사용자 JWT를 그대로 전달해 Spring이 본인 확인을 한다.
# 수취인을 못 찾으면 RecipientNotFound, 그 밖의 오류는 그대로 올려 채팅만 실패시킨다(NFR-003).
def create_transfer_request(token: str, pending: dict) -> dict:
    res = httpx.post(
        f"{_server_url()}/internal/transfer-requests",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "recipientAccountNumber": pending.get("recipient_account_number"),
            "alias": pending.get("alias"),
            "amount": pending["amount"],
        },
        timeout=5,
    )
    if res.status_code == 404:
        raise RecipientNotFound
    res.raise_for_status()
    return res.json()["data"]


# Spring 기간 내 송금 합계·내역 조회(I-04). 합계는 Spring이 계산하므로 받은 값을 그대로 돌려준다.
def get_transfer_history(token: str, start: date, end: date, size: int = 5) -> dict:
    res = httpx.get(
        f"{_server_url()}/internal/transfers",
        headers={"Authorization": f"Bearer {token}"},
        params={"from": start.isoformat(), "to": end.isoformat(), "size": size},
        timeout=5,
    )
    res.raise_for_status()
    return res.json()["data"]
