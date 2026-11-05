"""routes.py — 카카오 길찾기 실경로 보정. 네트워크 없이 fetch·urlopen 을 주입해서 본다."""

import dataclasses
import io
import json
import threading
import urllib.error
import urllib.parse

import pytest

from chagok import engine as E
from chagok import routes as R

KEY = "test-kakao-key-0123456789abcdef"
OLAT, OLNG = 37.5665, 126.9780          # 서울시청


def body(distance=48_000, duration=3_600, toll=1_200, code=0, msg="길찾기 성공", fare=True):
    summary = {"distance": distance, "duration": duration}
    if fare:
        summary["fare"] = {"taxi": 50_000, "toll": toll}
    r = {"result_code": code, "result_msg": msg}
    if code == 0:
        r["summary"] = summary
    return json.dumps({"trans_id": "x", "routes": [r]}, ensure_ascii=False)


def cand(i, lat=37.53, lng=127.31, route_km=40.0, minutes=44, toll=0):
    return E.Candidate(id=f"ta_{i}", name=f"후보{i}", theme="river", lat=lat + i * 0.01, lng=lng,
                       route_km=route_km, minutes=minutes, toll_krw=toll)


class Clock:
    def __init__(self, t=1_000_000.0):
        self.t = t

    def __call__(self):
        return self.t


class FakeFetch:
    """호출을 세고, 목적지 위도로 결과를 만든다. fail 에 든 목적지 위도는 RouteError."""

    def __init__(self, fail=()):
        self.calls = []
        self.fail = set(fail)
        self._lock = threading.Lock()

    def __call__(self, olat, olng, dlat, dlng):
        with self._lock:
            self.calls.append((olat, olng, dlat, dlng))
        if dlat in self.fail:
            raise R.RouteError("길찾기 실패 result_code=104")
        return R.Route(route_km=100.0 + dlat, minutes=90, toll_krw=3_000)


# ------------------------------------------------------------ 파싱·변환
def test_parse_ok():
    r = R.parse_directions(body(distance=48_000, duration=3_600, toll=1_200))
    assert r == R.Route(route_km=48.0, minutes=60, toll_krw=1_200)


def test_parse_no_fare_means_zero_toll():
    assert R.parse_directions(body(fare=False)).toll_krw == 0
    assert R.parse_directions(body(toll=None)).toll_krw == 0


@pytest.mark.parametrize("code", [1, 104, -1, None])
def test_parse_result_code_nonzero_raises(code):
    with pytest.raises(R.RouteError):
        R.parse_directions(body(code=code, msg="출발지와 도착지가 너무 가까움"))


@pytest.mark.parametrize("text", ["", "<html>", "{}", '{"routes": []}', '{"routes": [{"result_code": 0}]}'])
def test_parse_bad_shape_raises(text):
    with pytest.raises(R.RouteError):
        R.parse_directions(text)


@pytest.mark.parametrize("distance,km", [
    (2_250, 2.3),      # 2.25 → half-up 2.3 (내장 round 는 2.2)
    (2_249, 2.2),
    (12_350, 12.4),
    (48_049, 48.0),
    (48_050, 48.1),
])
def test_km_half_up(distance, km):
    assert R.parse_directions(body(distance=distance)).route_km == km


@pytest.mark.parametrize("duration,minutes", [
    (150, 3),          # 2.5분 → 3 (내장 round 는 2)
    (270, 5),          # 4.5분 → 5 (내장 round 는 4)
    (89, 1),
    (90, 2),
    (4_529, 75),       # 75.48분 → 75
    (4_530, 76),       # 75.5분 → 76 ("75분 안" 경계)
])
def test_minutes_half_up(duration, minutes):
    assert R.parse_directions(body(duration=duration)).minutes == minutes


# ------------------------------------------------------------ fetch_kakao (urlopen 주입)
class FakeResp:
    def __init__(self, text):
        self._b = text.encode("utf-8")

    def read(self):
        return self._b


def test_fetch_kakao_request_shape(monkeypatch):
    seen = {}

    def fake_urlopen(req, timeout=None):
        seen["url"], seen["auth"], seen["timeout"] = req.full_url, req.get_header("Authorization"), timeout
        return FakeResp(body(distance=31_000, duration=2_400, toll=0))

    monkeypatch.setattr(R.urllib.request, "urlopen", fake_urlopen)
    r = R.fetch_kakao(KEY, OLAT, OLNG, 37.5340, 127.3150, timeout=2.5)
    assert r == R.Route(31.0, 40, 0)
    q = urllib.parse.parse_qs(urllib.parse.urlsplit(seen["url"]).query)
    assert q["origin"] == [f"{OLNG},{OLAT}"]                 # 카카오는 경도,위도 순
    assert q["destination"] == ["127.315,37.534"]
    assert q["priority"] == ["RECOMMEND"] and q["summary"] == ["true"]
    assert seen["auth"] == f"KakaoAK {KEY}"
    assert KEY not in seen["url"]                            # 키는 헤더에만
    assert seen["timeout"] == 2.5


def test_fetch_kakao_http_error_message_has_no_key_or_origin(monkeypatch):
    def fake_urlopen(req, timeout=None):
        err = f'{{"msg":"wrong appKey({KEY}) format","origin":"{OLNG},{OLAT}","code":-401}}'
        raise urllib.error.HTTPError(req.full_url, 401, "Unauthorized", {}, io.BytesIO(err.encode()))

    monkeypatch.setattr(R.urllib.request, "urlopen", fake_urlopen)
    with pytest.raises(R.RouteError) as ei:
        R.fetch_kakao(KEY, OLAT, OLNG, 37.5340, 127.3150)
    msg = str(ei.value)
    assert "401" in msg
    assert KEY not in msg
    assert str(OLAT) not in msg and str(OLNG) not in msg
    assert ei.value.__cause__ is None and ei.value.__suppress_context__


def test_fetch_kakao_network_error_message_has_no_key_or_origin(monkeypatch):
    def fake_urlopen(req, timeout=None):
        raise urllib.error.URLError(f"boom {req.full_url} {KEY}")

    monkeypatch.setattr(R.urllib.request, "urlopen", fake_urlopen)
    with pytest.raises(R.RouteError) as ei:
        R.fetch_kakao(KEY, OLAT, OLNG, 37.5340, 127.3150)
    msg = str(ei.value)
    assert KEY not in msg and str(OLAT) not in msg and str(OLNG) not in msg


def test_fetch_kakao_timeout_is_route_error(monkeypatch):
    def fake_urlopen(req, timeout=None):
        raise TimeoutError("timed out")

    monkeypatch.setattr(R.urllib.request, "urlopen", fake_urlopen)
    with pytest.raises(R.RouteError):
        R.fetch_kakao(KEY, OLAT, OLNG, 37.5340, 127.3150)


def test_kakao_fetcher_passes_key_and_timeout(monkeypatch):
    seen = []
    monkeypatch.setattr(R, "fetch_kakao", lambda *a: seen.append(a) or R.Route(1.0, 1, 0))
    f = R.kakao_fetcher(KEY, timeout=1.5)
    assert f(OLAT, OLNG, 37.0, 127.0) == R.Route(1.0, 1, 0)
    assert seen == [(KEY, OLAT, OLNG, 37.0, 127.0, 1.5)]


# ------------------------------------------------------------ 격자 키
def test_grid_key_quantizes_to_001_degree():
    k = R.grid_key(OLAT, OLNG, "ta_1")
    assert k == (3757, 12698, "ta_1")
    assert all(isinstance(x, int) for x in k[:2])            # 원좌표(float)를 들고 있지 않다


def test_grid_key_same_cell_within_1km():
    # 서울시청 주변 수백 m 이동은 같은 칸
    assert R.grid_key(37.5661, 126.9779, "a") == R.grid_key(37.5699, 126.9760, "a") == R.grid_key(37.5652, 126.9849, "a")
    # 칸 경계를 넘으면 다른 칸
    assert R.grid_key(37.5649, 126.9780, "a") != R.grid_key(37.5651, 126.9780, "a")
    # 후보가 다르면 다른 키
    assert R.grid_key(OLAT, OLNG, "a") != R.grid_key(OLAT, OLNG, "b")


def test_grid_key_half_up_boundary():
    # x.xx5 경계는 위쪽 칸(half-up). 0.125 는 이진수로 정확하다
    assert R.grid_key(37.125, 127.125, "a")[:2] == (3713, 12713)


# ------------------------------------------------------------ 캐시
def test_cache_ttl_expiry():
    clk = Clock()
    c = R.RouteCache(ttl_s=100, clock=clk)
    c.put(("k",), R.Route(1.0, 1, 0))
    clk.t += 99
    assert c.get(("k",)) == R.Route(1.0, 1, 0)
    clk.t += 1                                                # 정확히 ttl → 만료
    assert c.get(("k",)) is None
    assert len(c) == 0                                        # 만료된 항목은 지운다


def test_cache_put_again_resets_ttl():
    clk = Clock()
    c = R.RouteCache(ttl_s=100, clock=clk)
    c.put(("k",), R.Route(1.0, 1, 0))
    clk.t += 60
    c.put(("k",), R.Route(2.0, 2, 0))
    clk.t += 60
    assert c.get(("k",)) == R.Route(2.0, 2, 0)


def test_cache_max_entries_evicts_oldest_first():
    clk = Clock()
    c = R.RouteCache(max_entries=3, clock=clk)
    for i in range(3):
        c.put((i,), R.Route(float(i), i, 0))
        clk.t += 1
    assert c.get((0,)) is not None                            # 읽기는 순서를 바꾸지 않는다
    c.put((3,), R.Route(3.0, 3, 0))
    assert len(c) == 3
    assert c.get((0,)) is None                                # 가장 오래된 0이 빠진다
    assert [c.get((i,)) is not None for i in (1, 2, 3)] == [True, True, True]
    c.put((1,), R.Route(1.5, 1, 0))                           # 다시 넣은 1은 가장 새것
    c.put((4,), R.Route(4.0, 4, 0))
    assert c.get((2,)) is None and c.get((1,)) == R.Route(1.5, 1, 0)


def test_cache_thread_safe_under_concurrent_puts():
    c = R.RouteCache(max_entries=500)

    def worker(base):
        for i in range(400):
            c.put((base, i), R.Route(1.0, 1, 0))
            c.get((base, i // 2))

    ts = [threading.Thread(target=worker, args=(b,)) for b in range(8)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert len(c) == 500


# ------------------------------------------------------------ refine
def test_refine_updates_only_route_fields_and_keeps_order():
    cands = [cand(i) for i in range(6)]
    before = [dataclasses.asdict(c) for c in cands]
    fetch = FakeFetch()
    out, st = R.refine(OLAT, OLNG, cands, fetch=fetch, cache=R.RouteCache())
    assert [c.id for c in out] == [c.id for c in cands]
    for c, o in zip(cands, out):
        assert o is not c
        assert (o.route_km, o.minutes, o.toll_krw) == (100.0 + c.lat, 90, 3_000)
        assert (o.id, o.name, o.theme, o.lat, o.lng, o.parking_krw, o.has_parking_info) == \
               (c.id, c.name, c.theme, c.lat, c.lng, c.parking_krw, c.has_parking_info)
    assert [dataclasses.asdict(c) for c in cands] == before   # 입력은 그대로
    assert (st.hits, st.calls, st.failures) == (0, 6, 0)
    assert all(call[:2] == (OLAT, OLNG) for call in fetch.calls)


def test_refine_cache_hit_reduces_calls():
    cands = [cand(i) for i in range(5)]
    cache = R.RouteCache()
    fetch = FakeFetch()
    out1, st1 = R.refine(OLAT, OLNG, cands, fetch=fetch, cache=cache)
    out2, st2 = R.refine(OLAT, OLNG, cands, fetch=fetch, cache=cache)
    assert (st1.calls, st1.hits) == (5, 0)
    assert (st2.calls, st2.hits) == (0, 5)
    assert len(fetch.calls) == 5
    assert out1 == out2


def test_refine_partial_cache():
    cands = [cand(i) for i in range(4)]
    cache = R.RouteCache()
    fetch = FakeFetch()
    R.refine(OLAT, OLNG, cands[:2], fetch=fetch, cache=cache)
    _, st = R.refine(OLAT, OLNG, cands, fetch=fetch, cache=cache)
    assert (st.hits, st.calls) == (2, 2)
    assert len(fetch.calls) == 4


def test_refine_shares_cache_within_same_grid_cell():
    cands = [cand(i) for i in range(3)]
    cache = R.RouteCache()
    fetch = FakeFetch()
    R.refine(37.5661, 126.9779, cands, fetch=fetch, cache=cache)
    _, st = R.refine(37.5699, 126.9760, cands, fetch=fetch, cache=cache)   # 같은 칸 안 다른 출발지
    assert (st.hits, st.calls) == (3, 0)
    _, st = R.refine(37.5800, 126.9780, cands, fetch=fetch, cache=cache)   # 다른 칸
    assert (st.hits, st.calls) == (0, 3)


def test_refine_ttl_expiry_calls_again():
    clk = Clock()
    cache = R.RouteCache(ttl_s=7 * 86400, clock=clk)
    cands = [cand(0)]
    fetch = FakeFetch()
    R.refine(OLAT, OLNG, cands, fetch=fetch, cache=cache)
    clk.t += 7 * 86400
    _, st = R.refine(OLAT, OLNG, cands, fetch=fetch, cache=cache)
    assert (st.hits, st.calls) == (0, 1)


def test_refine_failure_keeps_estimate_and_counts():
    cands = [cand(i, route_km=40.0 + i, minutes=44 + i, toll=0) for i in range(4)]
    fail_lat = cands[1].lat
    cache = R.RouteCache()
    fetch = FakeFetch(fail=[fail_lat])
    out, st = R.refine(OLAT, OLNG, cands, fetch=fetch, cache=cache)
    assert (st.calls, st.failures, st.hits) == (4, 1, 0)
    assert out[1] == cands[1]                                 # 추정값 그대로
    assert out[0].minutes == 90 and out[2].minutes == 90 and out[3].minutes == 90
    assert len(cache) == 3                                    # 실패는 캐시에 넣지 않는다 → 다음에 다시 시도
    _, st2 = R.refine(OLAT, OLNG, cands, fetch=FakeFetch(), cache=cache)
    assert (st2.hits, st2.calls, st2.failures) == (3, 1, 0)


def test_refine_any_exception_is_swallowed():
    def boom(*a):
        raise ValueError("unexpected")

    cands = [cand(0), cand(1)]
    out, st = R.refine(OLAT, OLNG, cands, fetch=boom, cache=R.RouteCache())
    assert out == cands and st.failures == 2


def test_refine_runs_in_parallel():
    n = 5
    barrier = threading.Barrier(n, timeout=5)                 # 5개가 동시에 들어와야 풀린다

    def fetch(olat, olng, dlat, dlng):
        barrier.wait()
        return R.Route(1.0, 1, 0)

    _, st = R.refine(OLAT, OLNG, [cand(i) for i in range(n)], fetch=fetch, cache=R.RouteCache(), max_workers=n)
    assert (st.calls, st.failures) == (n, 0)


def test_refine_empty():
    out, st = R.refine(OLAT, OLNG, [], fetch=FakeFetch(), cache=R.RouteCache())
    assert out == [] and (st.hits, st.calls, st.failures) == (0, 0, 0)


def test_refine_duplicate_candidate_called_once():
    c = cand(0)
    fetch = FakeFetch()
    out, st = R.refine(OLAT, OLNG, [c, c], fetch=fetch, cache=R.RouteCache())
    assert st.calls == 1 and len(fetch.calls) == 1
    assert out[0] == out[1] and out[0].minutes == 90


def test_cache_keys_hold_no_raw_origin():
    cache = R.RouteCache()
    R.refine(OLAT, OLNG, [cand(0)], fetch=FakeFetch(), cache=cache)
    (key,) = list(cache._data)
    assert OLAT not in key and OLNG not in key
