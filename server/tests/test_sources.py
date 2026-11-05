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
    assert set(sum(S.THEME_LCLS.values(), [])) and len(S.THEME_LCLS) == 8
