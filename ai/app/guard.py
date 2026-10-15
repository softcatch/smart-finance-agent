import re

# 안전 판정은 LLM이 아니라 파이썬 규칙으로 한다.
_CARD = re.compile(r"(?<!\d)\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}(?!\d)")
_RRN = re.compile(r"(?<!\d)\d{6}-?[1-4]\d{6}(?!\d)")
_INJECTION = re.compile(
    r"이전\s*(지시|명령|지침)\S*\s*(을|를)?\s*(모두\s*)?(무시|잊)"
    r"|ignore\s+(all\s+)?(the\s+)?(previous|prior|above)\s+instructions"
    r"|시스템\s*프롬프트|system\s*prompt|너는\s*이제",
    re.IGNORECASE,
)


# 카드번호(16자리)를 먼저 가려야 주민번호 규칙이 그 앞 13자리를 잘못 잡지 않는다.
def mask_pii(text: str) -> str:
    return _RRN.sub("[주민번호]", _CARD.sub("[카드번호]", text))


def is_injection(text: str) -> bool:
    return _INJECTION.search(text) is not None
