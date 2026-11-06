"""A1 규칙(목적지성·날씨·행사)과 B(A2 평판) — fixtures/ranking/R*.json. 앱 Kotlin FixturesTest도 같은 파일을 읽는다.

1부: 가중표 값을 손으로 검산한 기대값(엔진 출력과 독립)
2부: fixture 전수 — 같은 후보·조건 → 같은 응답·순위
3부: 데모 고정 모드는 A1·B 어느 쪽이든 기존 응답 그대로(PT·fixtures/cards 고정)
"""

from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest

from chagok import cards as C
from chagok import engine as E

ROOT = Path(__file__).resolve().parents[2]
RANKS = sorted((ROOT / "fixtures/ranking").glob("R*.json"), key=lambda p: int(p.stem[1:]))
CFG = C.RANKING


def cand(**kw) -> E.Candidate:
    base = dict(id="x", name="장소", theme="sea", lat=37.5, lng=127.0, route_km=40.0, minutes=50)
    return E.Candidate(**(base | kw))


# ------------------------------------------------------------ 1부: 손 검산
def test_config_matches_agreed_skeleton():
    assert CFG.weights == {"quality": 0.30, "weather": 0.30, "reputation": 0.30}
    assert CFG.quality["NA020900"] == 1.0 and CFG.quality["NA020700"] == 0.6 and CFG.quality["VE030400"] == 0.2
    assert CFG.quality_default == 0.5 and CFG.indoor_clear == 0.5 and CFG.reputation_missing == 0.25


def test_quality_penalties():
    assert E.quality(cand(lcls3="NA020900"), CFG) == 1.0
    assert E.quality(cand(lcls3="NA020700", name="작은선착장"), CFG) == 0.6 * 0.5
    assert E.quality(cand(lcls3=None), CFG) == 0.5                     # 데모 후보: 중립
    assert E.quality(cand(lcls3="ZZ999999"), CFG) == 0.5               # 표에 없는 소분류: 중립
    long_ev = cand(lcls3="EV010100", event_start="2026-04-01", event_end="2026-12-31")
    assert E.quality(long_ev, CFG) == 1.0 * 0.6
    short_ev = cand(lcls3="EV010100", event_start="2026-11-01", event_end="2026-12-30")   # 59일
    assert E.quality(short_ev, CFG) == 1.0


def test_weather_fit_table():
    out, ind = cand(lcls3="NA020900"), cand(lcls3="VE070500")
    assert [E.weather_fit(out, CFG, r) for r in (0.0, 0.5, 1.0)] == [1.0, 0.5, 0.0]
    assert [E.weather_fit(ind, CFG, r) for r in (0.0, 0.5, 1.0)] == [0.5, 0.75, 1.0]


def test_reputation_fit():
    assert E.reputation_fit(cand(reputation=5.0), CFG, 11) == 1.0
    assert E.reputation_fit(cand(reputation=None), CFG, 11) == 0.25
    assert E.reputation_fit(cand(reputation=4.0, months=(6, 7, 8)), CFG, 11) == 0.75 * 0.6
    assert E.reputation_fit(cand(reputation=4.0, months=(6, 7, 8)), CFG, 7) == 0.75


def test_exclusions_and_event_dates():
    assert E.excluded(cand(name="모래 놀이터", lcls3="VE030100"), CFG)
    assert E.excluded(cand(lcls3="EV020700"), CFG)                     # 공연
    assert E.excluded(cand(lcls3="EV030300"), CFG)                     # 스포츠경기
    assert not E.excluded(cand(name="양평 두물머리"), CFG)
    ev = cand(event_start="2026-11-09", event_end="2026-11-10")
    assert not E.event_active(ev, "2026-11-08", 1) and E.event_active(ev, "2026-11-08", 2)
    assert E.event_active(ev, None, 1) and E.event_active(cand(), "2026-11-08", 1)


def test_worked_examples_from_skeleton():
    """뼈대 설명의 계산 예: 같은 조건(추가 0원 · 테마 고름)에서 점수 차이."""
    b = E.Budget("ev", 195.0, 50.0, 5.27, 300)
    ctx = E.RankContext(cfg=CFG)

    def total(c):
        badge = E.compute_badge(c, b)
        return E.weighted(E.score(c, badge, ["sea"], [], ctx), CFG)
    beach_tight = cand(lcls3="NA020900", route_km=90.0, minutes=60)      # 왕복 180 ≥ 195×0.9 → 빠듯
    park_near = cand(lcls3="VE030400", route_km=30.0, minutes=40)
    assert total(beach_tight) - total(park_near) == pytest.approx(0.24 - 0.15)
    beach_far = cand(lcls3="NA020900", minutes=85)
    lake_near = cand(lcls3="NA020200", minutes=40)
    assert total(lake_near) > total(beach_far)                          # 목적지성 +0.06 < 시간 −0.17


# ------------------------------------------------------------ 2부: fixture
def replay(fx: dict) -> tuple[dict, list[dict]]:
    ctxd = fx["context"]
    ctx = E.RankContext(cfg=CFG, rain=ctxd["rain"], trip_date=ctxd["trip_date"], variant=ctxd["variant"])
    objs = [E.Candidate(**{k: (tuple(v) if k == "months" and v else v) for k, v in c.items()}) for c in fx["candidates"]]
    store = C.SessionStore(ids=iter(f"s{i}" for i in range(1, 99)).__next__)
    res = C.build_cards(copy.deepcopy(fx["request"]), objs, store, ctx)
    if res.get("session_id"):
        res["session_id"] = "<session>"
    _, pool, _ = C._ranked(fx["request"], objs, ctx)
    return res, [{"id": c["id"], "score": c["_score"], "reason": c["reason"]} for c in pool]


@pytest.mark.parametrize("path", RANKS, ids=lambda p: p.stem)
def test_ranking_fixture(path):
    fx = json.loads(path.read_text(encoding="utf-8"))
    res, ranked = replay(fx)
    assert ranked == fx["expected_ranked"]
    assert res == fx["expected"]


def test_ranking_fixtures_cover_the_rules():
    fx = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in RANKS}
    order = lambda k: [r["id"] for r in fx[k]["expected_ranked"]]  # noqa: E731
    assert order("R1")[0] == "r_beach" and order("R1")[-1] == "r_pier"
    assert order("R2")[:2] == ["r_science", "r_museum"] and "r_playground" not in order("R2")
    assert "비 와도 실내" in fx["R2"]["expected_ranked"][0]["reason"]
    assert order("R3")[:2] == ["r_arboretum", "r_beach"] or order("R3")[:2] == ["r_beach", "r_arboretum"]
    assert order("R4") == ["f_active", "f_long"]
    assert "f_tomorrow" in order("R5")
    assert order("R6")[0] == "r_port_famous" and fx["R6"]["expected"]["picked"]["reason"].startswith("갓 잡은")
    assert order("R7")[-1] == "r_port_famous"
    assert fx["R8"]["expected"]["hint"]["code"] == "rain_outdoor"


# ------------------------------------------------------------ 3부: 데모는 그대로
@pytest.mark.parametrize("fid", ["D1", "D2", "D3", "D4", "D9", "D10"])
@pytest.mark.parametrize("variant", C.VARIANTS)
def test_demo_mode_unchanged_by_ranking(fid, variant):
    fx = json.loads((ROOT / f"fixtures/cards/{fid}.json").read_text(encoding="utf-8"))
    if "request" not in fx or "session_from" in fx:
        pytest.skip("재호출 fixture는 test_fixtures가 본다")
    cands = C.load_demo_candidates()
    plain = C.build_cards(copy.deepcopy(fx["request"]), cands, C.SessionStore(ids=lambda: "s"))
    ranked = C.build_cards(copy.deepcopy(fx["request"]), cands, C.SessionStore(ids=lambda: "s"), C.demo_ctx(variant))
    assert ranked == plain
