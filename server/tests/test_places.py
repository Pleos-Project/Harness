"""places.py·GET /v1/places — 카카오 로컬 키워드 검색 대행(노고 PRD v1.3 8장, #56, AC-N-4).

네트워크 없이 urlopen·fetch를 주입해서 본다.
"""

import io
import json
from pathlib import Path
import socket
import time
import urllib.error
import urllib.parse

import pytest
from fastapi.testclient import TestClient

from chagok import main as M
from chagok import places as PL

KEY = "test-kakao-key-ABCDEF0123"
QUERY = "두물머리"
# 로그·응답에 남으면 안 되는 현재 위치. 흔한 값과 겹치지 않게 자릿수를 길게 둔다
LAT, LNG = 37.412734, 127.094718

# 11/7 실호출(query=두물머리, x·y 있음)과 같은 모양. 값은 x·y·id·주소 문자열 그대로, distance는 문자열
DOCS = [
    {"address_name": "경기 양평군 양서면 양수리", "category_group_code": "", "category_name": "여행 > 관광,명소",
     "distance": "23671", "id": "7845373", "phone": "", "place_name": "두물머리",
     "place_url": "http://place.map.kakao.com/7845373", "road_address_name": "",
     "x": "127.315829778049", "y": "37.5328127891601"},
    {"address_name": "경기 양평군 양서면 양수리 1161-5", "category_group_code": "MT1",
     "category_name": "가정,생활 > 슈퍼마켓 > 대형슈퍼 > 하나로마트",
     "distance": "24417", "id": "2141791502", "phone": "031-774-5944", "place_name": "양서농협 두물머리로컬푸드직매장",
     "place_url": "http://place.map.kakao.com/2141791502", "road_address_name": "경기 양평군 양서면 두물머리길 38",
     "x": "127.31911437324305", "y": "37.54082012837359"},
]


def keyword(docs=None, total=None) -> dict:
    docs = DOCS if docs is None else docs
    return {"documents": docs, "meta": {"is_end": True, "pageable_count": len(docs), "total_count": total or len(docs),
                                        "same_name": {"keyword": QUERY, "region": [], "selected_region": ""}}}


EXPECTED = [
    {"name": "두물머리", "address": "경기 양평군 양서면 양수리", "lat": 37.5328127891601, "lng": 127.315829778049,
     "place_id": "7845373", "category": "관광,명소", "distance_m": 23671,    # 도로명 주소가 없으면 지번 주소
     "category_path": ["여행", "관광,명소"]},
    {"name": "양서농협 두물머리로컬푸드직매장", "address": "경기 양평군 양서면 두물머리길 38",
     "lat": 37.54082012837359, "lng": 127.31911437324305, "place_id": "2141791502",
     "category": "하나로마트", "distance_m": 24417,                        # 분류는 마지막 칸
     "category_path": ["가정,생활", "슈퍼마켓", "대형슈퍼", "하나로마트"]},        # 윗칸부터 모든 칸(#116)
]


def no_location(docs):
    """현재 위치(x·y)를 안 보냈을 때 카카오는 distance를 빈 문자열로 준다."""
    return [dict(d, distance="") for d in docs]


class FakeResp(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


def body(obj) -> FakeResp:
    return FakeResp((obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False)).encode("utf-8"))


# ------------------------------------------------------------ 응답 해석
def test_parse_ok_maps_fields():
    assert [p.public() for p in PL.parse_keyword(json.dumps(keyword(), ensure_ascii=False))] == EXPECTED


def test_parse_caps_at_ten():
    docs = [dict(DOCS[0], id=str(i), place_name=f"곳 {i}") for i in range(15)]
    out = PL.parse_keyword(json.dumps(keyword(docs), ensure_ascii=False))
    assert len(out) == PL.MAX_RESULTS == 10 and [p.place_id for p in out] == [str(i) for i in range(10)]


def test_parse_skips_unusable_items():
    bad = [dict(DOCS[0], place_name=""), dict(DOCS[0], id=""), dict(DOCS[0], x=""), dict(DOCS[0], y="abc"),
           dict(DOCS[0], y="91"), dict(DOCS[0], x="nan"), "garbage", None]
    out = PL.parse_keyword(json.dumps(keyword([*bad, DOCS[1]]), ensure_ascii=False))
    assert [p.place_id for p in out] == ["2141791502"]


def test_parse_without_location_distance_is_null():
    out = PL.parse_keyword(json.dumps(keyword(no_location(DOCS)), ensure_ascii=False))
    assert [p.distance_m for p in out] == [None, None]
    assert [p.category for p in out] == ["관광,명소", "하나로마트"]


@pytest.mark.parametrize("category_name, category", [
    ("여행 > 관광,명소", "관광,명소"), ("음식점 > 패스트푸드", "패스트푸드"), ("주차장", "주차장"),
    ("", None), (None, None), ("음식점 > ", None),
])
def test_parse_category_last_part(category_name, category):
    out = PL.parse_keyword(json.dumps(keyword([dict(DOCS[0], category_name=category_name)]), ensure_ascii=False))
    assert out[0].category == category


@pytest.mark.parametrize("category_name, path", [
    ("교통,수송 > 지하철,전철 > 수도권2호선", ["교통,수송", "지하철,전철", "수도권2호선"]),   # 마지막 칸만으로는 역인 줄 모른다
    ("여행 > 관광,명소 > 섬 > 섬(내륙)", ["여행", "관광,명소", "섬", "섬(내륙)"]),
    ("주차장", ["주차장"]), ("", []), (None, []), ("음식점 > ", ["음식점"]), (" > 음식점 >  > 카페 ", ["음식점", "카페"]),
])
def test_parse_category_path_all_parts(category_name, path):
    """category_path는 윗칸부터 모든 칸, 빈 칸은 버린다. 응답에서는 목록으로 나간다(#116)."""
    out = PL.parse_keyword(json.dumps(keyword([dict(DOCS[0], category_name=category_name)]), ensure_ascii=False))
    assert list(out[0].category_path) == path
    assert out[0].public()["category_path"] == path
    if path and category_name and not category_name.rstrip().endswith(">"):
        assert out[0].category == path[-1]


@pytest.mark.parametrize("distance, distance_m", [
    ("23671", 23671), ("0", 0), ("12.5", 13), ("", None), (None, None), ("abc", None), ("-1", None), ("nan", None),
])
def test_parse_distance(distance, distance_m):
    out = PL.parse_keyword(json.dumps(keyword([dict(DOCS[0], distance=distance)]), ensure_ascii=False))
    assert out[0].distance_m == distance_m


def test_parse_empty_documents_is_empty_list():
    assert PL.parse_keyword(json.dumps(keyword([]))) == []


@pytest.mark.parametrize("text, kind", [
    ("", "not_json"),
    ("<html>error</html>", "not_json"),
    ('{"errorType": "AccessDeniedError", "message": "App disabled OPEN_MAP_AND_LOCAL service."}', "no_documents"),
    ("[]", "no_documents"),
    ('{"documents": "x"}', "no_documents"),
])
def test_parse_failures(text, kind):
    with pytest.raises(PL.PlacesError) as e:
        PL.parse_keyword(text)
    assert e.value.kind == kind


# ------------------------------------------------------------ 카카오 호출 (urlopen 주입)
def capture(monkeypatch, resp):
    seen = {}

    def fake_urlopen(req, timeout=None):
        seen["url"], seen["auth"], seen["timeout"] = req.full_url, req.get_header("Authorization"), timeout
        return body(resp)

    monkeypatch.setattr(PL.urllib.request, "urlopen", fake_urlopen)
    return seen


def test_search_ok_with_location(monkeypatch):
    seen = capture(monkeypatch, keyword())
    res = PL.search(QUERY, KEY, near=(LAT, LNG))
    assert res.status == "ok" and res.err is None
    assert res.public() == {"status": "ok", "places": EXPECTED}
    url = urllib.parse.urlsplit(seen["url"])
    assert url.geturl().startswith(PL.URL)
    assert dict(urllib.parse.parse_qsl(url.query)) == {
        "query": QUERY, "page": "1", "size": "10", "sort": "accuracy", "x": str(LNG), "y": str(LAT)}
    assert seen["auth"] == f"KakaoAK {KEY}"          # 키는 헤더로만
    assert KEY not in seen["url"]
    assert seen["timeout"] == PL.TIMEOUT_S == 3.0


def test_search_without_location_sends_no_xy(monkeypatch):
    seen = capture(monkeypatch, keyword(no_location(DOCS)))
    res = PL.search(QUERY, KEY)
    assert res.status == "ok" and [p["distance_m"] for p in res.public()["places"]] == [None, None]
    q = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(seen["url"]).query))
    assert "x" not in q and "y" not in q and q["sort"] == "accuracy"


def test_search_no_result_is_empty(monkeypatch):
    capture(monkeypatch, keyword([]))
    res = PL.search("ㅁㄴㅇㄹqwerzxcv", KEY)
    assert res == PL.Result("empty", (), None)
    assert res.public() == {"status": "empty", "places": []}


def test_search_without_key_does_not_call(monkeypatch):
    monkeypatch.setattr(PL.urllib.request, "urlopen", lambda *a, **k: pytest.fail("키 없이 카카오를 불렀다"))
    assert PL.search(QUERY, None) == PL.unavailable("no_key")
    assert PL.search(QUERY, "") == PL.unavailable("no_key")


@pytest.mark.parametrize("exc, kind", [
    (urllib.error.HTTPError(PL.URL, 401, "Unauthorized", {}, None), "http_401"),
    (urllib.error.HTTPError(PL.URL, 403, "Forbidden", {}, None), "http_403"),       # 카카오맵 서비스가 꺼진 앱
    (urllib.error.HTTPError(PL.URL, 429, "Too Many Requests", {}, None), "http_429"),
    (urllib.error.URLError(ConnectionRefusedError(f"refused {KEY}")), "network"),
    (urllib.error.URLError(socket.timeout("timed out")), "timeout"),
    (socket.timeout("timed out"), "timeout"),
    (TimeoutError("timed out"), "timeout"),
    (ConnectionResetError(f"reset {KEY}"), "network"),
])
def test_search_failures_are_unavailable(monkeypatch, exc, kind):
    def fake_urlopen(req, timeout=None):
        raise exc

    monkeypatch.setattr(PL.urllib.request, "urlopen", fake_urlopen)
    assert PL.search(QUERY, KEY, near=(LAT, LNG)) == PL.unavailable(kind)


def test_search_deadline_cuts_slow_call():
    """urlopen의 timeout은 연결·읽기 한 번마다라 전체를 묶지 못한다 → search가 마감에서 끊는다."""
    def slow():
        time.sleep(1.0)
        return json.dumps(keyword(), ensure_ascii=False)

    t0 = time.perf_counter()
    assert PL.search(QUERY, KEY, timeout=0.1, fetch=slow) == PL.unavailable("timeout")
    assert time.perf_counter() - t0 < 0.8


def test_search_unexpected_exception_is_unavailable():
    def boom():
        raise KeyError("whatever")

    assert PL.search(QUERY, KEY, fetch=boom) == PL.unavailable("KeyError")


# ------------------------------------------------------------ GET /v1/places
@pytest.fixture
def api(monkeypatch):
    monkeypatch.setattr(M, "PLACES_KEY", KEY)
    return TestClient(M.app)


def q(**params) -> str:
    return "/v1/places?" + urllib.parse.urlencode(params)


def test_get_places_200_exact_shape(api, monkeypatch):
    seen = capture(monkeypatch, keyword())
    r = api.get(q(query=QUERY, lat=LAT, lng=LNG))
    assert r.status_code == 200
    assert r.json() == {"status": "ok", "places": EXPECTED}
    assert r.headers["cache-control"] == "no-store"   # URL에 검색어·좌표가 있으니 앱 HTTP 캐시에도 남기지 않는다
    assert dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(seen["url"]).query))["query"] == QUERY


def test_get_places_trims_query(api, monkeypatch):
    seen = capture(monkeypatch, keyword())
    assert api.get(q(query=f"  {QUERY} ")).status_code == 200
    assert dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(seen["url"]).query))["query"] == QUERY


def test_get_places_empty(api, monkeypatch):
    capture(monkeypatch, keyword([]))
    r = api.get(q(query="ㅁㄴㅇㄹ"))
    assert r.status_code == 200 and r.json() == {"status": "empty", "places": []}


@pytest.mark.parametrize("exc", [
    urllib.error.URLError(ConnectionRefusedError("refused")),        # 카카오가 내려감
    urllib.error.HTTPError(PL.URL, 500, "Internal Server Error", {}, None),
    socket.timeout("timed out"),
])
def test_get_places_failure_is_200_unavailable(api, monkeypatch, exc):
    def fake_urlopen(req, timeout=None):
        raise exc

    monkeypatch.setattr(PL.urllib.request, "urlopen", fake_urlopen)
    r = api.get(q(query=QUERY))
    assert r.status_code == 200 and r.json() == {"status": "unavailable", "places": []}


def test_get_places_over_deadline_is_unavailable(api, monkeypatch):
    def slow_urlopen(req, timeout=None):
        time.sleep(1.0)
        return body(keyword())

    monkeypatch.setattr(PL, "TIMEOUT_S", 0.1)
    monkeypatch.setattr(PL.urllib.request, "urlopen", slow_urlopen)
    t0 = time.perf_counter()
    r = api.get(q(query=QUERY))
    assert r.status_code == 200 and r.json() == {"status": "unavailable", "places": []}
    assert time.perf_counter() - t0 < 0.8


def test_get_places_without_key_is_unavailable(monkeypatch):
    monkeypatch.setattr(M, "PLACES_KEY", None)
    monkeypatch.setattr(PL.urllib.request, "urlopen", lambda *a, **k: pytest.fail("키 없이 카카오를 불렀다"))
    r = TestClient(M.app).get(q(query=QUERY))
    assert r.status_code == 200 and r.json() == {"status": "unavailable", "places": []}


@pytest.mark.parametrize("path", [
    q(query=QUERY, odometer_km=15037),     # 원시 차량값이 실려 오면 받지 않는다
    q(query=QUERY, vin="KMHXX00XXXX000000"),
    q(query=QUERY, lat=LAT, lon=LNG),      # 날씨의 lon과 헷갈린 경우도 막는다
    q(query=QUERY, x=LNG, y=LAT),
    q(query=QUERY, lat=LAT),               # lat·lng는 둘 다 또는 둘 다 없음
    q(query=QUERY, lng=LNG),
    q(query=""), q(query="   "), q(query="가" * 101),
    "/v1/places", q(lat=LAT, lng=LNG),
    q(query=QUERY, lat=91, lng=LNG), q(query=QUERY, lat=LAT, lng=181),
    q(query=QUERY, lat="nan", lng=LNG), q(query=QUERY, lat=LAT, lng="inf"),
])
def test_get_places_422(api, monkeypatch, path):
    monkeypatch.setattr(PL.urllib.request, "urlopen", lambda *a, **k: pytest.fail("422인데 카카오를 불렀다"))
    assert api.get(path).status_code == 422


def test_places_is_get_only(api):
    assert api.post("/v1/places", json={"query": QUERY}).status_code == 405


def test_places_log_has_no_query_coords_results_or_key(api, monkeypatch, capsys):
    """AC-N-4: 검색어·현재 위치·결과(이름·좌표·id)·키·URL을 로그에 남기지 않는다."""
    calls = iter([body(keyword()), body(keyword([])), urllib.error.URLError(ConnectionRefusedError(f"refused {KEY}"))])

    def fake_urlopen(req, timeout=None):
        nxt = next(calls)
        if isinstance(nxt, Exception):
            raise nxt
        return nxt

    monkeypatch.setattr(PL.urllib.request, "urlopen", fake_urlopen)
    capsys.readouterr()
    api.get(q(query=QUERY, lat=LAT, lng=LNG))
    api.get(q(query="없는곳"))
    api.get(q(query=QUERY))
    out = capsys.readouterr()
    text = out.out + out.err
    recs = [json.loads(ln) for ln in out.out.splitlines() if '"ev": "places"' in ln]
    assert [(r["result"], r["n"], r["near"], r.get("err")) for r in recs] == [
        ("ok", 2, True, None), ("empty", 0, False, None), ("unavailable", 0, False, "network")]
    assert all(set(r) <= {"t", "ev", "status", "ms", "result", "n", "near", "err"} for r in recs)
    for bad in (QUERY, "없는곳", str(LAT), str(LNG), "37.41", "127.09", "37.53", "127.31", "7845373", "2141791502",
                "양평", "관광", "23671", KEY, "dapi.kakao.com"):
        assert bad not in text


# ------------------------------------------------------------ 출발 미리보기 배경의 장소 종류(#116)
_FIX = Path(__file__).resolve().parents[2] / "fixtures"
PREVIEW_BG = json.loads((_FIX / "constants.json").read_text(encoding="utf-8"))["preview_bg"]
DEMO_SCENE = json.loads((_FIX / "effort/demo.json").read_text(encoding="utf-8"))["scene"]


def test_preview_bg_table_shape():
    """표의 값은 정해진 종류뿐이고, 검색 결과로는 home이 나오지 않는다(집은 내비 구분값). leaf와 upper는 겹치지 않는다."""
    kinds = set(PREVIEW_BG["kinds"])
    assert kinds == {"sea", "nature", "city", "home", "neutral"}
    for name in ("leaf", "upper"):
        assert set(PREVIEW_BG[name].values()) <= kinds - {"home", "neutral"}, name
        assert all(k == k.strip() and k and ">" not in k for k in PREVIEW_BG[name]), name
    assert not set(PREVIEW_BG["leaf"]) & set(PREVIEW_BG["upper"])
    # 한 값 안에 종류가 섞여 있어 일부러 뺀 값(constants _basis). 다시 넣으면 엉뚱한 그림이 나온다
    for mixed in ("국립공원", "국립공원 지구", "섬(내륙)", "KTX정차역", "도시근린공원", "전망대", "케이블카", "유원지", "공항", "관광,명소"):
        assert mixed not in PREVIEW_BG["leaf"] and mixed not in PREVIEW_BG["upper"], mixed


@pytest.mark.parametrize("ex", PREVIEW_BG["examples"], ids=lambda e: e["name"])
def test_preview_bg_kind_examples(ex):
    assert PL.preview_bg_kind(ex["category_path"], PREVIEW_BG) == ex["kind"]


def test_preview_bg_kind_rules():
    def k(*path):
        return PL.preview_bg_kind(list(path), PREVIEW_BG)
    assert k("여행", "관광,명소", "섬") == "sea"
    assert k("여행", "관광,명소", "섬", "섬(내륙)") == "neutral"            # 윗칸 '섬'으로 올라가 바다가 되면 안 된다
    assert k("교통,수송", "지하철,전철", "수도권2호선") == "city"            # 마지막 칸은 노선 이름, 윗칸으로 고른다
    assert k("교통,수송", "교통시설", "선착장,항만시설", "방파제") == "sea"
    assert k("음식점", "카페", "커피전문점") == "neutral" and k() == "neutral"
    assert PL.preview_bg_kind(None, PREVIEW_BG) == "neutral" and PL.preview_bg_kind(["산"], {}) == "neutral"
    # 서버가 실제로 내려 주는 category_path로도 같은 결과
    doc = dict(DOCS[0], category_name="여행 > 관광,명소 > 해수욕장,해변")
    out = PL.parse_keyword(json.dumps(keyword([doc]), ensure_ascii=False))
    assert PL.preview_bg_kind(out[0].category_path, PREVIEW_BG) == "sea"


def test_preview_bg_demo_kinds_are_known():
    """시연 데이터에 직접 적은 종류(effort/demo.json)도 같은 종류 이름을 쓴다."""
    assert set(DEMO_SCENE["preview_bg"]["kinds"].values()) <= set(PREVIEW_BG["kinds"])
