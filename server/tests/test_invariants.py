"""PRD 5-3 표기 규칙·F2/F3 경계를 '모든 상태'에 대해 검사한다.

fixture 비교는 "엔진이 어제와 같은가"만 본다. 여기는 "엔진이 문서 규칙을 지키는가"를 본다.
잔고·프리셋·테마·인원·1박·차종을 훑어 화면에 나갈 수 있는 모든 카드에 규칙을 건다.
"""

import itertools
import re

import pytest

from chagok import engine as E
from chagok.cards import SessionStore, build_cards, load_constants, load_demo_candidates, swap

CONST = load_constants()
CANDS = load_demo_candidates()
ORIGIN = {"lat": CONST["origin"]["lat"], "lng": CONST["origin"]["lng"]}
ZERO_KRW = re.compile(r"(^|[^0-9])0 원")
FORBIDDEN = ("글레오", "예상 vs 실제", "예상과 실제")


def states():
    themes_opts = [[], ["sea"], ["river"], ["lake", "mountain"], ["island", "drive"]]
    for model, rr_km, extra, themes, seats, days in itertools.product(
            ["ioniq5_lr", "avante_hev", "grandeur_25"], range(40, 521, 24), E.RING_KRW, themes_opts,
            [1, 2, 4], [1, 2]):
        v = E.vehicle_inputs(CONST, model, 60, remaining_range_m=rr_km * 1000)
        r = E.compute_range(v, extra, days)
        yield {"origin": ORIGIN, "budget": E.budget_json(E.budget_of(r, v)), "seats_occupied": seats,
               "extra_krw": extra, "days": days, "themes": themes, "recent_visits": [], "demo_seed": "inv"}


STATES = list(states())


def shown(res):
    return ([res["picked"]] if res["picked"] else []) + res["stack"]


def check_card(c, fuel_type, seats):
    d, reason, m = c["display"], c["reason"], c["minutes"]
    assert m <= E.FAR_MINUTES, "90분 넘는 카드는 내지 않는다"
    assert c["time_label"] == ("75분 안" if m <= 75 else "조금 먼 곳")
    if m > 75:
        assert "75분 안" not in reason, (c["name"], reason)
    word = "충전" if fuel_type == "ev" else "주유"
    zero_phrase = f"추가 {word} 없이"          # PRD v1.6 5-3: 화면 문구('추가 0원'은 내부 조건 이름)
    if d["tight"]:
        assert d["badge_text"] == "빠듯", "경계 카드는 '빠듯'만(PRD 5-3)"
        assert zero_phrase not in reason and "추가 0원" not in reason, (c["name"], reason)
    if d["zero_extra"] and not d["tight"]:
        assert re.fullmatch(rf"{zero_phrase} · \d+km 남기고", d["badge_text"]), d["badge_text"]   # '남겨 둘 거리'
    if not d["zero_extra"]:
        assert zero_phrase not in reason and "추가 0원" not in reason and "없이" not in d["badge_text"]
    assert d["ring_krw"] in E.RING_KRW
    assert (d["per_person_text"] is None) == (seats < 2)
    assert "주차" not in reason
    for txt in (d["total_text"], d["badge_text"], d["per_person_text"] or "", reason):
        assert not ZERO_KRW.search(txt), txt
        assert not any(f in txt for f in FORBIDDEN), txt


@pytest.mark.parametrize("i", range(0, len(STATES), max(1, len(STATES) // 60)))
def test_every_card_follows_copy_rules(i):
    for req in STATES[i:i + max(1, len(STATES) // 60)]:
        res = build_cards(req, CANDS, SessionStore())
        for c in shown(res):
            check_card(c, req["budget"]["fuel_type"], req["seats_occupied"])
        # F2·F3 경계: 새로 뽑을 때는 고른 프리셋·테마 안에서만
        for c in shown(res):
            assert c["display"]["ring_krw"] <= req["extra_krw"]
            assert not req["themes"] or c["theme"] in req["themes"]
        assert (res["hint"] is not None) == (res["candidates_in_range"] < 3)
        assert len({c["id"] for c in shown(res)}) == len(shown(res)), "앞면·스택 중복 없음"


def test_recall_keeps_picked_and_swap_uses_latest_values():
    store = SessionStore()
    v1 = E.vehicle_inputs(CONST, "ioniq5_lr", 80, remaining_range_m=326_000, price=300)
    v2 = E.vehicle_inputs(CONST, "ioniq5_lr", 35, remaining_range_m=143_000, price=300)
    req1 = next(s for s in STATES if s["budget"]["fuel_type"] == "ev")
    req1 = dict(req1, budget=E.budget_json(E.budget_of(E.compute_range(v1), v1)), themes=[], extra_krw=0,
                days=1, seats_occupied=1)
    first = build_cards(req1, CANDS, store)
    b2 = E.budget_of(E.compute_range(v2), v2)
    second = build_cards(dict(req1, budget=E.budget_json(b2), session_id=first["session_id"]), CANDS, store)
    assert second["picked"]["id"] == first["picked"]["id"]
    for _ in range(2):
        s = swap(first["session_id"], [], CANDS, store)
        card = s["card"]
        if card["display"]["zero_extra"]:
            assert 2 * card["route_km"] <= b2.usable_km, "교체 카드도 최신 잔고 기준으로 0원 판정"
    assert swap(first["session_id"], [], CANDS, store)["error"] == "swap_limit"


def test_session_expires():
    t = [0.0]
    store = SessionStore(clock=lambda: t[0])
    res = build_cards(STATES[0], CANDS, store)
    if res["session_id"]:
        t[0] += 31 * 60
        assert swap(res["session_id"], [], CANDS, store)["error"] == "session_not_found"


@pytest.mark.parametrize("n", [0, 1, 499, 500, 2_499, 2_500, 4_999, 18_576])
def test_no_zero_won_text(n):
    for unit in (1_000, 5_000):
        _, txt, _ = E.approx(n, unit)
        assert not ZERO_KRW.search(txt), txt
