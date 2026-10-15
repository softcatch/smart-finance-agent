import io
import re
import xml.etree.ElementTree as ET
import zipfile
from datetime import date, timedelta

import httpx

BASE_URL = "https://opendart.fss.or.kr/api"
# 보고서 코드: 1분기·반기·3분기·사업. 매일 올해·작년 전부를 다시 받아 덮어쓴다(정정 반영).
REPORT_CODES = ("11013", "11012", "11014", "11011")
MAX_CORPS_PER_CALL = 100  # fnlttMultiAcnt가 한 번에 받는 회사 수
# 정기보고서 이름: "분기보고서 (2026.03)", "[기재정정]반기보고서 (2026.06)". 괄호 안이 보고 기간이다.
REPORT_NAME = re.compile(r"(?:사업|반기|분기)보고서\s*\((\d{4})\.(\d{2})\)")


class DartError(Exception):
    pass


# 응답 상태. 000이면 True, 013(데이터 없음·공시 전)이면 False, 그 밖은 DartError.
def _has_data(body: dict) -> bool:
    status = body.get("status")
    if status == "013":
        return False
    if status != "000":
        raise DartError(f"{status} {body.get('message')}")
    return True


# ZIP이어야 할 응답이 오류일 때는 ZIP이 아니라 XML이 온다(corpCode.xml·document.xml).
def _open_zip(content: bytes) -> zipfile.ZipFile:
    if not content.startswith(b"PK"):
        root = ET.fromstring(content)
        raise DartError(f"{root.findtext('status')} {root.findtext('message')}")
    return zipfile.ZipFile(io.BytesIO(content))


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
    if not _has_data(body):
        return []
    return parse_financials(body.get("list", []))


# 종목코드 → (기업코드, 이름). 상장사만.
def fetch_corp_codes(api_key: str) -> dict[str, tuple[str, str]]:
    res = httpx.get(f"{BASE_URL}/corpCode.xml", params={"crtfc_key": api_key}, timeout=60)
    res.raise_for_status()
    with _open_zip(res.content) as archive:
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


# 공시검색으로 회사의 최신 정기보고서(사업·반기·분기, 정정 포함)를 찾는다. 없으면 None.
# 최신은 (보고 기간, 접수번호) 순이다 — 지난 기간의 정정본이 늦게 나와도 최신 기간을 밀어내지 않는다.
# 400일이면 최신 사업·분기보고서가 반드시 들어온다(정기보고서 간격은 길어야 석 달).
def fetch_latest_report(api_key: str, corp_code: str, today: date) -> dict | None:
    res = httpx.get(
        f"{BASE_URL}/list.json",
        params={
            "crtfc_key": api_key,
            "corp_code": corp_code,
            "pblntf_ty": "A",  # 정기공시
            "bgn_de": (today - timedelta(days=400)).strftime("%Y%m%d"),
            "end_de": today.strftime("%Y%m%d"),
            "page_count": "100",
        },
        timeout=30,
    )
    res.raise_for_status()
    body = res.json()
    if not _has_data(body):
        return None
    found = [
        (m[1] + m[2], item["rcept_no"], item["report_nm"])
        for item in body.get("list", [])
        if (m := REPORT_NAME.search(item["report_nm"]))
    ]
    if not found:
        return None
    _, rcept_no, report_nm = max(found)
    return {"rcept_no": rcept_no, "report_nm": report_nm}


# 공시서류원본파일: ZIP 안의 {접수번호}.xml이 본문이다(나머지는 감사보고서 같은 첨부).
def fetch_document(api_key: str, rcept_no: str) -> bytes:
    res = httpx.get(
        f"{BASE_URL}/document.xml",
        params={"crtfc_key": api_key, "rcept_no": rcept_no},
        timeout=120,
    )
    res.raise_for_status()
    with _open_zip(res.content) as archive:
        return archive.read(f"{rcept_no}.xml")
