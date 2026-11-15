"""weather.trip·GET /v1/weather/trip — 도착 때 한 번, 1시간 조각마다 두 지점 강수(#63, AC-C-6, AC-N-4).

조각 [H, H+1)은 H+1 정시 실황(RN1 = 그 1시간 강수량, PTY = H+1 시각 형태)으로 판정한다.
네트워크 없이 fetch·urlopen을 주입해서 본다.
"""

import io
import json
import re
import threading
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
DEP = (37.412734, 127.094718)      # 성남
ARR = (37.532812, 127.315829)      # 양평 두물머리
DEP_G, ARR_G = W.to_grid(*DEP), W.to_grid(*ARR)
NOW = datetime(2026, 11, 7, 16, 50, tzinfo=W.KST)     # 최근 발표분 16:00
FROM = datetime(2026, 11, 7, 14, 20, tzinfo=W.KST)
TO = datetime(2026, 11, 7, 16, 45, tzinfo=W.KST)
HOURS = ["2026-11-07T14:00:00+09:00", "2026-11-07T15:00:00+09:00", "2026-11-07T16:00:00+09:00"]


def ncst(pty: str = "0", rn1: str | None = "0", code: str = "00", base_date: str = "20261107",
         base_time: str = "1500", nx: int = 0, ny: int = 0) -> str:
    """getUltraSrtNcst 응답과 같은 모양(8개 항목, obsrValue는 문자열). rn1=None이면 RN1 항목을 뺀다."""
    vals = {"PTY": pty, "REH": "88", "RN1": rn1, "T1H": "9.4", "UUU": "-1.2", "VEC": "310", "VVV": "1.4", "WSD": "1.9"}
    items = [{"baseDate": base_date, "baseTime": base_time, "category": c, "nx": nx, "ny": ny, "obsrValue": v}
             for c, v in vals.items() if v is not None]
    return json.dumps({"response": {"header": {"resultCode": code, "resultMsg": "NORMAL_SERVICE" if code == "00" else "ERR"},
                                    "body": {"dataType": "JSON", "items": {"item": items},
                                             "pageNo": 1, "numOfRows": 10, "totalCount": len(items)}}})


class Recorder:
    """fetch 대역. (nx, ny, base_date, base_time) → 응답 본문. 부른 인자를 남긴다."""

    def __init__(self, table: dict | None = None, default: tuple[str, str] = ("0", "0")):
        self.table = table or {}       # {(grid, "HH00"): (pty, rn1) | Exception | callable}
        self.default = default
        self.calls: list[tuple] = []
        self.lock = threading.Lock()

    def __call__(self, nx, ny, bd, bt):
        with self.lock:
            self.calls.append((nx, ny, bd, bt))
        v = self.table.get(((nx, ny), bt), self.default)
        if isinstance(v, Exception):
            raise v
        if callable(v):
            return v()
        return ncst(v[0], v[1], base_date=bd, base_time=bt, nx=nx, ny=ny)


# ------------------------------------------------------------ 한 시간 판정 (PTY·RN1)
@pytest.mark.parametrize("pty, rn1, precip", [
    ("0", "0", "none"),
    ("0", "1.5", "rain"),          # PTY가 0이어도 그 1시간에 내렸으면 비
    ("0", "0.1", "rain"),
    ("1", "0", "rain"),            # PTY가 있으면 RN1이 0이어도 강수(막 내리기 시작)
    ("4", "0", "rain"), ("5", "0.2", "rain"),
    ("2", "0.5", "snow"),          # 비/눈은 눈
    ("3", "0", "snow"), ("6", "0", "snow"), ("7", "0", "snow"),
    ("0", None, "none"),           # RN1이 없으면 PTY만 본다(/v1/weather와 같은 판정)
    ("0", "-998.9", "none"),       # 결측
    ("0", "900", "none"),
    ("0", "강수없음", "none"),      # 숫자가 아니면 RN1을 버린다
    ("0", "nan", "none"),
    ("8", "2", "rain"),            # PTY를 못 써도 RN1 > 0이면 비
    ("abc", "0.3", "rain"),
    (None, "0.1", "rain"),
    ("0.0", "0", "none"),
])
def test_judge_hour(pty, rn1, precip):
    assert W.judge_hour(pty, rn1) == precip


@pytest.mark.parametrize("pty, rn1, kind", [
    ("8", "0", "pty_unexpected"),
    ("-1", None, "pty_unexpected"),
    ("abc", "0", "pty_not_number"),
    (None, "0", "no_pty"),
    (None, None, "no_pty"),
])
def test_judge_hour_cannot_decide(pty, rn1, kind):
    with pytest.raises(W.WeatherError) as e:
        W.judge_hour(pty, rn1)
    assert e.value.kind == kind


def test_parse_ncst_hour_reads_pty_and_rn1():
    assert W.parse_ncst_hour(ncst("0", "2.0")) == "rain"
    assert W.parse_ncst_hour(ncst("3", "0")) == "snow"
    assert W.parse_ncst_hour(ncst("0", "0")) == "none"


@pytest.mark.parametrize("text, kind", [
    (ncst(code="10"), "kma_10"),       # 24시간보다 오래된 발표분(11/7 실측)
    (ncst(code="03"), "kma_03"),
    ("<OpenAPI_ServiceResponse/>", "not_json"),
    (json.dumps({"response": {"header": {"resultCode": "00"}, "body": {"items": ""}}}), "no_pty"),
])
def test_parse_ncst_hour_failures(text, kind):
    with pytest.raises(W.WeatherError) as e:
        W.parse_ncst_hour(text)
    assert e.value.kind == kind


def test_parse_ncst_unchanged_pty_only():
    """/v1/weather(lookup)는 지금처럼 PTY만 본다. RN1이 있어도 PTY 0이면 none."""
    assert W.parse_ncst(ncst("0", "2.0", base_time="1600")) == ("none", "2026-11-07T16:00:00+09:00")


# ------------------------------------------------------------ 1시간 조각
@pytest.mark.parametrize("start, end, hours", [
    (FROM, TO, [14, 15, 16]),
    (FROM.replace(minute=0), FROM.replace(hour=15, minute=0), [14]),     # 정시에 끝나면 다음 조각은 없다
    (FROM.replace(minute=0), FROM.replace(hour=15, minute=0, second=1), [14, 15]),
    (FROM, FROM.replace(minute=21), [14]),
    (datetime(2026, 11, 7, 23, 30, tzinfo=W.KST), datetime(2026, 11, 8, 1, 10, tzinfo=W.KST), [23, 0, 1]),
    (datetime(2026, 11, 7, 5, 20, tzinfo=timezone.utc), datetime(2026, 11, 7, 7, 45, tzinfo=timezone.utc), [14, 15, 16]),
])
def test_trip_hours(start, end, hours):
    got = W.trip_hours(start, end)
    assert [h.hour for h in got] == hours
    assert all(h.tzinfo == W.KST and h.minute == 0 for h in got)


def test_trip_hours_needs_timezone():
    with pytest.raises(ValueError):
        W.trip_hours(datetime(2026, 11, 7, 14, 20), TO)


# ------------------------------------------------------------ trip: 조각 → 발표분, 판정
def test_trip_rain_then_stops_at_departure():
    """#63 경우 1: 출발 때 오던 비가 중간에 그침. 앞 조각만 비, 도착 지점은 맑음."""
    fake = Recorder({(DEP_G, "1500"): ("1", "2.0")})
    res = W.trip(DEP, ARR, FROM, TO, KEY, now=NOW, fetch=fake)
    assert res.status == "ok"
    assert res.public() == {
        "status": "ok",
        "dep": [{"hour": HOURS[0], "precip": "rain"}, {"hour": HOURS[1], "precip": "none"},
                {"hour": HOURS[2], "precip": "none"}],
        "arr": [{"hour": h, "precip": "none"} for h in HOURS]}


def test_trip_rain_only_midway_is_caught():
    """#63 경우 2: 출발·도착 순간은 맑고 중간에만 비. 1회 관측으로는 못 잡던 비를 조각으로 잡는다."""
    fake = Recorder({(DEP_G, "1600"): ("0", "3.5"), (ARR_G, "1600"): ("0", "1.0")})
    res = W.trip(DEP, ARR, FROM, TO, KEY, now=NOW, fetch=fake)
    # 15시 조각은 16:00 실황(RN1 = 15~16시 강수). 16시 조각은 17:00 발표 전이라 최근 16:00분을 쓴다
    assert res.dep == ("none", "rain", "rain") and res.arr == ("none", "rain", "rain")


def test_trip_hour_maps_to_next_hour_observation_and_dedupes():
    """조각 [H, H+1) → H+1 정시분. 발표 전 조각은 최근분으로 대신하고, 같은 (격자, 발표분)은 한 번만 부른다."""
    fake = Recorder()
    res = W.trip(DEP, ARR, FROM, TO, KEY, now=NOW, fetch=fake)
    assert sorted(fake.calls) == sorted([(*g, "20261107", bt) for g in (DEP_G, ARR_G) for bt in ("1500", "1600")])
    assert res.calls == 4 and res.hours == tuple(HOURS)


def test_trip_before_ten_past_uses_previous_hour():
    """16:05면 16:00분이 아직 없다(매시 10분 이후 제공) → 15:00분이 최근. 15·16시 조각 모두 15:00분."""
    fake = Recorder()
    W.trip(DEP, ARR, FROM, TO.replace(hour=16, minute=3), KEY, now=NOW.replace(minute=5), fetch=fake)
    assert sorted({c[3] for c in fake.calls}) == ["1500"]


def test_trip_same_grid_calls_once_per_hour():
    fake = Recorder()
    res = W.trip(DEP, DEP, FROM, TO, KEY, now=NOW, fetch=fake)
    assert len(fake.calls) == 2 and res.dep == res.arr


def test_trip_across_midnight_uses_kst_dates():
    fake = Recorder()
    now = datetime(2026, 11, 8, 1, 20, tzinfo=W.KST)
    W.trip(DEP, DEP, datetime(2026, 11, 7, 23, 30, tzinfo=W.KST), now - timedelta(minutes=5), KEY, now=now, fetch=fake)
    assert sorted(c[2:] for c in fake.calls) == [("20261108", "0000"), ("20261108", "0100")]


def test_trip_sends_grid_not_coords():
    fake = Recorder()
    W.trip(DEP, ARR, FROM, TO, KEY, now=NOW, fetch=fake)
    assert {c[:2] for c in fake.calls} == {DEP_G, ARR_G}
    assert all(isinstance(v, int) for c in fake.calls for v in c[:2])


def test_trip_too_old_hours_are_not_called():
    """기상청은 최근 24시간 실황만 준다 → 그보다 오래된 발표분은 부르지 않고 unknown(too_old)."""
    fake = Recorder()
    start = NOW - timedelta(hours=25, minutes=30)     # 11/6 15:20
    res = W.trip(DEP, DEP, start, start + timedelta(hours=2), KEY, now=NOW, fetch=fake)
    # 조각 15·16·17시(11/6) → 발표분 16·17·18시. 11/6 16:50보다 이른 16:00분만 부르지 않는다
    assert [c[3] for c in sorted(fake.calls)] == ["1700", "1800"]
    assert res.dep == ("unknown", "none", "none") and res.status == "partial" and res.errs == ("too_old",)


def test_trip_without_key_does_not_call():
    res = W.trip(DEP, ARR, FROM, TO, None, now=NOW, fetch=lambda *a: pytest.fail("키 없이 기상청을 불렀다"))
    assert res.status == "unavailable" and res.unknown == 6 and res.errs == ("no_key",) and res.calls == 0
    assert res.public()["dep"] == [{"hour": h, "precip": "unknown"} for h in HOURS]


def test_trip_out_of_grid_point_is_unknown_other_point_still_judged():
    fake = Recorder()
    res = W.trip((51.5, -0.12), ARR, FROM, TO, KEY, now=NOW, fetch=fake)
    assert res.dep == ("unknown",) * 3 and res.arr == ("none",) * 3
    assert res.status == "partial" and res.errs == ("out_of_grid",)
    assert {c[:2] for c in fake.calls} == {ARR_G}


@pytest.mark.parametrize("lat", [-90.0, 90.0])
def test_trip_unprojectable_point_is_unknown(lat):
    res = W.trip((lat, 0.0), (lat, 0.0), FROM, TO, KEY, now=NOW, fetch=Recorder())
    assert res.status == "unavailable" and res.errs == ("out_of_grid",)


def test_trip_failed_hour_is_unknown_only_there():
    fake = Recorder({(DEP_G, "1500"): W.WeatherError("network"),
                     (ARR_G, "1600"): lambda: ncst(code="10")})
    res = W.trip(DEP, ARR, FROM, TO, KEY, now=NOW, fetch=fake)
    assert res.dep == ("unknown", "none", "none")
    assert res.arr == ("none", "unknown", "unknown")
    assert res.status == "partial" and res.errs == ("kma_10", "network") and res.unknown == 3


def test_trip_unexpected_exception_is_unknown():
    res = W.trip(DEP, ARR, FROM, TO, KEY, now=NOW, fetch=Recorder(default=KeyError("x")))
    assert res.status == "unavailable" and res.errs == ("KeyError",)


def test_trip_deadline_cuts_slow_hours_only():
    """전체 마감(timeout)에서 끊는다. 늦은 조각만 unknown(timeout), 받은 조각은 그대로."""
    def slow():
        time.sleep(1.0)
        return ncst("1", "1.0")

    fake = Recorder({(DEP_G, "1600"): slow})
    t0 = time.perf_counter()
    res = W.trip(DEP, ARR, FROM, TO, KEY, now=NOW, timeout=0.2, fetch=fake)
    assert time.perf_counter() - t0 < 0.8
    assert res.dep == ("none", "unknown", "unknown") and res.arr == ("none",) * 3
    assert res.status == "partial" and res.errs == ("timeout",)


def test_trip_deadline_all_slow_is_unavailable():
    def slow(*a):
        time.sleep(1.0)
        return ncst()

    t0 = time.perf_counter()
    res = W.trip(DEP, ARR, FROM, TO, KEY, now=NOW, timeout=0.1, fetch=slow)
    assert time.perf_counter() - t0 < 0.8
    assert res.status == "unavailable" and res.errs == ("timeout",)


def test_trip_24_hours_is_25_hours_within_window():
    """24시간 주행(최대)은 조각 25개. 지금 기준 24시간 안의 발표분만 부른다."""
    fake = Recorder()
    end = NOW - timedelta(minutes=5)
    res = W.trip(DEP, ARR, end - W.TRIP_MAX, end, KEY, now=NOW, fetch=fake)
    assert len(res.hours) == 25 and res.status == "ok"
    assert len(fake.calls) == 2 * 24          # 발표분 11/6 17:00 ~ 11/7 16:00 (마지막 두 조각이 16:00분을 같이 씀)


# ------------------------------------------------------------ GET /v1/weather/trip
@pytest.fixture
def api(monkeypatch):
    monkeypatch.setattr(M, "WEATHER_KEY", KEY)
    monkeypatch.setattr(W, "now_kst", lambda: NOW)
    return TestClient(M.app)


def iso(t: datetime) -> str:
    return t.isoformat()


def q(dep=DEP, arr=ARR, start=FROM, end=TO, **extra) -> str:
    params = {"dep_lat": dep[0], "dep_lon": dep[1], "arr_lat": arr[0], "arr_lon": arr[1],
              "from": iso(start) if isinstance(start, datetime) else start,
              "to": iso(end) if isinstance(end, datetime) else end, **extra}
    return "/v1/weather/trip?" + urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})


class FakeResp(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def kma_urlopen(table: dict | None = None):
    """urlopen 대역: 요청 URL의 nx·ny·base_time으로 응답을 고른다."""
    table = table or {}
    seen = []

    def fake(req, timeout=None):
        qs = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(req.full_url).query))
        seen.append(qs)
        g, bt = (int(qs["nx"]), int(qs["ny"])), qs["base_time"]
        pty, rn1 = table.get((g, bt), ("0", "0"))
        return FakeResp(ncst(pty, rn1, base_date=qs["base_date"], base_time=bt).encode("utf-8"))

    fake.seen = seen
    return fake


def test_get_trip_200_exact_shape(api, monkeypatch):
    fake = kma_urlopen({(DEP_G, "1500"): ("0", "4.0"), (ARR_G, "1600"): ("3", "0")})
    monkeypatch.setattr(W.urllib.request, "urlopen", fake)
    r = api.get(q())
    assert r.status_code == 200
    assert r.json() == {
        "status": "ok",
        "dep": [{"hour": HOURS[0], "precip": "rain"}, {"hour": HOURS[1], "precip": "none"},
                {"hour": HOURS[2], "precip": "none"}],
        "arr": [{"hour": HOURS[0], "precip": "none"}, {"hour": HOURS[1], "precip": "snow"},
                {"hour": HOURS[2], "precip": "snow"}]}
    assert r.headers["cache-control"] == "no-store"   # URL에 좌표가 있으니 앱 HTTP 캐시에도 남기지 않는다
    # 기상청에는 격자·발표 시각만 간다(좌표·from·to 없음)
    assert all(set(s) == {"serviceKey", "pageNo", "numOfRows", "dataType", "base_date", "base_time", "nx", "ny"}
               for s in fake.seen)


def test_get_trip_accepts_utc_and_encoded_offset(api, monkeypatch):
    monkeypatch.setattr(W.urllib.request, "urlopen", kma_urlopen())
    a = api.get(q(start="2026-11-07T05:20:00Z", end="2026-11-07T07:45:00Z")).json()
    b = api.get(q()).json()            # urlencode가 +09:00의 +를 %2B로 보낸다
    assert a == b and a["status"] == "ok" and [h["hour"] for h in a["dep"]] == HOURS


def test_get_trip_failure_is_200(api, monkeypatch):
    """AC-C-6: 기상청이 실패해도 200 → 앱은 지금 규칙(출발·도착 1회)으로 이어 간다."""
    def down(req, timeout=None):
        raise urllib.error.URLError(ConnectionRefusedError("refused"))

    monkeypatch.setattr(W.urllib.request, "urlopen", down)
    r = api.get(q())
    assert r.status_code == 200 and r.json()["status"] == "unavailable"
    assert all(h["precip"] == "unknown" for h in r.json()["dep"] + r.json()["arr"])


def test_get_trip_without_key(monkeypatch):
    monkeypatch.setattr(M, "WEATHER_KEY", None)
    monkeypatch.setattr(W, "now_kst", lambda: NOW)
    monkeypatch.setattr(W.urllib.request, "urlopen", lambda *a, **k: pytest.fail("키 없이 기상청을 불렀다"))
    r = TestClient(M.app).get(q())
    assert r.status_code == 200 and r.json()["status"] == "unavailable"


@pytest.mark.parametrize("path", [
    q(odometer_km=15037),                          # 원시 차량값이 실려 오면 받지 않는다
    q(vin="KMHXX00XXXX000000"),
    q(lat=37.41), q(lon=127.09),                   # /v1/weather 쿼리 이름
    q(from_="2026-11-07T14:20:00+09:00"),
    q(dep=(None, 127.09)), q(arr=(37.5, None)),
    q(start=None), q(end=None),
    q(dep=(91, 127.0)), q(arr=(37.5, 181)), q(dep=("nan", 127.0)), q(arr=(37.5, "inf")),
    q(start=TO, end=FROM),                         # from ≥ to
    q(start=FROM, end=FROM),
    q(start=TO - timedelta(hours=24, minutes=1)),  # 24시간 초과
    q(end=NOW + timedelta(minutes=11)),            # 지금보다 10분 넘게 늦음
    q(start="2026-11-07T14:20:00"),                # 시간대 없음
    q(start="14:20"), q(start="abc"),
    "/v1/weather/trip?dep_lat=37.41&dep_lon=127.09&arr_lat=37.53&arr_lon=127.32"
    "&from=2026-11-07T14:20:00+09:00&to=2026-11-07T16:45:00+09:00",   # +를 인코딩 안 함 → 공백
    "/v1/weather/trip",
])
def test_get_trip_422(api, monkeypatch, path):
    monkeypatch.setattr(W.urllib.request, "urlopen", lambda *a, **k: pytest.fail("422인데 기상청을 불렀다"))
    assert api.get(path).status_code == 422


@pytest.mark.parametrize("path", [
    q(start=TO - timedelta(hours=24)),             # 딱 24시간
    q(end=NOW + timedelta(minutes=9)),             # 앱 시계가 조금 빠름
])
def test_get_trip_edges_ok(api, monkeypatch, path):
    monkeypatch.setattr(W.urllib.request, "urlopen", kma_urlopen())
    r = api.get(path)
    assert r.status_code == 200 and r.json()["status"] == "ok"


def test_trip_is_get_only(api):
    assert api.post("/v1/weather/trip", json={}).status_code == 405


def test_weather_still_answers(api, monkeypatch):
    """지금 /v1/weather는 그대로 둔다(대체 경로)."""
    monkeypatch.setattr(W.urllib.request, "urlopen", kma_urlopen())
    r = api.get(f"/v1/weather?lat={DEP[0]}&lon={DEP[1]}")
    assert r.status_code == 200 and r.json()["precip"] == "none"


def test_trip_log_has_no_coords_times_grid_or_key(api, monkeypatch, capsys):
    """AC-N-4: 로그에는 개수·상태·오류 종류만. 좌표·격자·출발·도착 시각·키·URL은 남기지 않는다."""
    fake = kma_urlopen({(DEP_G, "1500"): ("1", "1.0")})

    def flaky(req, timeout=None):
        if "base_time=1600" in req.full_url and f"nx={ARR_G[0]}" in req.full_url:
            raise urllib.error.URLError(ConnectionRefusedError(f"refused {KEY}"))
        return fake(req, timeout)

    monkeypatch.setattr(W.urllib.request, "urlopen", flaky)
    capsys.readouterr()
    api.get(q())
    out = capsys.readouterr()
    # 로그 시각("t")은 테스트를 돌린 시각이라 빼고 본다
    text = re.sub(r'"t": "[^"]*"', "", out.out) + out.err
    recs = [json.loads(ln) for ln in out.out.splitlines() if '"ev": "weather_trip"' in ln]
    assert len(recs) == 1
    rec = recs[0]
    assert set(rec) <= {"t", "ev", "status", "ms", "result", "hours", "calls", "unknown", "err"}
    assert (rec["result"], rec["hours"], rec["calls"], rec["unknown"], rec["err"]) == ("partial", 3, 4, 2, "network")
    for bad in (*map(str, DEP + ARR), "37.41", "127.09", "37.53", "127.31", KEY, "apis.data.go.kr",
                "14:20", "16:45", "T14", "T16", '"nx"', '"ny"', '"from"', '"to"'):
        assert bad not in text
