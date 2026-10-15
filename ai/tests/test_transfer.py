from app.transfer import TransferSlots, ask_for, merge


def _slots(**kw) -> TransferSlots:
    return TransferSlots(is_transfer=True, **kw)


def test_금액과_수취인이_다_있으면_되묻지_않는다():
    pending = merge({}, _slots(amount=10000, alias="엄마"))

    assert pending == {"amount": 10000, "alias": "엄마"}
    assert ask_for(pending) is None


def test_금액이_빠지면_금액을_묻는다():
    assert ask_for(merge({}, _slots(alias="엄마"))) == "얼마를 보낼까요?"


def test_수취인이_빠지면_수취인을_묻는다():
    assert "누구에게" in ask_for(merge({}, _slots(amount=10000)))


def test_아무것도_없으면_둘_다_묻는다():
    assert "금액과 받는 분" in ask_for(merge({}, _slots()))


def test_다음_턴에_말한_값이_앞_값과_합쳐진다():
    pending = merge({}, _slots(alias="엄마"))

    assert merge(pending, _slots(amount=10000)) == {"alias": "엄마", "amount": 10000}


def test_수취인을_바꿔_말하면_새_값이_이긴다():
    before = {"alias": "엄마", "amount": 10000}

    assert merge(before, _slots(recipient_account_number="123456789012")) == {
        "recipient_account_number": "123456789012",
        "amount": 10000,
    }
    assert merge(
        {"recipient_account_number": "123456789012", "amount": 10000}, _slots(alias="엄마")
    ) == {"alias": "엄마", "amount": 10000}


def test_0_이하_금액과_빈_문자열은_없는_값으로_본다():
    assert merge({}, _slots(amount=0, alias="엄마")) == {"alias": "엄마"}
    assert merge({"amount": 5000}, _slots(amount=-1, alias="")) == {"amount": 5000}
