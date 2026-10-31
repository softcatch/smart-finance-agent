import re
import secrets
import unicodedata

# 안전 판정은 LLM이 아니라 파이썬 규칙으로 한다.
_CARD = re.compile(r"(?<!\d)\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}(?!\d)")
_RRN = re.compile(r"(?<!\d)\d{6}-?[1-4]\d{6}(?!\d)")
# 전화번호(NFR-055): 휴대전화(010 등)와 지역번호 유선전화. 구분은 하이픈·점·공백이다.
# 국가번호(+82)가 있으면 앞의 0을 생략할 수 있다(+82 10-1234-5678, +82 2-1234-5678).
# 앞뒤로 숫자나 하이픈이 더 붙은 긴 숫자열(계좌번호·날짜) 안쪽은 건드리지 않는다.
_PHONE = re.compile(
    r"(?<![\d-])(?:\+82[-.\s]?(?:0?1[016789]|0?[2-6]\d?)|0?1[016789]|0[2-6]\d?)"
    r"[-.\s]?\d{3,4}[-.\s]?\d{4}(?![\d-])"
)
# 이메일: 뒤에 붙은 조사(예: ...com으로)까지 삼키지 않게 ASCII 문자만 쓴다.
_EMAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+", re.ASCII)
_INJECTION = re.compile(
    r"이전\s*(지시|명령|지침)\S*\s*(을|를)?\s*(모두\s*)?(무시|잊)"
    r"|ignore\s+(all\s+)?(the\s+)?(previous|prior|above)\s+instructions"
    r"|시스템\s*프롬프트|system\s*prompt|너는\s*이제",
    re.IGNORECASE,
)


# 비밀번호·OTP·인증번호(NFR-055): 그 낱말 가까이(앞뒤 몇 글자)에 있는 4~8자리 숫자. 낱말 없는 숫자,
# 단위가 붙은 숫자(5000원·2026년·4자리)는 건드리지 않는다. 숫자만 바꾸고 낱말은 남긴다.
# 쉼표·마침표·하이픈·빗금은 숫자 사이에 낄 때(1,000·2026.10.22·2026-10-22)만 숫자의 일부로 본다 — 문장부호(1234.·1234,)는 아니다.
# 단위는 4자리 이상 숫자에 붙는 것만 두고, 숫자에 바로 붙어야 단위로 본다('1234 원래'·'482913 번호'는 단위가
# 아니다). '만·천'은 '원'이 이어질 때만 금액이다 — '1234일 때'·'739201만 입력'의 '일·만'은 어미·조사라 가린다
# (날짜·시각의 일·월·시·분은 4자리 숫자에 붙지 않는다).
_SECRET_WORD = r"비밀\s*번호|비번|PIN|OTP|인증\s*번호|보안\s*카드"
_SECRET_NUMBER = (
    r"(?<!\d)(?<!\d[,./-])\d{4,8}(?!\d|[,./-]\d)(?!원|[만천]\s*원|억|년|개|명|회|건|번|%|자리)"
)
_QUOTE = r"""["'“‘「]"""  # 숫자를 감싼 따옴표('1234'·"1234")
_SECRET_AFTER = re.compile(
    rf"((?:{_SECRET_WORD})\s*(?:번호)?\s*(?:은|는|이|가|을|를)?\s*[:=]?\s*{_QUOTE}?){_SECRET_NUMBER}",
    re.IGNORECASE,
)
_SECRET_BEFORE = re.compile(
    rf"{_SECRET_NUMBER}(?=[^\d\n]{{0,6}}?(?:{_SECRET_WORD}))", re.IGNORECASE
)


# LLM이 몰라도 되는 값은 지운다(되돌리지 않는다). 카드번호(16자리)를 먼저 가려야 주민번호 규칙이 그 앞 13자리를
# 잘못 잡고, 이메일을 전화번호보다 먼저 가려야 주소 안의 숫자가 전화번호로 잘리지 않는다.
def mask_pii(text: str) -> str:
    text = _RRN.sub("[주민번호]", _CARD.sub("[카드번호]", text))
    text = _PHONE.sub("[전화번호]", _EMAIL.sub("[이메일]", text))
    return _SECRET_BEFORE.sub("[비밀번호]", _SECRET_AFTER.sub(r"\1[비밀번호]", text))


# 코드가 만든 답변(확인 문장·내역)의 실명 자리에 대화 기록으로 남기는 표기(NFR-055). 실명은 화면에만 보인다.
NAME_MARK = "[이름]"

# 수취인 계좌번호(NFR-055): 이 서비스가 발급하는 12자리 숫자. LLM은 값을 몰라도 어느 계좌인지만 알면 되어서
# [계좌1]처럼 바꿔 보내고, 송금 요청을 만들기 직전에 코드가 되돌린다. 대응표(자리표시자 → 번호)는 대화 상태에만 둔다.
_ACCOUNT = re.compile(r"(?<!\d)\d{12}(?!\d)")
_ACCOUNT_MARK = re.compile(r"\[계좌\d+\]")
_ACCOUNT_MARK_LOOSE = re.compile(r"\[?계좌\s*(\d{1,3})\]?(?!\d)")


# 계좌번호를 [계좌N]으로 바꾼다. 같은 번호는 같은 표기를 쓰고, 새 번호는 다음 번호를 받는다. → (바꾼 글, 대응표)
def pseudonymize(text: str, table: dict[str, str]) -> tuple[str, dict[str, str]]:
    table = dict(table)
    marks = {number: mark for mark, number in table.items()}

    def mark_of(match: re.Match) -> str:
        number = match.group()
        if number not in marks:
            marks[number] = f"[계좌{len(table) + 1}]"
            table[marks[number]] = number
        return marks[number]

    return _ACCOUNT.sub(mark_of, text), table


# LLM이 자리표시자를 꾸며 돌려줘도('계좌 [계좌1]'·'계좌1') [계좌N] 하나로 맞춘다. 표기가 없으면 그대로다.
def normalize_mark(value: str | None) -> str | None:
    match = _ACCOUNT_MARK_LOOSE.search(value) if value else None
    return f"[계좌{match[1]}]" if match else value


# 대응표에 없는 [계좌N] 표기인가 — LLM이 지어낸 것이라 계좌번호로 쓸 수 없다.
def is_unknown_mark(value: str | None, table: dict[str, str]) -> bool:
    return bool(value and _ACCOUNT_MARK.fullmatch(value) and value not in table)


# 송금 요청 도구(I-03)를 부르기 직전에 수취인 계좌의 자리표시자를 원래 번호로 되돌린다. 다른 값은 그대로다.
def restore(pending: dict, table: dict[str, str]) -> dict:
    account = pending.get("recipient_account_number")
    return {**pending, "recipient_account_number": table[account]} if account in table else pending


# 판정 전에 글자 모양만 맞춘다(NFR-012): 전각·호환 문자(ｉｇｎｏｒｅ·𝐢𝐠𝐧𝐨𝐫𝐞·ⓘ)는 NFKC로 보통 문자로,
# 낱말 사이에 끼운 보이지 않는 서식 문자(제로폭 U+200B~U+200D·U+2060·U+FEFF 등, 범주 Cf)는 지운다.
# 판정에만 쓴다 — LLM과 대화 기록에는 원래 글이 간다.
def _normalize(text: str) -> str:
    return "".join(
        ch for ch in unicodedata.normalize("NFKC", text) if unicodedata.category(ch) != "Cf"
    )


def is_injection(text: str) -> bool:
    return _INJECTION.search(_normalize(text)) is not None


# 시스템 프롬프트에 심는 카나리 단어(NFR-018). 프로세스마다 새로 만들어 코드를 봐도 알 수 없다.
# 답변에 나오면 프롬프트가 샌 것이다.
CANARY = f"카나리-{secrets.token_hex(4)}"

# 투자 권유 표현 목록(NFR-017). '자기주식 매수'·'비중 확대'처럼 공시에 나오는 말은 넣지 않는다.
_ADVICE = (
    "매수하세요",
    "매수를 추천",
    "매수 추천",
    "매수하는 것이 좋",
    "매도하세요",
    "매도를 추천",
    "매도하는 것이 좋",
    "사세요",
    "파세요",
    "담으세요",
    "투자하세요",
    "투자를 추천",
    "목표주가",
    "목표 주가",
    "수익을 보장",
    "원금을 보장",
    "반드시 오릅",
    "반드시 오를",
    "무조건 오릅",
    "무조건 오를",
    "확실히 오릅",
    "확실히 오를",
)


# 간접 인젝션(NFR-023): 공시 본문에 심은 지시를 모델이 따라 쓴 흔적. 사업의 내용 답변에만 쓴다 —
# 일반 답변에는 "송금할 때 비밀번호를 입력해 주세요" 같은 정상 안내가 있을 수 있다.
# 외부 링크: URL·www·도메인 모양(이메일 주소의 도메인 포함). 날짜(2026.06.30)는 끝이 숫자라 안 걸린다.
_LINK = re.compile(
    r"(?:https?://|www\.)\S+"
    r"|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|kr|io|ly|me|info|biz|xyz|shop|app|link|site)\b",
    re.IGNORECASE | re.ASCII,
)
# 민감값 요구: 비밀번호·OTP 같은 낱말 뒤 한 문장 안에 입력·전달을 시키는 말. 낱말만 나오는 설명은 통과한다.
_SECRET_REQUEST = re.compile(
    r"(?:비밀\s*번호|비번|OTP|PIN|인증\s*번호|보안\s*카드)[^.!?\n]{0,20}?"
    r"(?:(?:입력|회신|제출|공유)\s*(?:해|하)|알려|보내|적어)",
    re.IGNORECASE,
)


# LLM이 쓴 답변이 나가기 전 판정. 걸린 판정 코드를 돌려주고, 통과면 "PASS".
# unverified: 근거에 없는 숫자 목록(unverified_numbers). 근거가 없는 답변은 대조하지 않으므로 비워 둔다.
# disclosure: 공시 본문을 근거로 쓴 답변(사업의 내용)이면 참 — 간접 인젝션 흔적도 본다.
def check_output(reply: str, unverified: list[str] | None = None, disclosure: bool = False) -> str:
    if CANARY in reply:
        return "PROMPT_LEAK"
    if any(phrase in reply for phrase in _ADVICE):
        return "ADVICE"
    if disclosure and (_LINK.search(reply) or _SECRET_REQUEST.search(reply)):
        return "INDIRECT_INJECTION"
    if unverified:
        return "NUMBER_UNVERIFIED"
    return "PASS"


# 숫자 존재 대조(NFR-016 2단계): 답변의 숫자 중 근거 텍스트에 없는 것을 돌려준다.
# 천 단위 쉼표와 소수 끝의 0, 앞자리 0은 같은 값으로 본다. 날짜(2026.06.30, 2026. 1. 1)는
# 년·월·일 정수로 쪼개 대조하고, 공시의 두 자리 연도('26)는 2026으로 본다. 단위·표기만 다른
# 같은 값(131조 ↔ 131,895,033백만원)은 걸린다 — 표기 정규화는 4단계(추후).
_NUMBER = re.compile(r"\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?")
_DATE = re.compile(r"(?:19|20)\d{2}\.\s?\d{1,2}(?:\.\s?\d{1,2})?")
_YEAR2 = re.compile(r"'(\d{2})(?!\d)")


def _canon(token: str) -> str:
    whole, _, frac = token.replace(",", "").partition(".")
    frac = frac.rstrip("0")
    return f"{int(whole)}.{frac}" if frac else str(int(whole))


def _numbers(text: str) -> list[str]:
    found = [str(int(part)) for date in _DATE.findall(text) for part in re.split(r"\.\s?", date)]
    found += [str(int(f"20{yy}")) for yy in _YEAR2.findall(text)]
    found += [_canon(token) for token in _NUMBER.findall(_DATE.sub(" ", text))]
    return found


def unverified_numbers(reply: str, evidence: str) -> list[str]:
    known = set(_numbers(evidence))
    return [n for n in dict.fromkeys(_numbers(reply)) if n not in known]


# 가드에 걸리면 LLM 답변 대신 나가는 고정 문장(판정 코드별)
INJECTION_REPLY = "요청하신 내용은 처리할 수 없습니다."
OUTPUT_REPLIES = {
    "PROMPT_LEAK": INJECTION_REPLY,
    "ADVICE": "투자 판단에 대한 조언은 드릴 수 없습니다. 공시와 재무 정보만 안내해 드립니다.",
    "INDIRECT_INJECTION": "답변에 외부 링크나 비밀번호·인증번호 요구가 있어 표시하지 않습니다. "
    "아래 출처에서 원문을 확인해 주세요.",
    "NUMBER_UNVERIFIED": "답변에 공시 본문에서 확인되지 않은 숫자가 있어 표시하지 않습니다. "
    "아래 출처에서 원문을 확인해 주세요.",
}
