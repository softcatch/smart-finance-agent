from pydantic import BaseModel, Field

from app.history import Period


# LLM이 사용자 메시지에서 뽑는 의도(송금·내역 조회·재무 비교·사업의 내용)와 값. 말하지 않은 값은 None.
class TransferSlots(BaseModel):
    is_transfer: bool = Field(description="송금 요청이거나, 진행 중인 송금 정보를 이어 말하면 true")
    is_history: bool = Field(
        default=False, description="송금 내역이나 보낸·받은 금액을 묻는 질문이면 true"
    )
    period: Period | None = Field(
        default=None, description="내역을 조회할 기간. 해당하는 값이 없으면 null"
    )
    is_financial: bool = Field(
        default=False,
        description="기업의 매출액·영업이익·순이익 같은 재무 실적을 묻거나 기업끼리 비교하면 true",
    )
    is_business: bool = Field(
        default=False,
        description="기업의 회사 소개·주요 제품·사업 구조 같은 사업의 내용을 물으면 true",
    )
    companies: list[str] = Field(
        default_factory=list,
        description="재무나 사업의 내용을 묻는 기업 이름(말한 그대로). 없으면 빈 목록",
    )
    amount: int | None = Field(default=None, description="금액(원 단위 정수). 말하지 않았으면 null")
    recipient_account_number: str | None = Field(
        default=None, description="수취인 계좌번호(숫자). 말하지 않았으면 null"
    )
    alias: str | None = Field(
        default=None, description="수취인 별칭(예: 엄마). 말하지 않았으면 null"
    )


# 진행 중인 값(pending)에 이번에 말한 값을 합친다. 새로 말한 값이 이긴다.
def merge(pending: dict, slots: TransferSlots) -> dict:
    got = {
        k: v
        for k, v in slots.model_dump(
            exclude={
                "is_transfer",
                "is_history",
                "period",
                "is_financial",
                "is_business",
                "companies",
            }
        ).items()
        if v not in (None, "")
    }
    merged = {**pending, **got}
    # 0 이하 금액은 쓸 수 없다 — 앞에서 말한 금액으로 되돌아가지 않고 다시 묻는다
    if merged.get("amount", 1) <= 0:
        del merged["amount"]
    # 수취인은 계좌번호·별칭 중 하나 — 새로 말한 쪽이 이긴다(둘 다 말하면 계좌번호)
    if "recipient_account_number" in got:
        merged.pop("alias", None)
    elif "alias" in got:
        merged.pop("recipient_account_number", None)
    return merged


# 빠진 값을 되묻는 고정 문장. 다 모였으면 None.
def ask_for(pending: dict) -> str | None:
    has_amount = "amount" in pending
    has_recipient = "recipient_account_number" in pending or "alias" in pending
    if has_amount and has_recipient:
        return None
    if not has_amount and not has_recipient:
        return "송금할 금액과 받는 분(계좌번호 또는 별칭)을 알려 주세요."
    if not has_amount:
        return "얼마를 보낼까요?"
    return "누구에게 보낼까요? 계좌번호나 별칭을 알려 주세요."
