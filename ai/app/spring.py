import os

import httpx


class RecipientNotFound(Exception):
    pass


# Spring 송금 요청 생성(I-03). 사용자 JWT를 그대로 전달해 Spring이 본인 확인을 한다.
# 수취인을 못 찾으면 RecipientNotFound, 그 밖의 오류는 그대로 올려 채팅만 실패시킨다(NFR-003).
def create_transfer_request(token: str, pending: dict) -> dict:
    base_url = os.getenv("SMART_SERVER_BASE_URL", "http://localhost:8080")
    res = httpx.post(
        f"{base_url}/internal/transfer-requests",
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
