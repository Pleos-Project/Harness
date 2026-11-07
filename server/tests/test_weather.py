"""weather.py·GET /v1/weather — 기상청 초단기실황 강수 판정(명세 v0.5 7절·12절, AC-C-6, AC-N-4).

네트워크 없이 urlopen·fetch를 주입해서 본다.
"""

import io
import json
import socket
import time
import urllib.error
import urllib.parse
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from chagok import main as M
from chagok import weather as W

KEY = "test-kma-key-ABCDEF0123"
# 로그·응답에 남으면 안 되는 좌표. 흔한 값과 겹치지 않게 자릿수를 길게 둔다
LAT, LON = 37.412734, 127.094718
NX, NY = W.to_grid(LAT, LON)
NOW = datetime(2026, 11, 7, 14, 50, tzinfo=W.KST)


def ncst(pty: str = "1", code: str = "00", base_date: str = "20261107", base_time: str = "1400") -> dict:
    """getUltraSrtNcst 응답과 같은 모양(8개 항목, obsrValue는 문자열)."""
    vals = {"PTY": pty, "REH": "88", "RN1": "1.5", "T1H": "9.4", "UUU": "-1.2", "VEC": "310", "VVV": "1.4", "WSD": "1.9"}
    items = [{"baseDate": base_date, "baseTime": base_time, "category": c, "nx": NX, "ny": NY, "obsrValue": v}
             for c, v in vals.items()]
    return {"response": {"header": {"resultCode": code, "resultMsg": "NORMAL_SERVICE" if code == "00" else "NO_DATA"},
                         "body": {"dataType": "JSON", "items": {"item": items},
                                  "pageNo": 1, "numOfRows": 10, "totalCount": len(items)}}}


# 키 오류는 dataType=JSON이어도 게이트웨이가 XML로 준다
XML_KEY_ERROR = ("<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>"
                 "<returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>"
                 "<returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>")


class FakeResp(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def body(obj) -> FakeResp:
    return FakeResp((obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)).encode("utf-8"))


# ------------------------------------------------------------ 격자 변환 (위경도 → nx, ny)
@pytest.mark.parametrize("lat, lon, nx, ny", [
    # 기상청 격자 위경도 표의 시도 대표점
    (37.5635694444444, 126.980008333333, 60, 127),    # 서울
    (35.1770194444444, 129.076952777777, 98, 76),     # 부산
    (35.8685416666666, 128.603552777777, 89, 90),     # 대구
    (37.4532333333333, 126.707352777777, 55, 124),    # 인천
    (35.1569749999999, 126.853363888888, 58, 74),     # 광주
    (36.3471194444444, 127.386566666666, 67, 100),    # 대전
    (35.5354083333333, 129.313688888888, 102, 84),    # 울산
    (33.4856944444444, 126.500333333333, 52, 38),     # 제주
])
def test_to_grid_matches_kma_table(lat, lon, nx, ny):
    assert W.to_grid(lat, lon) == (nx, ny)


def test_out_of_grid():
    assert W.in_grid(*W.to_grid(LAT, LON))
    assert not W.in_grid(*W.to_grid(0.0, 0.0))
    assert not W.in_grid(*W.to_grid(51.5, -0.12))     # 런던


# ------------------------------------------------------------ 발표 시각 (매시 40분 이후 정시분)
@pytest.mark.parametrize("now, expected", [
    (datetime(2026, 11, 7, 14, 40, tzinfo=W.KST), ("20261107", "1400")),
    (datetime(2026, 11, 7, 14, 39, 59, tzinfo=W.KST), ("20261107", "1300")),
    (datetime(2026, 11, 7, 14, 0, tzinfo=W.KST), ("20261107", "1300")),
    (datetime(2026, 11, 8, 0, 10, tzinfo=W.KST), ("20261107", "2300")),          # 자정 직후 → 전날 23시
    (datetime(2027, 1, 1, 0, 39, tzinfo=W.KST), ("20261231", "2300")),           # 해 넘김
    (datetime(2026, 11, 7, 5, 45, tzinfo=timezone.utc), ("20261107", "1400")),   # 서버 시계 UTC → KST로
    (datetime(2026, 11, 7, 15, 20, tzinfo=timezone.utc), ("20261107", "2300")),  # UTC 15:20 = KST 00:20 다음 날 → 전날 23시
])
def test_base_time_kst(now, expected):
    assert W.base_time(now) == expected


# ------------------------------------------------------------ PTY 판정
@pytest.mark.parametrize("pty, precip", [
    ("0", "none"), ("1", "rain"), ("4", "rain"), ("5", "rain"),     # 4 = 소나기 → 비(11/7 효성 결정)
    ("2", "snow"), ("3", "snow"), ("6", "snow"), ("7", "snow"),
])
def test_parse_pty(pty, precip):
    assert W.parse_ncst(json.dumps(ncst(pty))) == (precip, "2026-11-07T14:00:00+09:00")


def test_parse_single_item_dict():
    data = ncst("3")
    data["response"]["body"]["items"]["item"] = [i for i in data["response"]["body"]["items"]["item"]
                                                if i["category"] == "PTY"][0]
    assert W.parse_ncst(json.dumps(data))[0] == "snow"


@pytest.mark.parametrize("text, kind", [
    (json.dumps(ncst("8")), "pty_unexpected"),          # 판정표(0~7)에 없는 값
    (json.dumps(ncst("-1")), "pty_unexpected"),
    (json.dumps(ncst("-")), "pty_not_number"),
    (json.dumps(ncst(code="03")), "kma_03"),            # NO_DATA (발표 전 시각 등)
    (json.dumps(ncst(code="30")), "kma_30"),
    (XML_KEY_ERROR, "not_json"),
    ("", "not_json"),
    ("[]", "no_header"),
    (json.dumps({"response": {"header": {"resultCode": "00"}, "body": {"items": ""}}}), "no_pty"),
    (json.dumps({"response": {"header": {"resultCode": "00"}, "body": {"items": {"item": []}}}}), "no_pty"),
    (json.dumps(ncst(base_time="14")), "bad_base_time"),
])
def test_parse_failures(text, kind):
    with pytest.raises(W.WeatherError) as e:
        W.parse_ncst(text)
    assert e.value.kind == kind


# ------------------------------------------------------------ 기상청 호출 (urlopen 주입)
def test_lookup_ok_sends_grid_not_coords(monkeypatch):
    seen = {}

    def fake_urlopen(req, timeout=None):
        seen["url"], seen["timeout"] = req.full_url, timeout
        return body(ncst("1"))

    monkeypatch.setattr(W.urllib.request, "urlopen", fake_urlopen)
    res = W.lookup(LAT, LON, KEY, now=NOW)
    assert res == W.Result("rain", "2026-11-07T14:00:00+09:00", None)
    assert res.public() == {"precip": "rain", "observed_at": "2026-11-07T14:00:00+09:00"}
    url = urllib.parse.urlsplit(seen["url"])
    assert url.geturl().startswith(W.URL)
    q = dict(urllib.parse.parse_qsl(url.query))
    assert q == {"serviceKey": KEY, "pageNo": "1", "numOfRows": "10", "dataType": "JSON",
                 "base_date": "20261107", "base_time": "1400", "nx": str(NX), "ny": str(NY)}
    assert str(LAT) not in seen["url"] and str(LON) not in seen["url"]   # 기상청에도 격자만 간다
    assert seen["timeout"] == W.TIMEOUT_S == 3.0


def test_lookup_decodes_encoded_key(monkeypatch):
    """공공데이터포털 Encoding 키를 넣어도 두 번 인코딩하지 않는다(sources.py와 같은 처리)."""
    seen = {}

    def fake_urlopen(req, timeout=None):
        seen["url"] = req.full_url
        return body(ncst("0"))

    monkeypatch.setattr(W.urllib.request, "urlopen", fake_urlopen)
    assert W.lookup(LAT, LON, "abc%2Bdef%3D%3D", now=NOW).precip == "none"
    assert dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(seen["url"]).query))["serviceKey"] == "abc+def=="


def test_lookup_without_key_does_not_call(monkeypatch):
    monkeypatch.setattr(W.urllib.request, "urlopen", lambda *a, **k: pytest.fail("키 없이 기상청을 불렀다"))
    assert W.lookup(LAT, LON, None, now=NOW) == W.unknown("no_key")
    assert W.lookup(LAT, LON, "", now=NOW) == W.unknown("no_key")


@pytest.mark.parametrize("lat, lon", [(0.0, 0.0), (51.5, -0.12), (-90.0, 0.0), (90.0, 180.0)])
def test_lookup_out_of_grid_does_not_call(monkeypatch, lat, lon):
    monkeypatch.setattr(W.urllib.request, "urlopen", lambda *a, **k: pytest.fail("격자 밖인데 기상청을 불렀다"))
    assert W.lookup(lat, lon, KEY, now=NOW) == W.unknown("out_of_grid")


@pytest.mark.parametrize("exc, kind", [
    (urllib.error.HTTPError(W.URL, 500, "Internal Server Error", {}, None), "http_500"),
    (urllib.error.URLError(ConnectionRefusedError(f"refused {KEY}")), "network"),
    (urllib.error.URLError(socket.timeout("timed out")), "timeout"),
    (socket.timeout("timed out"), "timeout"),
    (TimeoutError("timed out"), "timeout"),
    (ConnectionResetError(f"reset {KEY}"), "network"),
])
def test_lookup_network_failures_are_unknown(monkeypatch, exc, kind):
    def fake_urlopen(req, timeout=None):
        raise exc

    monkeypatch.setattr(W.urllib.request, "urlopen", fake_urlopen)
    assert W.lookup(LAT, LON, KEY, now=NOW) == W.unknown(kind)


def test_lookup_kma_error_bodies_are_unknown(monkeypatch):
    for text, kind in [(XML_KEY_ERROR, "not_json"), (json.dumps(ncst(code="03")), "kma_03")]:
        monkeypatch.setattr(W.urllib.request, "urlopen", lambda req, timeout=None, t=text: body(t))
        assert W.lookup(LAT, LON, KEY, now=NOW) == W.unknown(kind)


def test_lookup_deadline_cuts_slow_call():
    """urlopen의 timeout은 연결·읽기 한 번마다라 전체를 묶지 못한다 → lookup이 마감에서 끊는다."""
    def slow():
        time.sleep(1.0)
        return json.dumps(ncst("1"))

    t0 = time.perf_counter()
    res = W.lookup(LAT, LON, KEY, now=NOW, timeout=0.1, fetch=slow)
    assert res == W.unknown("timeout")
    assert time.perf_counter() - t0 < 0.8


def test_lookup_unexpected_exception_is_unknown():
    def boom():
        raise KeyError("whatever")

    assert W.lookup(LAT, LON, KEY, now=NOW, fetch=boom) == W.unknown("KeyError")


# ------------------------------------------------------------ GET /v1/weather
@pytest.fixture
def api(monkeypatch):
    monkeypatch.setattr(M, "WEATHER_KEY", KEY)
    return TestClient(M.app)


def q(lat=LAT, lon=LON, **extra) -> str:
    return "/v1/weather?" + urllib.parse.urlencode({"lat": lat, "lon": lon, **extra})


def test_get_weather_200_exact_shape(api, monkeypatch):
    monkeypatch.setattr(W.urllib.request, "urlopen", lambda req, timeout=None: body(ncst("2")))
    r = api.get(q())
    assert r.status_code == 200
    assert r.json() == {"precip": "snow", "observed_at": "2026-11-07T14:00:00+09:00"}
    assert r.headers["cache-control"] == "no-store"      # URL에 좌표가 있으니 앱 HTTP 캐시에도 남기지 않는다


@pytest.mark.parametrize("exc", [
    urllib.error.URLError(ConnectionRefusedError("refused")),        # 기상청이 내려감
    urllib.error.HTTPError(W.URL, 502, "Bad Gateway", {}, None),
    socket.timeout("timed out"),
])
def test_get_weather_failure_is_200_unknown(api, monkeypatch, exc):
    """AC-C-6: 기상청이 실패해도 서버는 200 unknown → 앱은 '정보 없음'으로 출발·도착을 이어 간다."""
    def fake_urlopen(req, timeout=None):
        raise exc

    monkeypatch.setattr(W.urllib.request, "urlopen", fake_urlopen)
    r = api.get(q())
    assert r.status_code == 200 and r.json() == {"precip": "unknown", "observed_at": None}


def test_get_weather_over_deadline_is_unknown(api, monkeypatch):
    def slow_urlopen(req, timeout=None):
        time.sleep(1.0)
        return body(ncst("1"))

    monkeypatch.setattr(W, "TIMEOUT_S", 0.1)
    monkeypatch.setattr(W.urllib.request, "urlopen", slow_urlopen)
    t0 = time.perf_counter()
    r = api.get(q())
    assert r.status_code == 200 and r.json() == {"precip": "unknown", "observed_at": None}
    assert time.perf_counter() - t0 < 0.8


def test_get_weather_without_key_is_unknown(monkeypatch):
    monkeypatch.setattr(M, "WEATHER_KEY", None)
    monkeypatch.setattr(W.urllib.request, "urlopen", lambda *a, **k: pytest.fail("키 없이 기상청을 불렀다"))
    r = TestClient(M.app).get(q())
    assert r.status_code == 200 and r.json() == {"precip": "unknown", "observed_at": None}


@pytest.mark.parametrize("path", [
    q(odometer_km=15037),            # 원시 차량값이 실려 오면 받지 않는다
    q(vin="KMHXX00XXXX000000"),
    q(level_pct=80),
    q(x=""),
    "/v1/weather?lat=37.4",          # lon 없음
    "/v1/weather?lon=127.1",
    "/v1/weather",
    q(lat=91), q(lat=-91), q(lon=181),
    q(lat="nan"), q(lon="inf"), q(lat="abc"),
])
def test_get_weather_422(api, monkeypatch, path):
    monkeypatch.setattr(W.urllib.request, "urlopen", lambda *a, **k: pytest.fail("422인데 기상청을 불렀다"))
    assert api.get(path).status_code == 422


def test_weather_is_get_only(api):
    assert api.post("/v1/weather", json={"lat": LAT, "lon": LON}).status_code == 405


def test_weather_log_has_no_coords_grid_or_key(api, monkeypatch, capsys):
    """AC-N-4: 서버 로그에 좌표가 없다. 격자(nx, ny)·키·URL도 남기지 않는다."""
    calls = iter([body(ncst("1")), urllib.error.URLError(ConnectionRefusedError(f"refused {KEY}"))])

    def fake_urlopen(req, timeout=None):
        nxt = next(calls)
        if isinstance(nxt, Exception):
            raise nxt
        return nxt

    monkeypatch.setattr(W.urllib.request, "urlopen", fake_urlopen)
    capsys.readouterr()
    api.get(q())
    api.get(q())
    out = capsys.readouterr()
    text = out.out + out.err
    recs = [json.loads(ln) for ln in out.out.splitlines() if '"ev": "weather"' in ln]
    assert [(r["precip"], r.get("err")) for r in recs] == [("rain", None), ("unknown", "network")]
    assert all(set(r) <= {"t", "ev", "status", "ms", "precip", "err"} for r in recs)
    for bad in (str(LAT), str(LON), "37.41", "127.09", KEY, "apis.data.go.kr", f'"nx"', f'"ny"'):
        assert bad not in text


def test_health_reports_weather_key(api):
    assert api.get("/v1/health").json()["weather"] is True
