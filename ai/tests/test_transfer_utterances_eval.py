from app.transfer import TransferSlots
from evals.transfer_utterances import failure_kind, matches

ACCOUNT = {"turns": ["123456789012로 5000원 보내줘"], "amount": 5000, "account": "123456789012"}
ALIAS = {"turns": ["엄마한테 3만원 보내줘"], "amount": 30000, "alias": "엄마"}


def test_금액과_수취인이_모두_같아야_맞다():
    assert matches(ACCOUNT, {"amount": 5000, "recipient_account_number": "123456789012"})
    assert matches(ALIAS, {"amount": 30000, "alias": "엄마"})
    assert not matches(ACCOUNT, {"amount": 5000, "recipient_account_number": "987654321098"})
    assert not matches(ACCOUNT, {"amount": 50000, "recipient_account_number": "123456789012"})
    assert not matches(ALIAS, {"amount": 30000, "recipient_account_number": "123456789012"})


def test_송금_요청이_안_만들어졌으면_틀린_것이다():
    assert not matches(ACCOUNT, None)


def _slots(**values) -> TransferSlots:
    return TransferSlots(is_transfer=True, **values)


def test_실패_종류를_추출기가_뽑은_값으로_나눈다():
    assert failure_kind(ALIAS, [TransferSlots(is_transfer=False)]) == "송금 의도 놓침"
    assert failure_kind(ALIAS, [_slots(amount=30000)]) == "수취인 빈 값"
    assert failure_kind(ALIAS, [_slots(amount=30000, alias="엄마한테")]) == "수취인에 조사·꾸밈"
    assert failure_kind(ALIAS, [_slots(amount=30000, alias="어머니")]) == "수취인 다른 값"
    assert failure_kind(ALIAS, [_slots(alias="엄마")]) == "금액 틀림·빠짐"
    # 여러 턴이면 마지막으로 말한 값을 본다. 계좌번호는 가명화 표기라 있는지만 본다
    assert (
        failure_kind(ALIAS, [_slots(alias="엄마"), _slots(amount=30000)])
        == "값은 맞는데 요청 안 됨"
    )
    assert failure_kind(ACCOUNT, [_slots(amount=5000, recipient_account_number="[계좌1]")]) == (
        "값은 맞는데 요청 안 됨"
    )
