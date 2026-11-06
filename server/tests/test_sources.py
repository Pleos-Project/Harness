"""TourAPI 후보 캐시(sources.py). 네트워크 없음(fetch 주입)."""

from __future__ import annotations

import json

import pytest

from chagok import engine as E
from chagok import sources as S


def page(items, total=None, code="0000") -> str:
    return json.dumps({"response": {"header": {"resultCode": code, "resultMsg": "OK"},
                                    "body": {"items": {"item": items} if items else "", "totalCount": total if total is not None else len(items)}}})


def item(cid, title, lat, lng, image=""):
    return {"contentid": cid, "title": title, "mapy": str(lat), "mapx": str(lng), "addr1": "경기", "firstimage": image}


def test_parse_ok_empty_error():
    assert S.parse_area_based(page([item(1, "a", 37.5, 127.0)])) == (1, [item(1, "a", 37.5, 127.0)])
    assert S.parse_area_based(page([])) == (0, [])
    one = json.dumps({"response": {"header": {"resultCode": "0000"}, "body": {"items": {"item": item(2, "b", 37, 127)}, "totalCount": 1}}})
    assert S.parse_area_based(one)[1] == [item(2, "b", 37, 127)]   # 1건이면 리스트가 아니라 객체로 옴
    with pytest.raises(S.TourApiError):
        S.parse_area_based(page([], code="0022"))
    with pytest.raises(S.TourApiError):
        S.parse_area_based("<OpenAPI_ServiceResponse>SERVICE_KEY_IS_NOT_REGISTERED</OpenAPI_ServiceResponse>")


def test_refresh_pages_bbox_and_first_theme(tmp_path):
    calls = []

    def fetch(region, lcls3, pg):
        calls.append((region, lcls3, pg))
        if (region, lcls3) == ("41", "NA020200"):   # 호수 2쪽: 100 + 1
            if pg == 1:
                return 101, [item(i, f"호수{i}", 37.2, 127.2) for i in range(100)]
            return 101, [item(999, "고삼호수", 37.08, 128.0)]   # 경도 오류(실제 TourAPI 사례) → 버림
        if (region, lcls3) == ("41", "NA010100"):   # 산에도 같은 contentid 0 → 먼저 나온 테마(호수) 유지
            return 1, [item(0, "겹침", 37.2, 127.2, "x.jpg")]
        return 0, []

    con = S.connect(tmp_path / "c.sqlite3")
    res = S.refresh(con, fetch, regions=["41"], today="2026-11-05")
    assert res.saved == 100 and res.dropped_coords == 1
    assert ("41", "NA020200", 2) in calls and ("41", "NA020200", 3) not in calls
    assert res.calls == sum(len(v) for v in S.THEME_LCLS.values()) + 1
    assert con.execute("SELECT theme FROM candidate WHERE id='ta_0'").fetchone() == ("lake",)
    rows, when = S.load_rows(con)
    assert len(rows) == 100 and when == "2026-11-05"


def test_refresh_replaces_previous(tmp_path):
    con = S.connect(tmp_path / "c.sqlite3")
    S.refresh(con, lambda r, l, p: (1, [item(1, "a", 37.2, 127.2)]) if l == "NA020200" else (0, []), ["41"], "2026-11-05")
    S.refresh(con, lambda r, l, p: (1, [item(2, "b", 37.2, 127.2)]) if l == "NA020200" else (0, []), ["41"], "2026-11-06")
    assert [r[0] for r in S.load_rows(con)[0]] == ["ta_2"]


def test_refresh_error_keeps_old(tmp_path):
    con = S.connect(tmp_path / "c.sqlite3")
    S.refresh(con, lambda r, l, p: (1, [item(1, "a", 37.2, 127.2)]) if l == "NA020200" else (0, []), ["41"], "2026-11-05")

    def boom(r, l, p):
        raise S.TourApiError("x")
    with pytest.raises(S.TourApiError):
        S.refresh(con, boom, ["41"], "2026-11-06")
    assert S.load_rows(con) == ([("ta_1", "a", "lake", 37.2, 127.2)], "2026-11-05")


def test_estimate_formula_and_cutoff():
    o = (37.5, 127.0)
    near = ("n", "가까운", "park", 37.5, 127.2)     # 직선 약 17.7km
    far = ("f", "먼", "sea", 38.5, 127.0)           # 직선 약 111km → 계산 안 함
    [c] = S.estimate(*o, [near, far])
    s = S.haversine_km(*o, 37.5, 127.2)
    assert c.route_km == E.round1(s * E.ROAD_FACTOR)
    assert c.minutes == E.round_half_up(S.EST_BASE_MIN + S.EST_MIN_PER_KM * s)
    assert c.toll_krw == 0 and c.parking_krw is None and not c.has_parking_info


def test_regions_bbox_sane():
    for code, (_, (lat0, lat1, lng0, lng1)) in S.REGIONS.items():
        assert 33 < lat0 < lat1 < 39 and 124 < lng0 < lng1 < 130, code
    assert set(sum(S.THEME_LCLS.values(), [])) and len(S.THEME_LCLS) == 9 and "indoor" in S.THEME_LCLS


def fest(cid, title, start, end, lat=37.2, lng=127.2, region="41", lcls="EV010200"):
    return {"contentid": cid, "title": title, "mapy": str(lat), "mapx": str(lng), "addr1": "경기",
            "eventstartdate": start, "eventenddate": end, "lDongRegnCd": region, "lDongSignguCd": "135",
            "lclsSystm3": lcls}


def test_refresh_festivals_and_sigungu(tmp_path):
    con = S.connect(tmp_path / "c.sqlite3")
    place = dict(item(1, "호수", 37.2, 127.2), lDongRegnCd="41", lDongSignguCd="135")
    fests = [fest(10, "가을축제", "20261107", "20261109"),
             fest(11, "부산축제", "20261107", "20261109", region="26"),     # 6개 시도 밖 → 버림
             fest(12, "좌표오류", "20261107", "20261109", lng=128.0),      # 경기 상자 밖 → 버림
             fest(13, "날짜없음", "", "20261109")]
    res = S.refresh(con, lambda r, l, p: (1, [place]) if l == "NA020200" else (0, []), ["41"], "2026-11-06",
                    fetch_festivals=lambda p: (len(fests), fests))
    assert res.festivals == 1 and res.saved == 2
    meta = S.load_meta(con)
    assert meta["ta_10"] == S.Meta("EV010200", "41135", "2026-11-07", "2026-11-09")
    assert meta["ta_1"] == S.Meta("NA020200", "41135", None, None)
    [c] = [x for x in S.estimate(37.2, 127.1, S.load_rows(con)[0], meta) if x.id == "ta_10"]
    assert c.theme == "festival" and c.event_end == "2026-11-09" and c.lcls3 == "EV010200"


def test_connect_migrates_old_schema(tmp_path):
    import sqlite3
    p = tmp_path / "old.sqlite3"
    old = sqlite3.connect(p)
    old.executescript("""CREATE TABLE candidate (id TEXT PRIMARY KEY, contentid TEXT NOT NULL, name TEXT NOT NULL,
        theme TEXT NOT NULL, lcls3 TEXT NOT NULL, region TEXT NOT NULL, addr TEXT, lat REAL NOT NULL, lng REAL NOT NULL,
        image INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL);
        INSERT INTO candidate VALUES ('ta_1','1','a','lake','NA020200','41','',37.2,127.2,0,'2026-11-05');""")
    old.commit()
    old.close()
    con = S.connect(p)
    assert S.load_meta(con)["ta_1"] == S.Meta("NA020200", None, None, None)
    assert S.load_curation(con) == {}


def test_estimate_carries_curation():
    rows = [("ta_1", "포구", "sea", 37.5, 127.2)]
    meta = {"ta_1": S.Meta("NA020700", "28200", None, None)}
    cur = {"ta_1": S.Curation(5.0, "포구 산책", (4, 5))}
    [c] = S.estimate(37.5, 127.0, rows, meta, cur)
    assert (c.lcls3, c.reputation, c.intro, c.months) == ("NA020700", 5.0, "포구 산책", (4, 5))


def test_indoor_fetch_has_no_content_type(monkeypatch):
    seen = {}

    def fake_get(key, base, op, q):
        seen[q["lclsSystm3"]] = q.get("contentTypeId")
        return 0, []
    monkeypatch.setattr(S, "_get", fake_get)
    S.fetch_area_based("k", "b", "11", "VE070100", 1)
    S.fetch_area_based("k", "b", "11", "NA020200", 1)
    assert seen == {"VE070100": None, "NA020200": 12}
