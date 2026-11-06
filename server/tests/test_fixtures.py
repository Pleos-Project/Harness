"""fixtures/ 가 엔진·서버와 일치하는지. 앱(Kotlin FixturesTest)도 같은 JSON을 읽는다.

1부: 명세서 AC 숫자를 손으로 검산한 값(엔진 출력과 독립된 기대값)
2부: fixture 전수 — app_local → request → 서버 응답이 저장된 기대값과 같은가
"""

import copy
import json
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from chagok import engine as E
from chagok.main import app

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import gen_fixtures as G  # noqa: E402

CARDS = sorted((ROOT / "fixtures/cards").glob("D*.json"), key=lambda p: int(p.stem[1:]))
SETTLE = sorted((ROOT / "fixtures/settle").glob("T*.json"), key=lambda p: int(p.stem[1:]))
LEDGER = sorted((ROOT / "fixtures/ledger").glob("L*.json"))
client = TestClient(app)
EFF = 6.2 * 0.85


def load(p):
    return json.loads(p.read_text(encoding="utf-8"))


def ev(level=80, rr=245_000, cap=77.4):
    return E.Vehicle("ev", level, cap, EFF, 300, remaining_range_m=rr)


# ---------------------------------------------------------------- 1부: 손 검산 (명세서 AC와 1:1)
def test_ac_f2_1_radius_d1_d2():
    # PT 콘솔 값(DECISIONS): D1 245km → 반경 75, D2 100km → 반경 19 (명세 AC-F2-1의 326·143km를 대체)
    assert abs(E.compute_range(ev()).radius_km - 75) < 1
    assert abs(E.compute_range(ev(35, 100_000)).radius_km - 19.2) < 0.5


def test_ac_f1_1_balance_krw_rounding():
    r = E.compute_range(ev())
    assert r.balance_krw == 19_000  # 18,576 → 반올림
    assert r.balance_text == "배터리에 1만 9천 원어치 · 약 245km"


def test_ac_f1_2_assembled_fallback():
    r = E.compute_range(ev(rr=0))
    assert r.range_source == "assembled" and abs(r.balance_km - 326) < 1


def test_ac_f1_4_ice_unit_conversion():
    assert E.l_per_100km_to_km_per_l(8.0) == 12.5


def test_ac_f1_5_capacity_sdk_wins_over_table():
    const = G.CONST
    v = E.vehicle_inputs(const, "ioniq5_lr", 80, remaining_range_m=326_000, capacity_sdk=60, price=300)
    assert E.compute_range(v).balance_krw == 14_000


def test_ac_f2_2_zero_unavailable_under_margin():
    assert E.compute_range(ev(12, 40_000)).zero_available is False


def test_ac_f2_3_overnight_minus_11_5km():
    d1, d2 = E.compute_range(ev(), days=1).radius_km, E.compute_range(ev(), days=2).radius_km
    assert abs((d1 - d2) - 30 / 2 / 1.3) < 0.01


def test_ac_f9_1_charge_text():
    assert E.charge_amount(20, 80, 77.4, 300)[2] == "방금 약 1만 4천 원어치 충전"


def test_f9_full_tank_amount():
    """PRD v1.3 F9 '가득': (100 − 25) ÷ 100 × 60L = 45L × 1,856 = 83,520 → 8만 4천."""
    assert E.full_tank_amount(25, 60, 1856) == (45.0, 84_000, "약 8만 4천 원")
    assert E.full_tank_amount(100, 60, 1856) == (0.0, 0, "1천 원 미만")      # 이미 가득 → '0 원' 금지
    assert E.full_tank_amount(120, 60, 1856)[:2] == (0.0, 0)                # 100 넘는 값은 0으로
    assert E.full_tank_amount(99.5, 60, 1856)[1:] == (1_000, "약 1천 원")   # 0.3L × 1,856 = 556.8 → 1천(반올림)


def test_d5_settle_text():
    s = E.settle(E.Snapshot(12_340, 2, 300, EFF, "t"), 12_472, "양평 두물머리")
    assert s.text == "지난번 이후 132km · 약 8천 원 · 마지막 목적지 양평 두물머리"
    assert s.per_person == 4_000


def test_ac_f5_1_d1_picked_text():
    fx = load(ROOT / "fixtures/cards/D1.json")["expected"]["picked"]
    assert fx["name"] == "양평 두물머리"
    assert fx["display"]["badge_text"] == "추가 충전 없이 · 50km 남기고"
    assert fx["reason"] == "추가 충전 없이 · 75분 안 · 지난번과 다른 테마"


def test_ac_f5_2_d2_same_card_badge_only():
    d1, d2 = load(ROOT / "fixtures/cards/D1.json"), load(ROOT / "fixtures/cards/D2.json")
    assert d2["expected"]["picked"]["id"] == d1["expected"]["picked"]["id"]
    assert d2["expected"]["picked"]["display"]["badge_text"] == "+1만 원 안팎"


def test_ac_f11_1_per_person_with_four():
    d3 = load(ROOT / "fixtures/cards/D3.json")["expected"]["picked"]
    assert d3["display"]["per_person_text"] == "1인 2천 원 안팎"  # 7,920 ÷ 4 = 1,980


# ---------------------------------------------------------------- 2부: fixture 전수
def post_cards(req):
    r = client.post("/v1/cards", json=req)
    assert r.status_code == 200, r.text
    return r.json()


def masked(res):
    res = copy.deepcopy(res)
    if res.get("session_id"):
        res["session_id"] = G.MASK
    return res


@pytest.mark.parametrize("path", [p for p in CARDS if "app_local" in load(p)], ids=lambda p: p.stem)
def test_app_local_matches(path):
    fx = load(path)
    v, r, local = G.app_local(fx["app_local"]["inputs"])
    assert local == fx["app_local"]["expected"]
    if "request" in fx:
        req = G.request_for(fx["app_local"]["inputs"], v, r, seats=fx["request"]["seats_occupied"],
                            themes=fx["request"]["themes"], recent=fx["request"]["recent_visits"])
        want = {k: v for k, v in fx["request"].items() if k != "session_id"}
        assert req == want


@pytest.mark.parametrize("path", [p for p in CARDS if "request" in load(p)], ids=lambda p: p.stem)
def test_cards_fixture_matches_server(path):
    fx = load(path)
    req = fx["request"]
    if fx.get("session_from"):
        first = post_cards(load(ROOT / "fixtures/cards" / f"{fx['session_from']}.json")["request"])
        req = dict(req, session_id=first["session_id"])
    res = post_cards(req)
    assert masked(res) == fx["expected"]
    for want in fx.get("expected_swaps", []):
        s = client.post("/v1/cards/swap", json={"session_id": res["session_id"]})
        if "error" in want:
            assert s.status_code == 409 and s.json()["detail"]["error"] == want["error"]
        else:
            got = s.json()
            assert {"card": got["card"], "swaps_left": got["swaps_left"], "stack_left": got["stack_left"]} == want


@pytest.mark.parametrize("path", SETTLE, ids=lambda p: p.stem)
def test_settle_fixture_matches_reference(path):
    fx = load(path)
    res = client.post("/v1/settle", json={"snapshot_prev": fx["snapshot_prev"], "odometer_now": fx["odometer_now"],
                                          "last_destination": fx["last_destination"], "span_days": fx["span_days"]})
    assert res.status_code == 200, res.text
    assert res.json() == fx["expected"]


@pytest.mark.parametrize("path", LEDGER, ids=lambda p: p.stem)
def test_ledger_fixture(path):
    fx = load(path)
    st = fx["state0"]
    for step in fx["steps"]:
        st, out = E.ledger_step(st, step["event"])
        assert out == step["expected"]["output"]
        assert st == step["expected"]["state"]
    assert E.month_summary(st["rows"], fx["month"]) == fx["expected_month"]


# ---------------------------------------------------------------- 계약·세션 규칙
def test_raw_vehicle_values_are_rejected():
    """PRD 5-2: 잔량·주행가능거리를 서버로 보내지 않는다 → 모르는 필드는 422."""
    req = load(ROOT / "fixtures/cards/D1.json")["request"]
    bad = copy.deepcopy(req)
    bad["budget"]["level_pct"] = 80
    assert client.post("/v1/cards", json=bad).status_code == 422
    bad = dict(copy.deepcopy(req), vehicle={"model": "ioniq5_lr", "level_pct": 80})
    assert client.post("/v1/cards", json=bad).status_code == 422


def test_swap_follows_visible_stack_order():
    d1 = load(ROOT / "fixtures/cards/D1.json")
    stack_ids = [c["id"] for c in d1["expected"]["stack"]]
    swapped = [s["card"]["id"] for s in d1["expected_swaps"] if "card" in s]
    assert swapped == stack_ids[: len(swapped)]


def test_swap_after_recall_uses_latest_vehicle_values():
    d2 = load(ROOT / "fixtures/cards/D2.json")
    card = d2["expected_swaps"][0]["card"]
    usable = d2["app_local"]["expected"]["usable_km"]
    assert not card["display"]["zero_extra"] or card["route_km"] * 2 <= usable  # D2 가용 거리 안일 때만 0원


def test_reason_never_mentions_parking():
    for p in CARDS:
        exp = load(p).get("expected", {})
        for c in ([exp["picked"]] if exp.get("picked") else []) + exp.get("stack", []):
            assert "주차" not in c["reason"]
