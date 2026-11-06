"""블라인드 검수 도구(scripts/blind_review): A(A1)·B(A1+A2) 섞기, 출처가 페이지에 새지 않는지, 판정 경계.
네트워크·LLM 호출 없음."""

from __future__ import annotations

import json
import random
import sys
from datetime import datetime
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "blind_review"))
import blind_review as B  # noqa: E402

from chagok import cards as C  # noqa: E402
from chagok import engine as E  # noqa: E402


def card(i: int, theme: str = "sea", reason: str | None = None, **kw) -> dict:
    c = E.Candidate(id=f"ta_{i}", name=f"장소{i}", theme=theme, lat=37.0, lng=126.0 + i * 0.05, route_km=40.0,
                    minutes=50, lcls3="NA020900", **kw)
    req = B.d1_request([theme])
    ctx = E.RankContext(cfg=C.RANKING, variant="a1")
    out = C.evaluate([c], C.budget_from(req), 1, [theme], [], ctx)[0]
    if reason:
        out["reason"] = reason
    return out


def conditions_with_overlap():
    a = [card(i) for i in range(1, 6)]
    b = [card(1, reason="소개 1")] + [card(i, reason=f"소개 {i}") for i in range(11, 15)]   # 첫 곳은 같이 고름
    return [{"key": "sea", "label": "바다", "text": "조건", "a": a, "b": b}]


def test_build_items_no_source_leak_and_overlap():
    data, key = B.build_items(conditions_with_overlap(), {}, random.Random(7))
    assert len(data["places"]) == 9            # 5 + 5 − 겹침 1
    assert len(data["reasons"]) == 10          # 겹친 곳은 이유 문장이 달라 쪽마다
    blob = json.dumps(data, ensure_ascii=False)
    for leak in ("a_rank", "b_rank", "ta_", "sides", '"a"', '"b"'):
        assert leak not in blob, leak
    both = [k for k in key["places"].values() if k["a_rank"] and k["b_rank"]]
    assert len(both) == 1 and both[0]["id"] == "ta_1"
    codes = [p["code"] for p in data["places"]] + [r["code"] for r in data["reasons"]]
    assert len(set(codes)) == len(codes)


def test_same_reason_text_asked_once_and_counted_for_both():
    a = [card(1)]
    b = [card(1)]                              # B에 A2 소개가 없으면 이유 문장이 같다
    data, key = B.build_items([{"key": "sea", "label": "바다", "text": "조건", "a": a, "b": b}], {}, random.Random(1))
    assert len(data["places"]) == 1 and len(data["reasons"]) == 1
    assert list(key["reasons"].values())[0]["sides"] == ["a", "b"]


def test_place_view_shows_same_fields_for_both_sides():
    c = card(1)
    v = B.place_view(c, "경기 화성시")
    assert v["region"] == "경기 화성시 · 해변" and "편도 50분" in v["time_text"]
    ev = card(2, theme="festival", event_start="2026-11-07", event_end="2026-11-09")
    assert B.place_view(ev, "")["region"].endswith("11/07~11/09")


def test_render_page_injects_data_safely():
    data, _ = B.build_items(conditions_with_overlap(), {}, random.Random(1))
    data["places"][0]["name"] = "</script><b>x"
    html = B.render_page(data)
    assert "/*__DATA__*/null" not in html
    assert "</script><b>x" not in html and "<\\/script><b>x" in html


def test_conditions_and_text():
    assert [c["key"] for c in B.CONDITIONS] == ["sea", "park", "city", "festival", "rain"]
    req = B.d1_request([])
    assert B.one_way_km(req) == 98                       # D1 usable 195 → 97.5 → 98(0.5 올림)
    txt = B.condition_text(B.CONDITIONS[-1], req, "2026-11-07")
    assert "테마 없음" in txt and "비 예보(가정)" in txt and "편도 도로 약 98km" in txt
    rain_ctx = B.ctx_for(B.CONDITIONS[-1], "b", "2026-11-07", ["x", "y"])
    assert rain_ctx.rain == {"x": 1.0, "y": 1.0} and rain_ctx.variant == "b"
    assert B.ctx_for(B.CONDITIONS[0], "a", "2026-11-07", ["x"]).rain == {}
    assert B.default_date(datetime(2026, 11, 6, tzinfo=B.KST)) == "2026-11-07"     # 금 → 토
    assert B.default_date(datetime(2026, 11, 7, tzinfo=B.KST)) == "2026-11-07"     # 토 → 그날


# ------------------------------------------------------------ 집계·판정
def key_three_places():
    return {"places": {"P1": {"id": "a", "name": "a", "theme": "sea", "a_rank": 1, "b_rank": None},
                       "P2": {"id": "b", "name": "b", "theme": "sea", "a_rank": None, "b_rank": 1},
                       "P3": {"id": "c", "name": "c", "theme": "sea", "a_rank": 2, "b_rank": 2}},
            "reasons": {"R1": {"sides": ["a"], "place": "P1"}, "R2": {"sides": ["b"], "place": "P2"},
                        "R3": {"sides": ["a", "b"], "place": "P3"}}}


def test_tally_overlap_counts_both_sides():
    res = B.tally(key_three_places(), [{"rater": "u1", "place": {"P1": 2, "P2": 4, "P3": 3}, "reason": {"R3": 5}}])
    assert res["place"]["mean"] == {"a": Fraction(5, 2), "b": Fraction(7, 2)}
    assert res["place"]["diff"] == 1 and res["verdict"] == "A2 도입(B)"
    assert res["reason"]["mean"] == {"a": 5, "b": 5} and res["intro_verdict"] == "규칙 이유 유지"


def test_tally_threshold_half_point_goes_to_b():
    key = key_three_places()
    r = [{"rater": "u1", "place": {"P1": 3, "P2": 4}, "reason": {}},
         {"rater": "u2", "place": {"P1": 3, "P2": 3}, "reason": {}}]
    res = B.tally(key, r)
    assert res["place"]["diff"] == Fraction(1, 2) and res["verdict"] == "A2 도입(B)"   # 0.5 미만이어야 규칙 확정
    r[1]["place"]["P2"] = 2
    assert B.tally(key, r)["verdict"] == "A1만(규칙 확정)"


def test_tally_ignores_bad_scores_unknown_codes_and_duplicate_raters():
    r = [{"rater": "u1", "place": {"P1": 5, "P2": 9, "ZZ": 1}, "reason": {"R1": 4, "R2": 2}},
         {"rater": "u1", "place": {"P1": 1}, "reason": {}}]
    res = B.tally(key_three_places(), r)
    assert res["place"]["n"] == {"a": 1, "b": 0} and res["verdict"] is None
    assert res["duplicates"] == ["u1"]
    assert res["reason"]["mean"] == {"a": 4, "b": 2}


def test_norm_raters_accepts_db_list_and_copied_json():
    db_list = {"docs": [{"id": "uid-1", "data": {"place": {"P1": 3}, "reason": {}, "part1_done": True}}]}
    copied = {"rater": "명준", "place": {"P2": 4}, "reason": {"R2": 5}}
    raters = B._norm_raters([db_list, copied])
    assert [r["rater"] for r in raters] == ["uid-1", "명준"]
    md = B.result_markdown(B.tally(key_three_places(), raters), key_three_places(), {"sea": "바다"})
    assert "장소 판정" in md and "| 바다 |" in md and "평가자가 PRD의 5명보다 적다(2명)" in md
