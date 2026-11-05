"""prices.py — 오피넷 전국 평균 단가 캐시. 네트워크 없이 fetch·urlopen 을 주입해서 본다."""

import io
import json
import sqlite3
import urllib.error

import pytest

from chagok import prices as P

KEY = "test-opinet-key-ABCDEF0123"

# 2026-11-05 실제 응답과 같은 모양(PRICE 는 문자열, TRADE_DT 는 YYYYMMDD)
OK = {"RESULT": {"OIL": [
    {"TRADE_DT": "20261105", "PRODCD": "B034", "PRODNM": "고급휘발유", "PRICE": "2395.13", "DIFF": "+0.31"},
    {"TRADE_DT": "20261105", "PRODCD": "B027", "PRODNM": "휘발유", "PRICE": "1855.25", "DIFF": "-0.12"},
    {"TRADE_DT": "20261105", "PRODCD": "D047", "PRODNM": "자동차용경유", "PRICE": "1841.77", "DIFF": "0.00"},
    {"TRADE_DT": "20261105", "PRODCD": "C004", "PRODNM": "실내등유", "PRICE": "1570.29", "DIFF": "0.00"},
    {"TRADE_DT": "20261105", "PRODCD": "K015", "PRODNM": "자동차용부탄", "PRICE": "1177.53", "DIFF": "0.00"},
]}}


@pytest.fixture
def con():
    c = sqlite3.connect(":memory:")
    P.ensure_schema(c)
    yield c
    c.close()


# ------------------------------------------------------------ 파싱
def test_parse_ok():
    out = P.parse_avg_all(json.dumps(OK, ensure_ascii=False))
    assert set(out) == {"B034", "B027", "D047", "C004", "K015"}
    assert out["B027"] == {"name": "휘발유", "price": 1855.25, "trade_dt": "2026-11-05"}
    assert out["D047"]["price"] == 1841.77


def test_parse_skips_bad_rows():
    data = {"RESULT": {"OIL": [
        {"PRODCD": "B027", "PRODNM": "휘발유", "PRICE": "1,855.25", "TRADE_DT": "20261105"},   # 쉼표 허용
        {"PRODCD": "D047", "PRODNM": "경유", "PRICE": "", "TRADE_DT": "20261105"},
        {"PRODCD": "C004", "PRODNM": "등유", "PRICE": "0", "TRADE_DT": "20261105"},
        {"PRODCD": "", "PRODNM": "?", "PRICE": "100", "TRADE_DT": "20261105"},
        "garbage",
    ]}}
    out = P.parse_avg_all(json.dumps(data, ensure_ascii=False))
    assert out == {"B027": {"name": "휘발유", "price": 1855.25, "trade_dt": "2026-11-05"}}


def test_parse_keeps_unusual_trade_dt():
    data = {"RESULT": {"OIL": [{"PRODCD": "B027", "PRODNM": "휘발유", "PRICE": "1800", "TRADE_DT": "2026.11.05"}]}}
    assert P.parse_avg_all(json.dumps(data))["B027"]["trade_dt"] == "2026.11.05"


@pytest.mark.parametrize("text", [
    "<html>error</html>",                          # 오류 페이지
    '{"RESULT": "인증키 오류"}',                     # 오류 응답
    '{"ERROR": {"MSG": "invalid"}}',
    "[]",
])
def test_parse_error_response_raises(text):
    with pytest.raises(P.PriceError):
        P.parse_avg_all(text)


@pytest.mark.parametrize("text", ["", '{"RESULT": {"OIL": []}}', '{"RESULT": {}}'])
def test_parse_empty_response_raises(text):
    with pytest.raises(P.PriceError):
        P.parse_avg_all(text)


def test_decode_body_cp949():
    # 오피넷은 Content-Type 이 utf-8 이라고 하지만 제품명이 CP949로 온다
    raw = json.dumps(OK, ensure_ascii=False).encode("cp949")
    assert P.parse_avg_all(P.decode_body(raw))["B027"]["name"] == "휘발유"
    assert P.decode_body("휘발유".encode("utf-8")) == "휘발유"


# ------------------------------------------------------------ fetch (urlopen 주입)
class FakeResp:
    def __init__(self, raw):
        self._b = raw

    def read(self):
        return self._b


def test_fetch_avg_all_ok(monkeypatch):
    seen = {}

    def fake_urlopen(req, timeout=None):
        seen["url"], seen["timeout"] = req.full_url, timeout
        return FakeResp(json.dumps(OK, ensure_ascii=False).encode("cp949"))

    monkeypatch.setattr(P.urllib.request, "urlopen", fake_urlopen)
    out = P.fetch_avg_all(KEY, timeout=7)
    assert out["D047"]["name"] == "자동차용경유"
    assert seen["url"].startswith(P.URL) and "out=json" in seen["url"] and seen["timeout"] == 7


@pytest.mark.parametrize("exc", [
    lambda url: urllib.error.HTTPError(url, 500, "err", {}, io.BytesIO(url.encode())),
    lambda url: urllib.error.URLError(f"fail {url}"),
    lambda url: TimeoutError(f"timed out {url}"),
])
def test_fetch_avg_all_error_message_has_no_key(monkeypatch, exc):
    def fake_urlopen(req, timeout=None):
        raise exc(req.full_url)

    monkeypatch.setattr(P.urllib.request, "urlopen", fake_urlopen)
    with pytest.raises(P.PriceError) as ei:
        P.fetch_avg_all(KEY)
    assert KEY not in str(ei.value)
    assert ei.value.__suppress_context__


def test_fetch_avg_all_bad_body_message_has_no_key(monkeypatch):
    monkeypatch.setattr(P.urllib.request, "urlopen", lambda req, timeout=None: FakeResp(f"bad key {KEY}".encode()))
    with pytest.raises(P.PriceError) as ei:
        P.fetch_avg_all(KEY)
    assert KEY not in str(ei.value)


# ------------------------------------------------------------ refresh·latest
def test_refresh_and_latest(con):
    n = P.refresh(con, lambda: P.parse_avg_all(json.dumps(OK, ensure_ascii=False)), now="2026-11-05T06:00:00")
    assert n == 5
    assert P.latest(con, "B027") == {"prodcd": "B027", "name": "휘발유", "price": 1855.25,
                                     "trade_dt": "2026-11-05", "updated_at": "2026-11-05T06:00:00"}
    assert P.latest(con, "D047")["price"] == 1841.77
    assert P.latest(con, "X999") is None


def test_latest_on_fresh_db_is_none():
    c = sqlite3.connect(":memory:")                           # 스키마 없는 새 DB
    assert P.latest(c, "B027") is None


def test_refresh_overwrites_existing(con):
    P.refresh(con, lambda: {"B027": {"name": "휘발유", "price": 1800.0, "trade_dt": "2026-11-04"}}, now="t1")
    P.refresh(con, lambda: {"B027": {"name": "휘발유", "price": 1855.25, "trade_dt": "2026-11-05"}}, now="t2")
    assert P.latest(con, "B027") == {"prodcd": "B027", "name": "휘발유", "price": 1855.25,
                                     "trade_dt": "2026-11-05", "updated_at": "t2"}
    assert con.execute("SELECT COUNT(*) FROM price").fetchone()[0] == 1


def test_refresh_failure_keeps_existing(con):
    P.refresh(con, lambda: P.parse_avg_all(json.dumps(OK, ensure_ascii=False)), now="t1")
    before = con.execute("SELECT * FROM price ORDER BY prodcd").fetchall()

    def fail():
        raise P.PriceError("오피넷 HTTP 500")

    with pytest.raises(P.PriceError):
        P.refresh(con, fail, now="t2")
    with pytest.raises(P.PriceError):
        P.refresh(con, lambda: P.parse_avg_all('{"RESULT": {"OIL": []}}'), now="t2")    # 빈 응답
    assert con.execute("SELECT * FROM price ORDER BY prodcd").fetchall() == before


def test_refresh_partial_response_keeps_missing_products(con):
    P.refresh(con, lambda: P.parse_avg_all(json.dumps(OK, ensure_ascii=False)), now="t1")
    n = P.refresh(con, lambda: {"B027": {"name": "휘발유", "price": 1860.0, "trade_dt": "2026-11-06"}}, now="t2")
    assert n == 1
    assert P.latest(con, "B027")["price"] == 1860.0
    assert P.latest(con, "D047") == {"prodcd": "D047", "name": "자동차용경유", "price": 1841.77,
                                     "trade_dt": "2026-11-05", "updated_at": "t1"}   # 지우지 않는다


def test_refresh_empty_dict_writes_nothing(con):
    P.refresh(con, lambda: {"B027": {"name": "휘발유", "price": 1800.0, "trade_dt": "2026-11-04"}}, now="t1")
    assert P.refresh(con, lambda: {}, now="t2") == 0
    assert P.latest(con, "B027")["updated_at"] == "t1"


# ------------------------------------------------------------ CLI
def test_cli_refresh_without_key(monkeypatch, tmp_path, capsys):
    monkeypatch.setenv("CHAGOK_DB", str(tmp_path / "p.sqlite3"))
    monkeypatch.setattr(P.sources, "load_env", lambda: {})
    assert P.main(["prices", "refresh"]) == 2
    assert "OPINET_API_KEY" in capsys.readouterr().out


def test_cli_refresh_failure_keeps_db_and_hides_key(monkeypatch, tmp_path, capsys):
    monkeypatch.setenv("CHAGOK_DB", str(tmp_path / "p.sqlite3"))
    monkeypatch.setattr(P.sources, "load_env", lambda: {"OPINET_API_KEY": KEY})
    monkeypatch.setattr(P.urllib.request, "urlopen",
                        lambda req, timeout=None: (_ for _ in ()).throw(urllib.error.URLError(req.full_url)))
    assert P.main(["prices", "refresh"]) == 1
    out = capsys.readouterr().out
    assert KEY not in out and "기존 단가 유지" in out


def test_cli_refresh_and_show(monkeypatch, tmp_path, capsys):
    monkeypatch.setenv("CHAGOK_DB", str(tmp_path / "p.sqlite3"))
    monkeypatch.setattr(P.sources, "load_env", lambda: {"OPINET_API_KEY": KEY})
    monkeypatch.setattr(P.urllib.request, "urlopen",
                        lambda req, timeout=None: FakeResp(json.dumps(OK, ensure_ascii=False).encode("cp949")))
    assert P.main(["prices", "refresh"]) == 0
    out = capsys.readouterr().out
    assert "1855.25" in out and "2026-11-05" in out and KEY not in out
    assert P.main(["prices", "show"]) == 0
    assert "B027 휘발유 1855.25원/L · 기준일 2026-11-05" in capsys.readouterr().out
