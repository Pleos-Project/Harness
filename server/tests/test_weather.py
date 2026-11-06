"""날씨 캐시(weather.py). 네트워크 없음(fetch 주입)."""

from __future__ import annotations

import json
from datetime import datetime

import pytest

from chagok import sources as S
from chagok import weather as W


def hours(rain_at=(), pop=None, maybe_at=()):
    """9~18시 1시간 단위. rain_at 시각은 PTY 1, maybe_at 시각은 강수확률 40."""
    out = {}
    for h in range(6, 21):
        t = f"{h:02d}00"
        out[t] = {"POP": str(pop if pop is not None else (40 if h in maybe_at else 10)), "PTY": "1" if h in rain_at else "0"}
    return out


def test_grid_matches_kma_reference_points():
    assert W.to_grid(37.5665, 126.9780) == (60, 127)       # 서울시청(기상청 격자표)
    assert W.to_grid(37.4127, 127.0947) == (62, 123)       # D1 출발지 — 11/6 실호출로 확인한 격자


def test_rain_level_rule():
    assert W.rain_level(hours())[0] == 0.0
    assert W.rain_level(hours(rain_at=(10, 11, 12)))[0] == 1.0
    assert W.rain_level(hours(rain_at=(10,)))[0] == 0.5
    assert W.rain_level(hours(maybe_at=(9, 10, 11)))[0] == 0.5
    assert W.rain_level(hours(maybe_at=(9, 10)))[0] == 0.0
    assert W.rain_level(hours(rain_at=(6, 7, 19, 20)))[0] == 0.0         # 나들이 시간대 밖
    assert W.rain_level(hours(pop=60))[:2] == (1.0, 10)                  # 강수확률 60%도 비로 셈


def test_trip_date_and_base():
    assert W.trip_date(datetime(2026, 11, 7, 17, 59, tzinfo=W.KST)) == "2026-11-07"
    assert W.trip_date(datetime(2026, 11, 7, 18, 0, tzinfo=W.KST)) == "2026-11-08"
    assert W.latest_base(datetime(2026, 11, 7, 14, 20, tzinfo=W.KST)) == ("20261107", "1400")
    assert W.latest_base(datetime(2026, 11, 7, 14, 10, tzinfo=W.KST)) == ("20261107", "1100")
    assert W.latest_base(datetime(2026, 11, 7, 2, 5, tzinfo=W.KST)) == ("20261106", "2300")


def test_parse_forecast_and_error():
    body = {"response": {"header": {"resultCode": "00"}, "body": {"items": {"item": [
        {"fcstDate": "20261108", "fcstTime": "1000", "category": "POP", "fcstValue": "60"},
        {"fcstDate": "20261108", "fcstTime": "1000", "category": "PTY", "fcstValue": "1"}]}}}}
    assert W.parse_forecast(json.dumps(body)) == {"2026-11-08": {"1000": {"POP": "60", "PTY": "1"}}}
    with pytest.raises(W.WeatherError):
        W.parse_forecast(json.dumps({"response": {"header": {"resultCode": "30"}}}))


def make_db(path):
    con = W.connect(path)
    with con:
        for cid, sg, lat, lng in [("ta_1", "41135", 37.40, 127.10), ("ta_2", "41135", 37.42, 127.12),
                                  ("ta_3", "11110", 37.57, 126.98), ("ta_4", None, 37.26, 127.03)]:
            con.execute("INSERT INTO candidate (id, contentid, name, theme, lcls3, region, addr, lat, lng, image, "
                        "updated_at, sigungu) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                        (cid, cid, cid, "park", "VE030100", "41", "", lat, lng, 0, "2026-11-06", sg))
    return con


def test_refresh_and_index(tmp_path):
    con = make_db(tmp_path / "w.sqlite3")
    grids = W.candidate_grids(con)
    assert grids["ta_1"] == grids["ta_2"]                 # 같은 시군구 = 같은 대표 지점
    assert len(set(grids.values())) == 3
    seen = []

    def fetch(nx, ny, base):
        seen.append((nx, ny, base))
        if f"{nx},{ny}" == grids["ta_3"]:
            raise W.WeatherError("x")                     # 실패한 격자는 건너뜀
        return {"2026-11-08": hours(rain_at=(10, 11, 12)), "2026-11-07": hours()}
    res = W.refresh(con, fetch, now=datetime(2026, 11, 7, 14, 20, tzinfo=W.KST))
    assert (res.grids, res.calls, res.failures, res.days) == (3, 3, 1, 2)
    assert {b for *_, b in seen} == {("20261107", "1400")}
    idx = W.WeatherIndex(tmp_path / "w.sqlite3")
    assert idx.rain_map("2026-11-08") == {"ta_1": 1.0, "ta_2": 1.0, "ta_4": 1.0}
    assert idx.rain_map("2026-11-07") == {}
    assert W.WeatherIndex(tmp_path / "none.sqlite3").rain_map("2026-11-08") == {}


def test_refresh_keeps_failed_grid_previous_value(tmp_path):
    con = make_db(tmp_path / "w.sqlite3")
    W.refresh(con, lambda nx, ny, b: {"2026-11-08": hours(rain_at=(10,))}, now=datetime(2026, 11, 7, 9, tzinfo=W.KST))

    def boom(nx, ny, b):
        raise W.WeatherError("x")
    W.refresh(con, boom, now=datetime(2026, 11, 7, 12, tzinfo=W.KST))
    assert set(W.WeatherIndex(tmp_path / "w.sqlite3").rain_map("2026-11-08").values()) == {0.5}
    assert S.load_rows(con)[0]   # 후보 표는 그대로
