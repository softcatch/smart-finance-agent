from evals.transfer_utterances import matches

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
