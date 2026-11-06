"""블라인드 검수 도구(scripts/blind_review): 출처가 페이지에 새지 않는지, 판정 경계, LLM 응답 검증.
네트워크·LLM 호출 없음."""

from __future__ import annotations

import json
import random
import sys
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts" / "blind_review"))
import blind_review as B  # noqa: E402

from chagok import cards as C  # noqa: E402
from chagok import engine as E  # noqa: E402


def card(i: int, theme: str = "sea", name: str | None = None, lat: float = 37.0, lng: float | None = None) -> dict:
    c = E.Candidate(id=f"ta_{i}", name=name or f"장소{i}", theme=theme, lat=lat,
                    lng=lng if lng is not None else 126.0 + i * 0.05, route_km=40.0, minutes=50, toll_krw=0,
                    parking_krw=None, has_parking_info=False)
    req = B.d1_request(theme)
    return C.evaluate([c], C.budget_from(req), 1, [theme], [])[0]


# ------------------------------------------------------------ 테마 자동 선택
def test_pick_themes_one_per_group():
    counts = {"sea": 80, "lake": 65, "river": 16, "mountain": 195, "park": 636, "city": 156, "island": 38, "drive": 19}
    assert [t for t, _ in B.pick_themes(counts)] == ["sea", "park", "city"]


def test_pick_themes_skips_thin_group_and_fills_by_count():
    counts = {"sea": 10, "lake": 12, "river": 3, "island": 5, "mountain": 195, "park": 636, "city": 156, "drive": 19}
    picked = [t for t, _ in B.pick_themes(counts)]
    assert picked == ["park", "city", "mountain"]   # 물가 묶음은 30곳 미만 → 전체에서 많은 순


# ------------------------------------------------------------ LLM 응답 검증
def test_validate_picks_drops_unknown_duplicate_and_same_place():
    a = card(1, name="오이도")
    b = card(2, name="오이도 빨간등대", lat=a["lat"], lng=a["lng"])   # 이름 앞부분 겹침 = 같은 장소
    c = card(3)
    pool = {x["id"]: x for x in (a, b, c)}
    picks = [{"id": a["id"], "reason": "노을이 좋은 포구"}, {"id": "ta_999", "reason": "x"},
             {"id": a["id"], "reason": "중복"}, {"id": b["id"], "reason": "같은 곳"}, {"id": c["id"], "reason": " "},
             ]
    kept, dropped = B.validate_picks(picks, pool)
    assert [k["id"] for k in kept] == [a["id"]]
    assert [d["why"] for d in dropped] == ["후보 목록에 없는 id", "중복", "앞에서 고른 곳과 같은 장소", "이유 없음"]


def test_reason_flags():
    assert B.reason_flags("바다 보며 걷기 좋은 산책길") == []
    assert "금액·비용·주차 언급" in B.reason_flags("주차가 편한 해변")
    assert "시간·거리 숫자" in B.reason_flags("40분이면 닿는 바다")
    assert any("자 넘음" in f for f in B.reason_flags("가" * 41))


def test_candidate_table_is_name_sorted_not_rule_order():
    pool = [card(1, name="하늘포구"), card(2, name="가람해변"), card(3, name="나루터")]
    table = B.candidate_table(pool, {})
    assert [line.split(" | ")[1] for line in table.splitlines()] == ["가람해변", "나루터", "하늘포구"]


def test_prompt_template_has_all_slots():
    system, user, sha = B.load_prompt()
    assert system and len(sha) == 12
    req = B.d1_request("sea")
    out = B.render_user(user, req, 75.0, [card(1)], {})
    # D1 usable 195km → 편도 도로 97.5 → 98(반올림 0.5 올림). 75는 직선 반경(지도 원)
    assert "{" not in out and "테마: 바다" in out and "편도 도로 약 98km 안(지도 직선 반경 약 75km)" in out
    assert "편도 도로 약 98km" in B.condition_text(req)


# ------------------------------------------------------------ 섞기·페이지
def conditions_with_overlap():
    rule = [card(i) for i in range(1, 6)]
    llm_cards = [rule[0]] + [card(i) for i in range(11, 15)]   # 첫 곳은 두 쪽이 같이 고름
    llm = [{"llm_rank": i, "reason": f"LLM 이유 {i}", "card": c} for i, c in enumerate(llm_cards, 1)]
    return [{"theme": "sea", "text": "조건", "rule": rule, "llm": llm}]


def test_build_items_no_source_leak_and_overlap():
    data, key = B.build_items(conditions_with_overlap(), {}, random.Random(7))
    assert len(data["places"]) == 9            # 5 + 5 − 겹침 1
    assert len(data["reasons"]) == 10          # 이유는 쪽마다 하나
    blob = json.dumps(data, ensure_ascii=False)
    for leak in ("rule", "llm", "ta_", "rank", "source"):
        assert leak not in blob, leak
    both = [k for k in key["places"].values() if k["rule_rank"] and k["llm_rank"]]
    assert len(both) == 1 and both[0]["id"] == "ta_1"
    assert {v["source"] for v in key["reasons"].values()} == {"rule", "llm"}
    codes = [p["code"] for p in data["places"]] + [r["code"] for r in data["reasons"]]
    assert len(set(codes)) == len(codes)


def test_render_page_injects_data_safely():
    data, _ = B.build_items(conditions_with_overlap(), {}, random.Random(1))
    data["places"][0]["name"] = "</script><b>x"
    html = B.render_page(data)
    assert "/*__DATA__*/null" not in html
    assert "</script><b>x" not in html and "<\\/script><b>x" in html


# ------------------------------------------------------------ 집계·판정
def key_two_places():
    return {"places": {"P1": {"id": "a", "name": "a", "theme": "sea", "rule_rank": 1, "llm_rank": None},
                       "P2": {"id": "b", "name": "b", "theme": "sea", "rule_rank": None, "llm_rank": 1},
                       "P3": {"id": "c", "name": "c", "theme": "sea", "rule_rank": 2, "llm_rank": 2}},
            "reasons": {"R1": {"source": "rule", "place": "P1"}, "R2": {"source": "llm", "place": "P2"}}}


def test_tally_overlap_counts_both_sides():
    res = B.tally(key_two_places(), [{"rater": "u1", "place": {"P1": 2, "P2": 4, "P3": 3}, "reason": {}}])
    assert res["place"]["mean"]["rule"] == Fraction(5, 2)   # P1 2, P3 3
    assert res["place"]["mean"]["llm"] == Fraction(7, 2)    # P2 4, P3 3
    assert res["place"]["diff"] == 1 and res["verdict"] == "규칙 재검토"


def test_tally_threshold_is_strictly_less_than_half():
    key = key_two_places()
    # 규칙 평균 3, LLM 평균 3.5 → 차이 정확히 0.5 → 규칙 확정 아님
    r = [{"rater": "u1", "place": {"P1": 3, "P2": 4}, "reason": {}},
         {"rater": "u2", "place": {"P1": 3, "P2": 3}, "reason": {}}]
    res = B.tally(key, r)
    assert res["place"]["diff"] == Fraction(1, 2) and res["verdict"] == "규칙 재검토"
    r[1]["place"]["P2"] = 2   # LLM 평균 3 → 차이 0
    assert B.tally(key, r)["verdict"] == "규칙 확정"


def test_tally_ignores_bad_scores_unknown_codes_and_duplicate_raters():
    key = key_two_places()
    r = [{"rater": "u1", "place": {"P1": 5, "P2": 9, "ZZ": 1}, "reason": {"R1": 4, "R2": 2}},
         {"rater": "u1", "place": {"P1": 1}, "reason": {}}]
    res = B.tally(key, r)
    assert res["place"]["n"] == {"rule": 1, "llm": 0} and res["verdict"] is None
    assert res["duplicates"] == ["u1"]
    assert res["reason"]["mean"] == {"rule": 4, "llm": 2}


def test_norm_raters_accepts_db_list_and_copied_json():
    db_list = {"docs": [{"id": "uid-1", "data": {"place": {"P1": 3}, "reason": {}, "part1_done": True}}]}
    copied = {"rater": "명준", "place": {"P2": 4}, "reason": {"R2": 5}}
    raters = B._norm_raters([db_list, copied])
    assert [r["rater"] for r in raters] == ["uid-1", "명준"]
    md = B.result_markdown(B.tally(key_two_places(), raters), key_two_places())
    assert "판정" in md and "평가자가 PRD의 5명보다 적다(2명)" in md
