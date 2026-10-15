import io
import xml.etree.ElementTree as ET
import zipfile

import httpx

BASE_URL = "https://opendart.fss.or.kr/api"
# 보고서 코드: 1분기·반기·3분기·사업. 매일 올해·작년 전부를 다시 받아 덮어쓴다(정정 반영).
REPORT_CODES = ("11013", "11012", "11014", "11011")
MAX_CORPS_PER_CALL = 100  # fnlttMultiAcnt가 한 번에 받는 회사 수


class DartError(Exception):
    pass


# "79,140,000,000,000" → 정수, 없음("-"·빈 값) → None
def parse_amount(text: str | None) -> int | None:
    cleaned = (text or "").replace(",", "").strip()
    if cleaned in ("", "-"):
        return None
    return int(cleaned)


# 주요계정 이름을 명세의 세 계정으로 맞춘다. 해당하지 않으면 None.
def _account(name: str) -> str | None:
    if "매출액" in name:
        return "매출액"
    if name.startswith("영업이익"):
        return "영업이익"
    if name.startswith("당기순이익"):
        return "당기순이익"
    return None


# 같은 계정이 한 재무제표에 두 번 오기도 한다(실제 응답: 당기순이익(손실)이 ord 29·61). 먼저 온 행만 둔다.
# 값의 의미: 1분기·반기·3분기는 amount가 해당 분기 단독, add_amount가 누적, prev_amount가 전년 동기다.
# 사업보고서는 amount가 연간이고 add_amount는 없다.
def parse_financials(items: list[dict]) -> list[dict]:
    rows: dict[tuple, dict] = {}
    for item in items:
        account = _account(item.get("account_nm", ""))
        stock_code = (item.get("stock_code") or "").strip()
        if account is None or not stock_code:
            continue
        key = (stock_code, int(item["bsns_year"]), item["reprt_code"], item["fs_div"], account)
        rows.setdefault(
            key,
            {
                "stock_code": stock_code,
                "bsns_year": key[1],
                "reprt_code": key[2],
                "fs_div": key[3],
                "account_nm": account,
                "amount": parse_amount(item.get("thstrm_amount")),
                "add_amount": parse_amount(item.get("thstrm_add_amount")),
                "prev_amount": parse_amount(item.get("frmtrm_amount")),
                "rcept_no": item.get("rcept_no"),
            },
        )
    return list(rows.values())


# 다중회사 주요계정. 오류는 HTTP 200으로 오고 status 필드에 담긴다(013은 아직 공시 전이라 빈 목록).
def fetch_financials(api_key: str, corp_codes: list[str], year: int, reprt_code: str) -> list[dict]:
    res = httpx.get(
        f"{BASE_URL}/fnlttMultiAcnt.json",
        params={
            "crtfc_key": api_key,
            "corp_code": ",".join(corp_codes),
            "bsns_year": str(year),
            "reprt_code": reprt_code,
        },
        timeout=30,
    )
    res.raise_for_status()
    body = res.json()
    status = body.get("status")
    if status == "013":
        return []
    if status != "000":
        raise DartError(f"{status} {body.get('message')}")
    return parse_financials(body.get("list", []))


# 종목코드 → (기업코드, 이름). 상장사만. 오류일 때는 ZIP이 아니라 XML이 온다.
def fetch_corp_codes(api_key: str) -> dict[str, tuple[str, str]]:
    res = httpx.get(f"{BASE_URL}/corpCode.xml", params={"crtfc_key": api_key}, timeout=60)
    res.raise_for_status()
    if not res.content.startswith(b"PK"):
        root = ET.fromstring(res.content)
        raise DartError(f"{root.findtext('status')} {root.findtext('message')}")
    with zipfile.ZipFile(io.BytesIO(res.content)) as archive:
        root = ET.fromstring(archive.read(archive.namelist()[0]))
    codes = {}
    for item in root.iter("list"):
        stock_code = (item.findtext("stock_code") or "").strip()
        if stock_code:
            codes[stock_code] = (
                (item.findtext("corp_code") or "").strip(),
                (item.findtext("corp_name") or "").strip(),
            )
    return codes
