"""실서비스 모드(demo_seed 없음): TourAPI 캐시 후보 + 상위 후보만 실경로. 네트워크 없음(경로 fetch 주입)."""

from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from chagok import cards as C
from chagok import engine as E
from chagok import live
from chagok import main as M
from chagok import routes as R
from chagok import sources as S

FIX = Path(__file__).resolve().parents[2] / "fixtures"
REQ = json.loads((FIX / "cards" / "D1.json").read_text(encoding="utf-8"))["request"]
O = REQ["origin"]
THEMES = list(S.THEME_LCLS)


def live_req(**kw) -> dict:
    r = copy.deepcopy(REQ)
    r.pop("demo_seed")
    r.update(kw)
    return r


def make_db(path: Path, n: int = 40, day: str = "2026-11-05") -> Path:
    """출발지 주변 n곳(동쪽으로 1.5km 간격, 테마 순환). 40곳이면 추정 20~79분 → '조금 먼 곳'도 생김."""
    con = S.connect(path)
    with con:
        con.execute("DELETE FROM candidate")
        for i in range(n):
            con.execute("INSERT INTO candidate VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        (f"ta_{i:03d}", str(i), f"장소{i}", THEMES[i % len(THEMES)], "X", "41", "",
                         O["lat"], O["lng"] + 0.017 * (i + 1), 0, day))
        con.execute("INSERT OR REPLACE INTO meta VALUES ('tourapi_refreshed', ?)", (day,))
    con.close()
    return path


class FakeRoute:
    """직선 × 1.2 km, 직선 × 0.9 + 10 분, 통행료 1,000원. 호출 기록."""

    def __init__(self, fail: bool = False) -> None:
        self.calls: list[tuple[float, float]] = []
        self.fail = fail

    def __call__(self, olat, olng, dlat, dlng) -> R.Route:
        self.calls.append((dlat, dlng))
        if self.fail:
            raise R.RouteError("fail")
        s = S.haversine_km(olat, olng, dlat, dlng)
        return R.Route(route_km=E.round1(s * 1.2), minutes=E.round_half_up(s * 0.9 + 10), toll_krw=1000)


@pytest.fixture()
def db(tmp_path) -> Path:
    return make_db(tmp_path / "c.sqlite3")


def rows_of(db: Path):
    return live.CandidateIndex(db).rows()[0]


def est_pool(db: Path, req: dict | None = None) -> list[dict]:
    return C._ranked(req or live_req(), live.estimates(req or live_req(), rows_of(db)))[1]


# ------------------------------------------------------------ candidates_for
def test_ranks_only_on_real_routes(db):
    """추정으로 N곳을 골라 실경로로 바꾸고, 순위는 실경로 후보끼리만(못 바꾼 추정값이 1위로 올라오지 않게)."""
    fetch = FakeRoute()
    cands, refined, st = live.candidates_for(live_req(), rows_of(db), fetch=fetch, cache=R.RouteCache())
    assert st.ranked_on == "route"
    assert st.pool == 40 and st.in_range == len(est_pool(db))
    assert st.calls == len(fetch.calls) == len(refined) == live.REFINE_WINDOW
    assert cands == list(refined.values()) and all(c.toll_krw == 1000 for c in cands)


def test_window_mixes_far_and_themes(db):
    pool = est_pool(db)
    ids = live.window(pool)
    by = {c["id"]: c for c in pool}
    head = live.REFINE_WINDOW - live.FAR_IN_WINDOW - 2
    assert len(ids) == len(set(ids)) == live.REFINE_WINDOW
    assert ids[:head] == [c["id"] for c in pool[:head]]
    assert any(by[i]["time_label"] == "조금 먼 곳" for i in ids)
    assert len({by[i]["theme"] for i in ids}) == len({c["theme"] for c in pool})


def test_no_key_uses_estimate(db):
    cands, refined, st = live.candidates_for(live_req(), rows_of(db), fetch=None, cache=R.RouteCache())
    assert st.calls == 0 and refined == {} and st.ranked_on == "estimate"
    assert cands == live.estimates(live_req(), rows_of(db))


def test_all_fail_falls_back_to_estimate(db):
    """카카오가 전부 실패해도 카드는 나온다(추정값 전체로 순위)."""
    fetch = FakeRoute(fail=True)
    cands, refined, st = live.candidates_for(live_req(), rows_of(db), fetch=fetch, cache=R.RouteCache())
    assert refined == {} and st.ranked_on == "estimate"
    assert cands == live.estimates(live_req(), rows_of(db))
    assert st.failures == st.calls == len(fetch.calls) > 0


def test_cache_shared_across_requests(db):
    fetch, cache = FakeRoute(), R.RouteCache()
    live.candidates_for(live_req(), rows_of(db), fetch=fetch, cache=cache)
    n = len(fetch.calls)
    _, refined, st = live.candidates_for(live_req(), rows_of(db), fetch=fetch, cache=cache)
    assert len(fetch.calls) == n and st.hits == live.REFINE_WINDOW and st.calls == 0
    assert len(refined) == live.REFINE_WINDOW


def test_keep_ids_refined(db):
    far = "ta_039"   # 창 밖이어도 세션 카드면 실경로로 받아 순위에 남긴다
    assert far not in live.window(est_pool(db))
    cands, refined, _ = live.candidates_for(live_req(), rows_of(db), fetch=FakeRoute(), cache=R.RouteCache(),
                                            keep_ids=[far])
    assert far in refined and far in [c.id for c in cands]


def test_session_refined_not_refetched(db):
    fetch = FakeRoute()
    _, refined, _ = live.candidates_for(live_req(), rows_of(db), fetch=fetch, cache=R.RouteCache())
    n = len(fetch.calls)
    _, again, st = live.candidates_for(live_req(), rows_of(db), fetch=fetch, cache=R.RouteCache(), refined=refined)
    assert len(fetch.calls) == n and st.calls == 0 and again == refined


def test_index_reloads_after_refresh(tmp_path):
    p = make_db(tmp_path / "c.sqlite3", n=5, day="2026-11-05")
    t = [0.0]
    idx = live.CandidateIndex(p, check_s=60, clock=lambda: t[0])
    assert len(idx.rows()[0]) == 5
    make_db(p, n=8, day="2026-11-06")
    t[0] = 30
    assert len(idx.rows()[0]) == 5          # 60초 전엔 다시 안 읽음
    t[0] = 61
    rows, when = idx.rows()
    assert len(rows) == 8 and when == "2026-11-06"


def test_index_missing_db_is_empty_and_not_created(tmp_path):
    p = tmp_path / "nope" / "c.sqlite3"
    assert live.CandidateIndex(p).rows() == ([], None)
    assert not p.parent.exists()


# ------------------------------------------------------------ API
@pytest.fixture()
def api(db, monkeypatch):
    fetch = FakeRoute()
    monkeypatch.setattr(M, "INDEX", live.CandidateIndex(db))
    monkeypatch.setattr(M, "KAKAO", fetch)
    monkeypatch.setattr(M, "ROUTE_CACHE", R.RouteCache())
    monkeypatch.setattr(M, "POOLS", live.LivePools())
    return TestClient(M.app), fetch


def test_live_cards_recall_and_swap(api, db):
    client, fetch = api
    r = client.post("/v1/cards", json=live_req())
    assert r.status_code == 200
    res = r.json()
    assert res["picked"]["id"].startswith("ta_")
    assert res["candidates_in_range"] == len(est_pool(db)) > live.REFINE_WINDOW   # 추정 기준 전체 수
    n = len(fetch.calls)
    # 재호출(같은 세션, 잔량만 바뀜): 같은 카드·같은 경로, 이미 받은 곳은 다시 안 부름
    again = live_req(session_id=res["session_id"])
    again["budget"]["usable_km"] = 120.0
    r2 = client.post("/v1/cards", json=again).json()
    assert r2["session_id"] == res["session_id"] and r2["picked"]["id"] == res["picked"]["id"]
    assert r2["picked"]["route_km"] == res["picked"]["route_km"]
    assert len(set(fetch.calls)) == len(fetch.calls) >= n
    # 교체: 같은 실경로 후보 안에서, 화면 스택 순서대로
    s = client.post("/v1/cards/swap", json={"session_id": res["session_id"]})
    assert s.status_code == 200
    card = s.json()["card"]
    assert card["id"].startswith("ta_") and card["id"] != res["picked"]["id"]
    assert card["id"] in [c["id"] for c in r2["stack"]]


def test_demo_seed_still_demo(api):
    client, fetch = api
    res = client.post("/v1/cards", json=REQ).json()
    assert not res["picked"]["id"].startswith("ta_") and fetch.calls == []
    s = client.post("/v1/cards/swap", json={"session_id": res["session_id"]})
    assert s.status_code == 200 and not s.json()["card"]["id"].startswith("ta_")


def test_empty_cache_503(tmp_path, monkeypatch):
    monkeypatch.setattr(M, "INDEX", live.CandidateIndex(tmp_path / "none.sqlite3"))
    r = TestClient(M.app).post("/v1/cards", json=live_req())
    assert r.status_code == 503 and r.json()["detail"]["error"] == "candidates_not_ready"


def test_logs_have_no_location(api, capsys):
    client, _ = api
    capsys.readouterr()
    res = client.post("/v1/cards", json=live_req()).json()
    client.post("/v1/cards/swap", json={"session_id": res["session_id"]})
    out = capsys.readouterr().out
    lines = [json.loads(x) for x in out.splitlines() if x.startswith("{")]
    assert [x["ev"] for x in lines] == ["cards", "swap"]
    assert lines[0]["mode"] == "live" and lines[0]["route_calls"] > 0 and lines[0]["ranked_on"] == "route"
    for bad in (str(O["lat"]), str(O["lng"]), res["session_id"], res["picked"]["id"], res["picked"]["name"]):
        assert bad not in out


def test_health_reports_live(api):
    client, _ = api
    h = client.get("/v1/health").json()
    assert h["live_candidates"] == 40 and h["tourapi_refreshed"] == "2026-11-05" and h["kakao"] is True


def test_window_skips_same_place():
    def card(i, name, lat, lng, theme="park", label="75분 안"):
        return {"id": i, "name": name, "lat": lat, "lng": lng, "theme": theme, "time_label": label}
    pool = [card("a", "오동근린공원", 37.30, 127.00), card("b", "오동근린공원 유아숲체험장", 37.31, 127.00, "mountain"),
            card("c", "다른공원", 37.40, 127.00), card("d", "옆 건물", 37.4001, 127.0001, "city"),
            card("e", "먼 바다", 37.60, 127.00, "sea", "조금 먼 곳")]
    # 상위 1(a) → 조금 먼 곳(e) → 없는 테마(d, city). b는 a와 이름 겹침, c는 d와 0.3km 안 → 빠짐
    assert live.window(pool, n=5) == ["a", "e", "d"]
