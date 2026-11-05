"""실서비스 모드 단가 — 오피넷 전국 평균 판매가격 캐시(SQLite).

PRD v1.3 7-3: 내연 단가 기본값은 오피넷 전국 평균을 하루 1회 받아 서버에 캐시한다.
키는 .env(OPINET_API_KEY)에만 둔다. 화면·로그·DB에 키를 남기지 않는다.
앱에 단가를 넘기는 엔드포인트·응답 필드는 아직 없다(계약 변경이라 별도 PR).

    python -m chagok.prices refresh     # 오피넷 avgAllPrice 1회 호출 → price 테이블 갱신 (server/ 에서)
    python -m chagok.prices show        # 저장된 단가·기준일

제품 코드: B027 휘발유, D047 자동차용경유, B034 고급휘발유, C004 실내등유, K015 자동차용부탄.
"""

from __future__ import annotations

import json
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable

from . import sources

URL = "https://www.opinet.co.kr/api/avgAllPrice.do"
GASOLINE = "B027"
DIESEL = "D047"

SCHEMA = """
CREATE TABLE IF NOT EXISTS price (
  prodcd TEXT PRIMARY KEY,      -- 오피넷 제품 코드(B027 등)
  name TEXT,
  price REAL,                   -- 원/L, 오피넷 값 그대로(소수 둘째 자리)
  trade_dt TEXT,                -- 기준일 YYYY-MM-DD
  updated_at TEXT               -- 서버가 받아 저장한 시각(로컬)
);
"""


class PriceError(RuntimeError):
    pass


def ensure_schema(con: sqlite3.Connection) -> None:
    con.executescript(SCHEMA)


# ------------------------------------------------------------ 오피넷
def _trade_date(raw: str) -> str:
    """'20261105' → '2026-11-05'. 모양이 다르면 그대로 둔다."""
    s = str(raw or "").strip()
    if len(s) == 8 and s.isdigit():
        return f"{s[:4]}-{s[4:6]}-{s[6:]}"
    return s


def decode_body(raw: bytes) -> str:
    """오피넷은 Content-Type 이 utf-8 이라고 하지만 제품명이 CP949(EUC-KR)로 온다. UTF-8 먼저, 안 되면 CP949."""
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("cp949", "replace")


def parse_avg_all(text: str) -> dict[str, dict]:
    """avgAllPrice 응답 → {prodcd: {"name", "price", "trade_dt"}}.

    응답 모양: {"RESULT": {"OIL": [{"TRADE_DT": "20261105", "PRODCD": "B027", "PRODNM": "휘발유",
                                     "PRICE": "1855.25", "DIFF": "..."}]}}
    JSON이 아니거나 OIL 이 없거나 비면 PriceError. 단가가 숫자가 아니거나 0 이하인 행은 버린다.
    """
    try:
        data = json.loads(text)
    except (json.JSONDecodeError, TypeError):
        raise PriceError(f"오피넷 응답이 JSON 아님: {str(text)[:80]!r}") from None
    result = data.get("RESULT") if isinstance(data, dict) else None
    oil = result.get("OIL") if isinstance(result, dict) else None
    if not isinstance(oil, list):
        raise PriceError("오피넷 응답에 RESULT.OIL 없음")
    out: dict[str, dict] = {}
    for it in oil:
        if not isinstance(it, dict):
            continue
        code = str(it.get("PRODCD") or "").strip()
        try:
            price = float(str(it.get("PRICE")).replace(",", ""))
        except (TypeError, ValueError):
            continue
        if not code or price <= 0:
            continue
        out[code] = {"name": str(it.get("PRODNM") or "").strip(), "price": price,
                     "trade_dt": _trade_date(it.get("TRADE_DT"))}
    if not out:
        raise PriceError("오피넷 응답에 쓸 수 있는 단가 없음")
    return out


def fetch_avg_all(key: str, timeout: float = 10) -> dict[str, dict]:
    """전국 평균 판매가격 1회 조회. 오류 메시지에서 키는 지운다."""
    url = f"{URL}?{urllib.parse.urlencode({'code': key, 'out': 'json'})}"   # 키가 URL에 들어간다 → URL은 어디에도 찍지 않는다
    try:
        raw = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Chagok"}),
                                     timeout=timeout).read()
    except urllib.error.HTTPError as e:
        raise PriceError(f"오피넷 HTTP {e.code}") from None          # 본문·URL은 싣지 않는다
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        raise PriceError(_mask(f"오피넷 연결 실패 {type(e).__name__}: {e}", key)) from None
    try:
        return parse_avg_all(decode_body(raw))
    except PriceError as e:
        raise PriceError(_mask(str(e), key)) from None             # 오류 본문 앞부분이 키를 되돌려 줄 수 있다


def _mask(text: str, key: str) -> str:
    return text.replace(key, "<KEY>") if key else text


# ------------------------------------------------------------ 저장
def refresh(con: sqlite3.Connection, fetch: Callable[[], dict[str, dict]], now: str | None = None) -> int:
    """fetch() 결과로 price 행을 덮어쓴다(UPSERT). 저장한 행 수를 돌려준다.

    실패하면(fetch 예외) DB를 건드리지 않고 예외를 그대로 올린다 — 기존 단가가 남는다.
    응답에 없는 제품 행도 지우지 않는다.
    """
    ensure_schema(con)
    rows = fetch()                      # 여기서 실패하면 아래로 내려가지 않는다
    if not rows:
        return 0
    now = now or time.strftime("%Y-%m-%dT%H:%M:%S")
    with con:
        con.executemany(
            "INSERT INTO price (prodcd, name, price, trade_dt, updated_at) VALUES (?,?,?,?,?) "
            "ON CONFLICT(prodcd) DO UPDATE SET name=excluded.name, price=excluded.price, "
            "trade_dt=excluded.trade_dt, updated_at=excluded.updated_at",
            [(code, r.get("name", ""), r["price"], r.get("trade_dt", ""), now) for code, r in rows.items()])
    return len(rows)


def latest(con: sqlite3.Connection, prodcd: str) -> dict | None:
    """저장된 단가 1건. 없으면 None."""
    ensure_schema(con)
    row = con.execute("SELECT prodcd, name, price, trade_dt, updated_at FROM price WHERE prodcd = ?",
                      (prodcd,)).fetchone()
    if row is None:
        return None
    return {"prodcd": row[0], "name": row[1], "price": row[2], "trade_dt": row[3], "updated_at": row[4]}


# ------------------------------------------------------------ CLI
def _show(con: sqlite3.Connection) -> None:
    ensure_schema(con)
    rows = con.execute("SELECT prodcd, name, price, trade_dt FROM price ORDER BY prodcd").fetchall()
    if not rows:
        print("저장된 단가 없음")
        return
    for code, name, price, trade_dt in rows:
        print(f"  {code} {name} {price}원/L · 기준일 {trade_dt}")


def main(argv: list[str]) -> int:
    cmd = argv[1] if len(argv) > 1 else "show"
    if cmd not in ("refresh", "show"):
        print("사용법: python -m chagok.prices refresh|show")
        return 2
    con = sources.connect()
    try:
        ensure_schema(con)
        if cmd == "refresh":
            key = sources.load_env().get("OPINET_API_KEY", "")
            if not key:
                print("OPINET_API_KEY 가 .env 에 없다 (.env.example 참고)")
                return 2
            try:
                n = refresh(con, lambda: fetch_avg_all(key))
            except PriceError as e:
                print(f"오피넷 갱신 실패(기존 단가 유지): {e}")
                return 1
            print(f"오피넷 단가 {n}종 갱신")
        _show(con)
        return 0
    finally:
        con.close()


if __name__ == "__main__":
    sys.exit(main(sys.argv))
