"""fixtures/ 와 contract/samples/ 생성. 손으로 고치지 않는다.

엔진(server/chagok/*.py)이나 상수(fixtures/constants.json)·후보(fixtures/candidates_demo.json)가 바뀌면:
    python scripts/gen_fixtures.py        (= make fixtures)
서버 pytest와 앱 JUnit(app/android/app/src/test/.../FixturesTest)이 같은 파일을 읽는다. CI는 재생성 후 diff가 없어야 통과한다.

fixture 종류
- cards/D*.json   app_local(앱 로컬 E1 기대값) → request(앱이 보낼 요청) → expected(서버·오프라인 데모 응답)
- settle/T*.json  E4 정산 경계 케이스
- ledger/L*.json  F8·F10·F17 로컬 장부(출발·도착·다시 열기·핀·월 합계) 이벤트 순서
- effort/R*.json  운전 노고(명세 v0.5 F23~F25) input → expected(영수증·제안). R1~R8 = 명세 12절 표(R2·R4는 #62에서 지움),
                  R9 = AC-F21-1, R10~ = 하네스 추가(R16~R21 도착 상한, R22~R26 시간대별 강수, R27~R33 중간 정산,
                  R34~R37 중간 정산 시각에서 장시간 끊기)
- effort/sun.json 일출·일몰 계산값(엔진 두 벌 대조) + 한국천문연구원 출몰시각(참고, pytest가 ±1분 대조)

계약 샘플(contract/samples)
- D1·D2           /v1/cards (D1·D2 fixture에서 그대로)
- weather.*       GET /v1/weather 응답. 기상청 초단기실황 모양의 고정 응답을 chagok.weather로 해석한 값
- weather.trip.*  GET /v1/weather/trip 응답(#63). 같은 모양의 고정 응답을 chagok.weather.trip으로 모은 값
- places.*        GET /v1/places 응답. 카카오 로컬 키워드 검색 모양의 고정 응답을 chagok.places로 해석한 값
"""

from __future__ import annotations

import copy
import dataclasses
import json
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))

from chagok import effort as EF  # noqa: E402
from chagok import engine as E  # noqa: E402
from chagok import places as PL  # noqa: E402
from chagok import weather as W  # noqa: E402
from chagok.cards import SessionStore, build_cards, load_constants, load_demo_candidates, swap  # noqa: E402

CONST = load_constants()
CANDS = load_demo_candidates()
ORIGIN = {"lat": CONST["origin"]["lat"], "lng": CONST["origin"]["lng"]}
SEED = CONST["demo_seed"]
MASK = "<session>"
WRITTEN: set[Path] = set()


def write(path: Path, obj: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    WRITTEN.add(path.resolve())
    print("wrote", path.relative_to(ROOT).as_posix())


def sweep_stale(*dirs: str) -> None:
    for d in dirs:
        for p in (ROOT / d).glob("*.json"):
            if p.resolve() not in WRITTEN:
                p.unlink()
                print("removed", p.relative_to(ROOT).as_posix())


# ---------------------------------------------------------------- 앱 로컬(E1) → 요청
def app_local(inp: dict) -> tuple[E.Vehicle, E.RangeResult, dict]:
    v = E.vehicle_inputs(CONST, inp["model"], inp["level_pct"], remaining_range_m=inp.get("remaining_range_m", 0),
                         capacity_sdk=inp.get("capacity_sdk"), efficiency_sdk=inp.get("efficiency_sdk"),
                         price=inp.get("price"))
    r = E.compute_range(v, inp.get("extra_krw", 0), inp.get("days", 1))
    exp = {"fuel_type": v.fuel_type, "capacity": v.capacity, "efficiency": v.efficiency,
           "price_per_unit": v.price_per_unit, "price_source": v.price_source,
           "balance_km": E.round1(r.balance_km), "balance_krw": r.balance_krw, "balance_text": r.balance_text,
           "margin_km": E.round1(r.margin_km), "usable_km": E.round1(r.usable_km),
           "radius_km": E.round1(r.radius_km), "range_source": r.range_source, "zero_available": r.zero_available}
    return v, r, exp


def request_for(inp: dict, v: E.Vehicle, r: E.RangeResult, **kw) -> dict:
    req = {"origin": ORIGIN, "budget": E.budget_json(E.budget_of(r, v)),
           "seats_occupied": kw.get("seats", 1), "extra_krw": inp.get("extra_krw", 0), "days": inp.get("days", 1),
           "themes": kw.get("themes", []), "recent_visits": kw.get("recent", []), "demo_seed": SEED}
    return req


def masked(res: dict) -> dict:
    res = copy.deepcopy(res)
    if res.get("session_id"):
        res["session_id"] = MASK
    return res


def card_fixture(fid: str, title: str, note: str, console: dict, inp: dict, *, seats: int = 1,
                 themes: list[str] | None = None, after: dict | None = None, swaps: int = 0,
                 swap_after: bool = False) -> dict:
    """after = 같은 세션의 직전 fixture(재호출). swaps = 응답 뒤 교체 횟수(기대값 기록)."""
    v, r, local = app_local(inp)
    req = request_for(inp, v, r, seats=seats, themes=themes or [])
    store = SessionStore(ids=iter(f"s{i}" for i in range(1, 99)).__next__)
    if after:
        first = build_cards(after["request"], CANDS, store)
        req_sent = dict(req, session_id=first["session_id"])
        req = dict(req, session_id=MASK)
    else:
        req_sent = req
    res = build_cards(req_sent, CANDS, store)
    fx = {"id": fid, "title": title, "note": note, "virtual_console": console,
          "app_local": {"inputs": inp, "expected": local}, "request": req, "expected": masked(res)}
    if after:
        fx["session_from"] = after["id"]
    if swaps or swap_after:
        out = []
        for _ in range(swaps or 1):
            s = swap(res["session_id"], [], CANDS, store)
            out.append({"error": s["error"]} if "error" in s else
                       {"card": s["card"], "swaps_left": s["swaps_left"], "stack_left": s["stack_left"]})
        fx["expected_swaps"] = out
    return fx


def local_fixture(fid: str, title: str, note: str, console: dict, inp: dict) -> dict:
    _, _, local = app_local(inp)
    return {"id": fid, "title": title, "note": note, "virtual_console": console,
            "app_local": {"inputs": inp, "expected": local}}


# ---------------------------------------------------------------- 장부
def ledger_fixture(fid: str, title: str, note: str, events: list[dict], month: str,
                   state0: dict | None = None) -> dict:
    st = copy.deepcopy(state0) if state0 else E.new_ledger()
    start = copy.deepcopy(st)
    steps = []
    for ev in events:
        st, out = E.ledger_step(st, ev)
        steps.append({"event": ev, "expected": {"output": out, "state": copy.deepcopy(st)}})
    return {"id": fid, "title": title, "note": note, "state0": start, "steps": steps,
            "month": month, "expected_month": E.month_summary(st["rows"], month)}


PLACE = {c.id: {"id": c.id, "name": c.name, "lat": c.lat, "lng": c.lng} for c in CANDS}
DUMUL = PLACE["p_dumulmeori"]
DUMUL_KM = next(c.route_km for c in CANDS if c.id == "p_dumulmeori")
DUMUL_MIN = next(c.minutes for c in CANDS if c.id == "p_dumulmeori")
EV_EFF = E.vehicle_inputs(CONST, "ioniq5_lr", 80).efficiency


def ev_open(ts: str, odo: float, seats: int = 1, recent: dict | None = None, **kw) -> dict:
    return {"type": "open", "ts": ts, "odometer_km": odo, "seats": seats, "price": kw.get("price", 300),
            "efficiency": kw.get("eff", EV_EFF), "price_source": kw.get("src", "user"),
            "recent_destination": recent}


# ---------------------------------------------------------------- 운전 노고 (명세 v0.5 F21·F23~F25)
EFFORT_CONST = EF.effort_constants(CONST)
# 날짜는 서울 2026-11-17: 이 날 일몰 계산값이 명세 R1의 17:20이다(일출 07:13). 일출·일몰은 입력으로 고정해
# 일출·일몰 계산(sun.json)이 바뀌어도 R 기대값은 그대로 둔다.
DAY = "2026-11-17"
NEXT_DAY = "2026-11-18"
SUN = {"sunrise": "07:13", "sunset": "17:20"}
# R1·시연 데이터의 경로 거리. 바꾸면 정체 km 표시만 따라 바뀌고 배수·금액은 그대로(명세 12절 시연 데이터)
R1_ROUTE_M = 104_300
R1_RATIO = 0.36          # requestTrip 정체 비율은 4% 단위(June 실측) → 9칸


def at(hm: str, day: str = DAY) -> str:
    return f"{day}T{hm}:00+09:00"


def effort_input(dep: str, arr: str, *, eta_min: float, n: int, answer: int = 0, ratio: float | None = 0.0,
                 congestion_m: float | None = 0.0, segments_min: float | None = None, wdep: str = "none",
                 warr: str = "none", distance_km: float | None = None, arr_day: str = DAY,
                 trip: dict | None = None) -> dict:
    """정차: answer = 도착 때 답한 쉰 시간(분, #62: 답 하나로만).
    정체: ratio·congestion_m = 기본(출발 전 requestTrip 비율·정체 거리 m = 비율 × 경로 거리, 없으면 None),
    segments_min = 보조(구간 목록 통과 시간).
    trip = 시간대별 강수(GET /v1/weather/trip 응답, #63). 넘긴 경우에만 입력에 넣는다(기존 R* 입력은 그대로)."""
    return {
        "departed_at": at(dep), "arrived_at": at(arr, arr_day),
        "rest_answer_min": answer,
        "eta_s": eta_min * 60,
        "congestion_ratio": ratio,
        "congestion_m": congestion_m,
        "congestion_s": segments_min * 60 if segments_min is not None else None,
        "weather_dep": wdep, "weather_arr": warr, **SUN, "n": n, "distance_km": distance_km,
    } | ({"weather_trip": trip} if trip is not None else {})


def trip_hours(first_hour: int, dep: list[str], arr: list[str], day: str = DAY) -> dict:
    """GET /v1/weather/trip 응답 모양(#63): 조각 [h, h+1) KST 정시, dep·arr 같은 순서. status는 엔진이 쓰지 않지만
    앱이 응답을 그대로 넘기는 경우를 보려고 계약대로 채운다."""
    every = dep + arr
    status = "unavailable" if all(x == "unknown" for x in every) else "partial" if "unknown" in every else "ok"
    return {"status": status,
            "dep": [{"hour": at(f"{first_hour + i:02d}:00", day), "precip": x} for i, x in enumerate(dep)],
            "arr": [{"hour": at(f"{first_hour + i:02d}:00", day), "precip": x} for i, x in enumerate(arr)]}


def effort_fixture(fid: str, title: str, note: str, inp: dict) -> dict:
    r = EF.effort(EF.input_from_json(inp), EFFORT_CONST)
    return {"id": fid, "title": title, "note": note, "input": inp, "expected": r.to_json()}


# 한국천문연구원 출몰시각(서울, 11/7 조회). 엔진 계산값과 ±1분 안인지 pytest가 본다(명세 7절 '검증').
# 좌표는 역서 범례의 서울시청(북위 37°34′0″, 동경 126°58′42″). 손으로 옮긴 참고값이라 엔진이 바뀌어도 그대로 둔다.
KASI_SEOUL: dict = {
    "place": "서울시청", "lat": 37.566667, "lng": 126.978333,
    "source": "천문연 월별 해/달 출몰시각(astro.kasi.re.kr/life/pageView/6, location=서울)·2026 천문력",
    "days": {
        "2026-03-20": {"sunrise": "06:36", "sunset": "18:44"},
        "2026-06-21": {"sunrise": "05:11", "sunset": "19:57"},
        "2026-11-07": {"sunrise": "07:03", "sunset": "17:28"},
        "2026-12-03": {"sunrise": "07:30", "sunset": "17:14"},
    },
}


# 시연 출발 미리보기의 정체 구간 막대(#116). (congestionType, startRatio, endRatio) = 경로의 % 정수.
# 위치는 시연용으로 지어낸 값: 도시를 빠져나가는 구간·목적지 근처. 원활 구간은 목록에 넣지 않는다.
DEMO_TRAFFIC = [("HEAVY", 2, 8), ("SLOW", 8, 20), ("SLOW", 72, 82), ("HEAVY", 90, 98)]
DEMO_MIDWAY_M = 52_000   # 휴게소(17:05)까지 온 거리 = R27


def demo_traffic(r1: dict) -> list[dict]:
    """정체 구간 목록을 검사하고 demo.json 모양으로 낸다.
    - SLOW·HEAVY 합 = 미리보기 정체 비율(36%). 앱 실제 모드도 이 둘만 더해 엔진 정체 입력으로 쓴다(NaviRepository.onTripInfo).
    - 휴게소 지점 앞뒤로 같게(18%씩): 엔진은 정체를 고르게 세서 중간 정산(R27) 정체가 전체의 절반(23.4분 · 19km)이다."""
    mid_pct = 100 * DEMO_MIDWAY_M / R1_ROUTE_M
    total = sum(e - s for _, s, e in DEMO_TRAFFIC)
    before = sum(e - s for _, s, e in DEMO_TRAFFIC if e <= mid_pct)
    after = sum(e - s for _, s, e in DEMO_TRAFFIC if s >= mid_pct)
    assert all(t in ("SLOW", "HEAVY") and 0 <= s < e <= 100 for t, s, e in DEMO_TRAFFIC)
    assert before + after == total, "휴게소 지점을 걸치는 구간이 없어야 앞뒤를 나눠 셀 수 있다"
    assert abs(total / 100 - r1["congestion_ratio"]) < 1e-9, "구간 합 = 미리보기 정체 비율"
    assert before == after, "휴게소 앞뒤 정체를 같게(중간 정산 영수증과 막대가 맞게)"
    return [{"congestion_type": t, "start_ratio": s, "end_ratio": e} for t, s, e in DEMO_TRAFFIC]


def demo_fixture(r1: dict) -> None:
    """시연 데이터(명세 v0.5 12절): R1 입력 그대로 + 화면용 scene. 목업·영상·PT 앱이 같은 숫자를 쓴다.
    앱은 데모 고정 모드에서 assets/data/effort/demo.json을 읽는다(syncChagokData).
    midway·midway_arrival = 휴게소 중간 정산 장면(효성 11/9): 17:05 중간 정산(R27) → [좋아요]로 정산 → 18:10 도착(R28)."""
    r = EF.effort(EF.input_from_json(r1), EFFORT_CONST)
    mid = r1_midway(r1, "17:05", DEMO_MIDWAY_M)
    after = dict(r1, settlements=[settled(mid)])
    preview = EF.departure_preview(r1["eta_s"], R1_ROUTE_M, r1["congestion_ratio"], r1["congestion_m"], EFFORT_CONST)
    rest_label = dict(EFFORT_CONST.copy.rest_answers)[r1["rest_answer_min"]]   # 도착 때 질문의 선택지(copy)
    write(ROOT / "fixtures/effort/demo.json", {
        "id": "demo", "title": "시연 데이터: R1(PRD 대표 장면) + 휴게소 중간 정산(R27 → R28)",
        "note": "명세 v0.5 12절. gen_fixtures.py가 R1·R27·R28 정의로 만든다(손으로 고치지 않음). "
                "목적지 이름·경로 거리는 바꿔도 배수·금액이 그대로(정체 km 표시만 바뀜). "
                "목적지 이름은 명준 #60. 화면 문구는 constants effort.copy의 임시 값(디자인 확정 대기)",
        "scene": {
            "dest_name": "태안 꽃지해수욕장",
            "route_m": R1_ROUTE_M,
            "n": r1["n"],
            "rest_answer_label": rest_label,
            "departure_preview": dataclasses.asdict(preview),
            # 출발 미리보기 정체 구간 막대(#116, 명준 시안 → June 앱). 엔진 입력이 아니라 그림용
            "route_preview": {
                "note": "출발 미리보기 정체 구간 막대(#116). 실제 모드의 requestTrip TrafficInfo(congestionType·startRatio·"
                        "endRatio, 경로의 % 정수)와 같은 뜻. 실제 모드는 경로 전체가 4% 칸으로 오고 FAST·MODERATE도 섞이지만 "
                        "시연 데이터는 SLOW·HEAVY 구간만 둔다. 앱은 목록에 없는 구간을 원활로 칠해 둘이 같은 그림이 된다(June #117 실측). "
                        "구간 위치는 시연용으로 지어낸 값이고 4% 칸에 맞추지 않는다(합 36%를 휴게소 앞뒤 18%씩 나누려고). "
                        "SLOW·HEAVY 합 = 미리보기 정체 비율 36%('38km (경로의 36%)'), 앱이 엔진 정체 입력으로 쓰는 기준과 같다. "
                        "휴게소(경로의 약 50%) 앞뒤 18%씩: 엔진은 정체를 고르게 세서 중간 정산(R27) 정체가 전체의 절반"
                        "(23.4분 · 19km)이라 막대와 맞춘다. 출발지 이름: 실제 requestTrip의 routePoints에 출발 지점이 없어 "
                        "앱이 '현재 위치'로 쓴다(June #117 실측)",
                "origin_name": "현재 위치",
                "traffic": demo_traffic(r1),
            },
        },
        "input": r1, "expected": r.to_json(),
        # 휴게소에서 운전자 교대 → 지금까지의 노고 보기. 시연 모드는 지금 시각·내비 남은 거리·시간대별 날씨 대신 이 값을 쓴다
        "midway": {
            "note": "휴게소 17:05 중간 정산(= R27). at = 정산 시각(input.arrived_at), remain_m = 그때 내비 남은 거리"
                    "(input.midway.remain_m). 정산하면 출발 기록 settlements에 {at, remain_krw = expected.proposal.remain_krw}",
            "at": mid["arrived_at"],
            "remain_m": mid["midway"]["remain_m"],
            "input": mid, "expected": EF.effort(EF.input_from_json(mid), EFFORT_CONST).to_json(),
        },
        "midway_arrival": {
            "note": "midway를 [좋아요]로 정산한 뒤 18:10 도착(= R28). input = 위 input(R1) + settlements 한 줄. "
                    "장시간이 17:05에서 끊겨 1.56배(중간 정산 없는 위 expected는 1.57배)",
            "input": after, "expected": EF.effort(EF.input_from_json(after), EFFORT_CONST).to_json(),
        }})


def remain_of(inp: dict) -> float:
    """그 영수증에서 정산하면 기록에 더할 원 단위 금액(proposal.remain_krw, 어림 전)."""
    return EF.effort(EF.input_from_json(inp), EFFORT_CONST).proposal.remain_krw


def settled(inp: dict) -> dict:
    """중간 정산 영수증 → 앱 출발 기록 settlements에 쌓일 한 줄 중 엔진이 받는 값(at = 그 영수증의 arrived_at)."""
    return {"at": inp["arrived_at"], "remain_krw": remain_of(inp)}


def r1_midway(r1: dict, now: str, so_far_m: int, **kw) -> dict:
    """R1 장면을 가는 중에 정산. 앱이 넘기는 값: arrived_at = 지금, midway = {경로 거리, 내비 남은 거리},
    weather_trip = 출발 좌표~현재 위치·to=지금(16:00·17:00 조각, 두 지점 모두 비)."""
    hours = trip_hours(16, ["rain"] * 2, ["rain"] * 2)
    return dict(r1, arrived_at=at(now), distance_km=None,
                midway={"route_m": R1_ROUTE_M, "remain_m": R1_ROUTE_M - so_far_m}, weather_trip=hours, **kw)


def midway_cases(r1: dict) -> list[tuple[str, str, str, dict]]:
    """중간 정산(명준 Q-08-02, 효성 계산 규칙 11/9, PR #94 댓글). 가는 중 '출발~지금'을 도착과 같은 영수증으로 세고
    A·B로 실제 정산한다. 앞서 한 정산은 settlements = [{at, remain_krw}]로 넘긴다(앱 출발 기록 settlements의 두 값).
    엔진이 remain_krw 합(남은 금액에서 뺌)과 정산 시각(장시간 구간 경계, 효성 11/9 #97 뒤)을 둘 다 만든다."""
    mid1 = r1_midway(r1, "17:05", 52_000)
    st1 = settled(mid1)
    mid2 = r1_midway(r1, "17:40", 80_000, settlements=[st1])
    st2 = settled(mid2)
    plain = effort_input("10:00", "12:00", eta_min=120, n=2)   # 2시간 맑음·낮, 가산 0 → 금액 21,400원
    return [
        ("R27", "중간 정산(시안 예시): R1 장면을 출발 65분(17:05)·지금까지 52km에서 정산. 경로 104.3km, 내비 남은 52.3km, 4명",
         "지금까지 거리 = 104,300 − 52,300 = 52,000m. 예상보다 더의 기준 ETA = 105분 × 52,000 ÷ 104,300 = 52.35분 → "
         "65 − 52.35 = 12.65분 '+13분' +5%. 비 65분 +40%(시간대별, to = 지금), 정체 0.36 × 65 = 23.4분 +10%, "
         "줄 값 '19km (경로의 36%)'(0.36 × 52km), 밤길·장시간 0 → 1.55배. 금액 17,964.19 → 약 1만 8천, 한 사람 4,491.05 → "
         "약 4천, 운전자 몫 13,473.14 → 밥. 거리 표시 52km. 도착 상한은 출발 때 ETA 105분 그대로(210분). "
         "처음 정산이라 settled_krw 0, remain_krw = 금액(정산하면 이 값을 기록에 더함). "
         "시안 예시의 17,967.08은 어림한 배수 1.55 × 65분으로 낸 값이라 원 단위만 다름(효성 확인 11/9)", mid1),
        ("R28", "R27에서 정산한 뒤 도착: R1과 같은 주행, settlements = [R27 정산(17:05)]",
         "장시간은 정산 시각 17:05에서 끊겨 65분 + 65분 → 장시간 줄 없음(R1은 130분 한 구간이라 '2시간 넘긴 10분'). "
         "노고 203.386 → 1.56배, 금액 36,270.50 → 약 3만 6천. 제안은 남은 금액 36,270.50 − 17,964.19 = 18,306.31로 "
         "한 사람 4,576.58 → 약 5천(어림은 마지막에 한 번, 어림끼리 빼지 않음). 운전자 몫 13,729.74 → 밥. "
         "A 근거는 '중간에 챙긴 몫을 뺀 나머지'(effort.copy.a_basis_remain_text 임시)", dict(r1, settlements=[st1])),
        ("R29", "남은 몫 B 경계(같음 → 제안): 10:00→12:00 맑음·낮, 2명, 앞서 정산 16,700원",
         "금액 21,400(정산 없으면 운전자 몫 10,700 → 밥). 남은 4,700 → 운전자 몫 2,350 = 커피 1인분 절반 → 커피(경계 '이상'). "
         "정산 시각은 도착과 같은 12:00이라 장시간 구간은 120분 한 구간 그대로(이 사례는 금액 경계만 봄)",
         dict(plain, settlements=[{"at": at("12:00"), "remain_krw": 16_700.0}])),
        ("R30", "남은 몫 B 경계(1원 아래 → 제안 없음): R29에서 앞서 정산 16,701원",
         "남은 4,699 → 운전자 몫 2,349.5 < 2,350 → B 제안 없음(b null). 앱은 A도 같은 기준으로 기록만(Q-07-04)",
         dict(plain, settlements=[{"at": at("12:00"), "remain_krw": 16_701.0}])),
        ("R31", "남은 몫 0: R27 정산 직후 같은 자리(17:05)에서 다시 중간 정산",
         "영수증은 R27과 같고(정산 시각 = 지금이라 길이 0인 구간은 넣지 않음 → 65분 한 구간) 남은 금액 = "
         "17,964.19 − 17,964.19 = 0 → 한 사람 '1천 원 미만', B 제안 없음. 정산한 합이 금액보다 커도 남은 금액은 0(음수 없음)",
         dict(mid1, settlements=[st1])),
        ("R32", "두 번째 중간 정산: R27 정산 뒤 출발 100분(17:40)·지금까지 80km, settlements = [R27 정산(17:05)]",
         "기준 ETA = 105 × 80,000 ÷ 104,300 = 80.54분 → '+19분' +5%. 비 100분 +40%, 정체 '29km (경로의 36%)' +10%, "
         "밤길 17:20~17:40 '20분' +1% → 1.56배, 금액 27,779.88 → 약 2만 8천. 장시간은 앞 정산 17:05에서 끊겨 65 + 35분(둘 다 0). "
         "남은 27,779.88 − 17,964.19 = 9,815.69 → 한 사람 2,453.92 → 약 2천, 운전자 몫 7,361.77 → 커피", mid2),
        ("R33", "두 번 정산한 뒤 도착: R1과 같은 주행, settlements = [R27 정산(17:05), R32 정산(17:40)]",
         "장시간은 65 + 35 + 30분 → 줄 없음, 1.56배·금액 36,270.50(R28과 같음). 정산한 합 17,964.19 + 9,815.69 = 27,779.88"
         "(= R32 금액). 남은 36,270.50 − 27,779.88 = 8,490.62 → 한 사람 2,122.66 → 약 2천, 운전자 몫 6,367.97 → 커피",
         dict(r1, settlements=[st1, st2])),
    ]


# R34~R37의 긴 길: 10:00 출발, ETA 200분, 경로 200km, 맑음·낮, 2명, 정체 0
LONG_ROUTE_M = 200_000


def fatigue_split_cases() -> list[tuple[str, str, str, dict]]:
    """중간 정산 시각에서 장시간(피로) 구간을 끊기(효성 11/9, #97 뒤). 영수증 줄 중 운전하는 사람에 따라 달라지는 건 장시간뿐이라
    교대·휴게로 정산한 시각을 구간 경계로 본다. 중간 정산이 있으면 쉰 시간 답 Q는 T에서 빼기만 하고 가운데에서 끊지 않는다."""
    long = effort_input("10:00", "14:00", answer=30, eta_min=200, n=2)

    def long_midway(now: str, so_far_m: int, **kw) -> dict:
        return dict(long, arrived_at=at(now), rest_answer_min=0,
                    midway={"route_m": LONG_ROUTE_M, "remain_m": LONG_ROUTE_M - so_far_m}, **kw)

    st_11 = settled(long_midway("11:00", 60_000))     # 60분·60km → 가산 0, 금액 10,700
    st_1230 = settled(long_midway("12:30", 150_000))  # 150분 한 구간 → 장시간 30분, 금액 27,418.75
    late = dict(long, arrived_at=at("13:30"), rest_answer_min=0)
    return [
        ("R34", "쉰 시간 답만(중간 정산 없음): 10:00→14:00, 답 30분, ETA 200분, 맑음·낮, 2명",
         "T = 240 − 30 = 210. 언제 쉬었는지 모르니 가운데에서 105 + 105로 끊음(지금 규칙) → 장시간 0, 평소와 같은 길. "
         "예상보다 더 10분 < ETA의 10% → 0. 금액 37,450 → 약 3만 7천. R35와 같은 주행", long),
        ("R35", "R34와 같은 주행 + 11:00 중간 정산(답 30분): 정산 시각에서만 끊음",
         "중간 정산이 있으면 Q는 T에서 빼기만(쉰 곳 = 그 휴게소). 구간 = 벽시계 × (T ÷ E) = 60 × 210/240 = 52.5, "
         "180 × 210/240 = 157.5 → 장시간 37.5분 '2시간 넘긴 38분' +2% → 214.6875 → 1.02배. 금액 38,285.94 → 약 3만 8천, "
         "남은 38,285.94 − 10,700(11:00 정산, 60분·60km 영수증) = 27,585.94 → 한 사람 약 1만 4천, 운전자 몫 → 밥",
         dict(long, settlements=[st_11])),
        ("R36", "정산이 늦어 앞 구간만 장시간: 10:00→13:30, 쉰 시간 없음, 12:30 중간 정산",
         "구간 150 + 60분 → 앞 구간만 넘김 '2시간 넘긴 30분' +2% → 213.75 → 1.02배(끊지 않으면 210분 한 구간, 90분 넘김 1.05배). "
         "금액 38,118.75. 12:30 중간 정산 영수증도 150분 한 구간이라 장시간 30분(1.03배, 27,418.75) → "
         "남은 10,700 → 한 사람 5,350 → 약 5천, 운전자 몫 5,350 → 커피",
         dict(late, settlements=[st_1230])),
        ("R37", "두 번째 중간 정산도 앞 정산 시각에서 끊김: R35의 11:00 정산 뒤 13:30·지금까지 195km에서 다시 정산",
         "구간 60 + 150분 → 장시간 30분 +2% → 213.75 → 1.02배(끊지 않으면 210분 한 구간, 90분 넘김 1.05배). "
         "기준 ETA = 200 × 195 ÷ 200 = 195분 → 15분 < 10% → 예상보다 더 0. 거리 표시 195km. "
         "금액 38,118.75, 남은 38,118.75 − 10,700 = 27,418.75 → 한 사람 약 1만 4천",
         long_midway("13:30", 195_000, settlements=[st_11])),
    ]


def effort_fixtures() -> None:
    # 정체 km = 비율 × 경로 거리 = 0.36 × 104,300m = 37,548m(명세 F20). 거리 표시도 같은 경로 거리
    r1 = effort_input("16:00", "18:10", answer=0, eta_min=105, ratio=R1_RATIO, congestion_m=R1_RATIO * R1_ROUTE_M,
                      wdep="rain", warr="rain", n=4, distance_km=R1_ROUTE_M / 1000)
    r13 = effort_input("09:00", "15:00", answer=60, eta_min=300, n=2)
    cases = [
        ("R1", "PRD 예시: 16:00→18:10, ETA 105분, 정체 38km(경로의 36%), 비(출발·도착), 일몰 17:20, 쉰 시간 없음, 4명",
         "AC-F23-1·AC-F24-1·AC-F25-1. 정체 분 = 비율 0.36 × T 130 = 46.8(열린 결정 28). 노고 130 + 비 52 + 정체 12.636 "
         "+ 예상보다 더 6.75(25분) + 밤길 2(50분) + 장시간 1.25(10분) = 204.636 → 1.57배, 정체 줄 +10%. "
         "금액 204.636 × 10,700 ÷ 60 = 36,493.42 → 약 3만 6천, 한 사람 9,123.36 → 약 9천, 운전자 몫 27,370.07 → 밥. "
         "정체 값은 효성 결정(11/7): 35%·12km는 거리 104km와 맞지 않고 4% 단위가 아니어서 36%·38km", r1),
        # R2·R4(자동으로 잰 정차 목록)는 #62에서 지웠다. 번호는 명세·AC·앱 테스트 참조가 안 바뀌게 비워 둔다
        ("R3", "180분 쉬지 않음(쉰 시간 없음, ETA 170분, 맑음·낮, 2명)",
         "장시간 (180 − 120) × 0.125 = 7.5 → 187.5 → 1.04배. 예상보다 더 10분 < ETA의 10%(17분) → 0",
         effort_input("10:00", "13:00", answer=0, eta_min=170, n=2)),
        ("R5", "60분, 출발 맑음·도착 눈, 쉰 시간 없음, ETA 60분, 낮, 3명",
         "눈은 T의 뒤 절반 30분 × 0.49 = 14.7 → 74.7. 74.7 ÷ 60 = 1.245 → 1.25(반올림 경계, Decimal과 같게)",
         effort_input("10:00", "11:00", answer=0, eta_min=60, warr="snow", n=3)),
        ("R6", "10분, 맑음·낮, 2명, 거리 0.4km",
         "금액 1,783.3 → 약 2천, 한 사람 891.7 → 약 1천. 운전자 몫 891.7 < 커피 절반 2,350 → B 제안 없음. "
         "거리 0.4km는 어림이 0이라 '0km'가 아니라 '1km 미만'(명세 2절, June 리뷰)",
         effort_input("10:00", "10:10", answer=0, eta_min=10, n=2, distance_km=0.4)),
        ("R7", "지연 문턱: ETA 60분, T 66분, 맑음·낮, 2명",
         "예상보다 더 6분 = ETA의 10%라 문턱(10% 미만이면 0)에 걸리지 않고 셈에 들어간다 → 67.62. 운전자 몫 6,029.45 → 커피",
         effort_input("10:00", "11:06", answer=0, eta_min=60, n=2)),
        ("R8", "혼자: R1과 같은 주행, 1명",
         "나눠 낼 인원 1명이면 영수증만 보여 주고 제안(A·B)은 하지 않는다(명세 3절) → proposal null", dict(r1, n=1)),
        ("R9", "기본(도착 때 묻기): 10:00→12:40, 쉰 시간 답 30분, ETA 130분, 맑음·낮, 2명",
         "AC-F21-1. 경과 160 − 답 30 = T 130. 언제 쉬었는지 모르니 65 + 65로 나눠 장시간 0"
         "(쉬지 않은 130분 한 구간이면 장시간 10 × 0.125 = 1.25). 130 → 평소와 같은 길",
         effort_input("10:00", "12:40", answer=30, eta_min=130, n=2)),
        # ---- 하네스 추가: 명세 표에 없는 경로(밤 경계·정보 없음·구간 목록)를 엔진 두 벌이 같게 내는지
        ("R10", "답 + 밤길 비례 + 정보 없음: 16:30→19:30, 쉰 시간 30분, ETA 140분, 날씨·정체 정보 없음, 3명",
         "T = 180 − 30 = 150(75 + 75, 장시간 0). 밤 = 일몰 17:20~19:30 130분 × (150 ÷ 180) = 108.3분 → 4.33. "
         "날씨 '정보 없음'(가산 0), 출발 전 정체 비율도 구간 목록도 없음 → '정체 정보 없음'(F20·X2)",
         effort_input("16:30", "19:30", answer=30, eta_min=140, ratio=None, congestion_m=None, wdep="unknown",
                      warr="unknown", n=3)),
        ("R11", "새벽 출발: 05:30→08:00, 쉰 시간 없음, 일출 07:13, ETA 150분, 맑음, 2명",
         "전날 일몰 ~ 그날 일출 구간과 겹친 05:30~07:13 = 103분이 밤길 → 4.12. 장시간 30 × 0.125 = 3.75",
         effort_input("05:30", "08:00", answer=0, eta_min=150, n=2)),
        ("R12", "자정 넘김 + 쉰 시간 답 15분: 22:00 출발, 02:15 도착, ETA 240분, 2명",
         "T = 255 − 15 = 240. 답이 15분 '이상'이면 가운데에서 나눈다 → 120 + 120, 장시간 0. "
         "날짜를 넘겨도 밤 = 255분 × (240 ÷ 255) = 240 → 9.6",
         effort_input("22:00", "02:15", answer=15, eta_min=240, n=2, arr_day=NEXT_DAY)),
        ("R13", "답 '1시간 이상'(60분): 09:00→15:00, ETA 300분, 맑음·낮, 2명",
         "#47 표 '5시간 + 점심 1시간'. 언제 쉬었는지 모르니 T 300을 150 + 150으로 나눔 → 장시간 2 × 30 × 0.125 = 7.5. "
         "307.5 ÷ 300 = 1.025 → 1.03(반올림 경계)", r13),
        ("R14", "나란히 배치로 정체 구간 목록을 받은 R1: 정체 통과 시간 합 40분, 비율 36%·38km도 있음, 4명",
         "구간 목록이 있으면 비율 × T(46.8분) 대신 통과 시간 40분을 쓴다(명세 7절). 노고 202.8 → 1.56배, 정체 +8%, "
         "금액 36,166 → 약 3만 6천, 한 사람 약 9천, 운전자 몫 27,124.5 → 밥. 정체 줄 문구는 그대로 '38km (경로의 36%)'",
         dict(r1, congestion_s=2400)),
        ("R15", "공동 경비: R13과 같은 주행(09:00→15:00, 답 60분, ETA 300분, 맑음·낮), 4명",
         "금액 54,837.5 → 운전자 몫 54,837.5 × 3 ÷ 4 = 41,128.1 ≥ 39,855.5 → 공동 경비. "
         "B 문장은 '공동 경비에서'('값' 없음, 명준 결정), item_examples = 그 자리에 차례로 보일 예시(constants effort.items)",
         dict(r13, n=4)),
        # ---- 도착 상한(#68): 경과(쉰 시간 빼지 않음)가 max(ETA × 2, ETA + 60분) 또는 12시간을 "넘으면" 정리하지 못함
        ("R16", "도착 상한 경계(같음 → 영수증): R1에서 도착만 19:30(경과 210분 = ETA 105 × 2)",
         "상한 = max(105 × 2, 105 + 60) = 210분. 경과가 상한과 같으면 영수증(status ok)",
         dict(r1, arrived_at=at("19:30"))),
        ("R17", "도착 상한 경계(넘음 → 정리하지 못함): R1에서 도착만 19:31(경과 211분)",
         "211 > 210 → status unsettled. 금액·줄은 계산하지 않고 headline_text에 안내 문구(effort.copy.unsettled_text)",
         dict(r1, arrived_at=at("19:31"))),
        ("R18", "도착 상한 경계(같음 → 영수증): ETA 10분, 10:00→11:10(경과 70분), 맑음·낮, 2명",
         "상한 = max(10 × 2, 10 + 60) = 70분(짧은 길은 +60분 쪽이 큼). 경과 70 = 상한 → 영수증",
         effort_input("10:00", "11:10", eta_min=10, n=2)),
        ("R19", "도착 상한 경계(넘음 → 정리하지 못함): ETA 10분, 10:00→11:11(경과 71분)",
         "71 > 70 → status unsettled",
         effort_input("10:00", "11:11", eta_min=10, n=2)),
        ("R20", "도착 상한 경계(12시간, 같음 → 영수증): ETA 400분, 08:00→20:00(경과 720분), 맑음, 2명",
         "max(400 × 2, 400 + 60) = 800분보다 12시간(720분)이 먼저 걸림 → 상한 720. 경과 720 = 상한 → 영수증",
         effort_input("08:00", "20:00", eta_min=400, n=2)),
        ("R21", "도착 상한 경계(12시간 넘음 → 정리하지 못함): ETA 400분, 08:00→20:01(경과 721분)",
         "721 > 720 → status unsettled",
         effort_input("08:00", "20:01", eta_min=400, n=2)),
        # ---- 시간대별 강수(#63, GET /v1/weather/trip). R16~R21은 #71(도착 상한) 몫
        ("R22", "시간대별: 출발 비 → 중간에 그침. 14:20→16:45, ETA 140분, 출발 1회 관측 비·도착 맑음, 2명",
         "조각 [14,15)는 가운데 14:30이 경과 가운데 15:32:30보다 앞 → 출발 지점 '비', 출발~도착과 겹친 40분. "
         "[15,16) 출발 지점 맑음, [16,17) 도착 지점 맑음 → 비 40분 → 0.40 × 40 = 16 → +11%. "
         "1회 관측 절반 규칙이면 비 72.5분(+20%)이었음",
         effort_input("14:20", "16:45", eta_min=140, wdep="rain", n=2,
                      trip=trip_hours(14, ["rain", "none", "none"], ["none", "none", "none"]))),
        ("R23", "시간대별: 출발·도착 맑음, 중간에만 비 + 쉰 시간 30분. 13:00→17:00, ETA 210분, 2명",
         "경과 가운데 15:00. [13,14)·[14,15)는 출발 지점(맑음·비), [15,16)·[16,17)는 도착 지점(비·맑음) → 비 벽시계 120분. "
         "강수 분 = 120 × T 210 ÷ 경과 240 = 105(밤길과 같은 방식) → 42 → +20%. 1회 관측으로는 0",
         effort_input("13:00", "17:00", answer=30, eta_min=210, n=2,
                      trip=trip_hours(13, ["none", "rain", "none", "none"], ["none", "none", "rain", "none"]))),
        ("R24", "시간대별 partial(unknown 섞임): 출발 눈 → 그침, 도착 지점 일부 정보 없음. 14:20→16:45, ETA 140분, 2명",
         "[14,15) 출발 지점 눈 40분, [15,16) 출발 지점 맑음, [16,17) 도착 지점 unknown(45분, 가산 0) → 눈 40분 → "
         "0.49 × 40 = 19.6 → +14%. 겹친 조각에 unknown이 있어 '일부 시간은 날씨 정보 없음'(effort.copy 임시)",
         effort_input("14:20", "16:45", eta_min=140, wdep="snow", n=2,
                      trip=trip_hours(14, ["snow", "none", "none"], ["none", "unknown", "unknown"]))),
        ("R25", "시간대별 경계: 조각 가운데 = 경과 가운데. 16:00→17:00 한 조각, 출발 지점 비·도착 지점 맑음, ETA 60분, 2명",
         "조각 [16,17) 가운데 16:30 = 경과 가운데 16:30 → 출발 지점(앞 절반에 넣는 규칙) → 비 60분 = 주행 내내 → "
         "'비, 주행 내내' +40%. 도착 지점을 쓰는 규칙이었다면 0",
         effort_input("16:00", "17:00", eta_min=60, wdep="rain", n=2,
                      trip=trip_hours(16, ["rain"], ["none"]))),
        ("R26", "시간대별 전부 unknown(unavailable) → 1회 관측 규칙으로: R22와 같은 주행",
         "겹친 조각이 모두 unknown이면 엔진도 weather_dep·weather_arr 절반씩으로 돌아간다(계약의 unavailable 대체와 같음) → "
         "비 72.5분 → 29 → +20%",
         effort_input("14:20", "16:45", eta_min=140, wdep="rain", n=2,
                      trip=trip_hours(14, ["unknown"] * 3, ["unknown"] * 3))),
    ]
    cases += midway_cases(r1)
    cases += fatigue_split_cases()
    for fid, title, note, inp in cases:
        write(ROOT / "fixtures/effort" / f"{fid}.json", effort_fixture(fid, title, note, inp))

    demo_fixture(r1)

    sun = []
    for day, kasi in sorted(KASI_SEOUL["days"].items()):
        rise, sset = EF.sun_times(KASI_SEOUL["lat"], KASI_SEOUL["lng"], day)
        sun.append({"date": day, "expected": {"sunrise": EF.hhmm(rise), "sunset": EF.hhmm(sset)}, "kasi": kasi})
    write(ROOT / "fixtures/effort/sun.json", {
        "id": "SUN", "title": f"일출·일몰: {KASI_SEOUL['place']}({KASI_SEOUL['lat']}, {KASI_SEOUL['lng']}), KST",
        "note": "expected = 엔진 계산값(분 단위 반올림, 두 벌이 같아야 함). kasi = 한국천문연구원 출몰시각(참고, ±1분). "
                f"출처: {KASI_SEOUL['source']}",
        "lat": KASI_SEOUL["lat"], "lng": KASI_SEOUL["lng"], "tz_offset_min": 540, "cases": sun})


def kma_ncst(lat: float, lng: float, pty: str, base_date: str, base_time: str, rn1: str = "1.5") -> str:
    """기상청 getUltraSrtNcst 응답과 같은 모양(8개 항목, obsrValue는 문자열). 계약 샘플을 만들 때만 쓴다."""
    nx, ny = W.to_grid(lat, lng)
    vals = {"PTY": pty, "REH": "88", "RN1": rn1, "T1H": "9.4", "UUU": "-1.2", "VEC": "310", "VVV": "1.4", "WSD": "1.9"}
    items = [{"baseDate": base_date, "baseTime": base_time, "category": c, "nx": nx, "ny": ny, "obsrValue": v}
             for c, v in vals.items()]
    return json.dumps({"response": {"header": {"resultCode": "00", "resultMsg": "NORMAL_SERVICE"},
                                    "body": {"dataType": "JSON", "items": {"item": items},
                                             "pageNo": 1, "numOfRows": 10, "totalCount": len(items)}}})


# 카카오 로컬 키워드 검색 응답 모양(query=두물머리, 11/7 실호출의 이름·주소·id·x·y 그대로). 계약 샘플을 만들 때만 쓴다.
# 첫 곳은 도로명 주소가 없어 지번 주소로, 둘째 곳은 도로명 주소로 나간다. 분류는 category_name 마지막 칸, 거리는 x·y를 보낸 호출의 값.
KAKAO_DUMUL_DOCS = [
    {"id": "7845373", "place_name": "두물머리", "address_name": "경기 양평군 양서면 양수리", "road_address_name": "",
     "category_name": "여행 > 관광,명소", "x": "127.315829778049", "y": "37.5328127891601", "distance": "23671"},
    {"id": "2141791502", "place_name": "양서농협 두물머리로컬푸드직매장", "address_name": "경기 양평군 양서면 양수리 1161-5",
     "road_address_name": "경기 양평군 양서면 두물머리길 38", "category_name": "가정,생활 > 슈퍼마켓 > 대형슈퍼 > 하나로마트",
     "x": "127.31911437324305", "y": "37.54082012837359", "distance": "24417"},
]


def kakao_keyword(docs: list[dict]) -> str:
    return json.dumps({"documents": docs, "meta": {"is_end": True, "pageable_count": len(docs),
                                                   "total_count": len(docs)}}, ensure_ascii=False)


def main() -> None:
    effort_fixtures()

    # ---------------- 카드 (D1~D4: 엔진 PRD 7절, D7~: 하네스 추가)
    # PT 콘솔 값(DECISIONS "PT 콘솔 값"): 두물머리가 앞면이 되는 D1 범위 200~280km의 가운데, 같은 카드가 +1만이 되는 D2 범위 60~120km의 가운데
    d1_in = {"model": "ioniq5_lr", "level_pct": 80, "remaining_range_m": 245_000, "price": 300}
    d1 = card_fixture(
        "D1", "기본: 아이오닉 5 LR, SOC 80%, Remaining Range 245km, 착석 1, 집 단가 300",
        f"AC-F1-1·AC-F2-1·AC-F5-1. 반경 (245−50)÷2÷1.3 ≈ 75. 앞면 양평 두물머리(편도 {DUMUL_KM}km·{DUMUL_MIN}분, 추가 0원). "
        "교체 2번 뒤 3번째는 swap_limit.",
        {"PUT /vehicle/ev-battery?field=capacity&value=": 77.4,
         "PUT /vehicle/ev-battery?field=soc&value=": 80,
         "PUT /vehicle/odometer?remaining_range_m=": 245_000},
        d1_in, swaps=3)
    write(ROOT / "fixtures/cards/D1.json", d1)

    d2 = card_fixture(
        "D2", "PT 3단계: SOC 35% + Remaining Range 100km — 같은 카드, 배지만 +1만",
        f"같은 session_id로 재호출(1초 디바운스 1건). 반경 (100−50)÷2÷1.3 ≈ 19. 두물머리 왕복 {2 * DUMUL_KM:.1f} > 가용 50 → +1만. "
        "재호출 뒤 교체도 이 값 기준(expected_swaps).",
        {"PUT /vehicle/ev-battery?field=soc&value=": 35, "PUT /vehicle/odometer?remaining_range_m=": 100_000},
        dict(d1_in, level_pct=35, remaining_range_m=100_000), after=d1, swap_after=True)
    write(ROOT / "fixtures/cards/D2.json", d2)

    d3 = card_fixture(
        "D3", "PT 4단계: 착석 4 — 1인 몫",
        "같은 세션 재호출. per_person = total ÷ 4, 천 원 어림(AC-F11-1).",
        {"console_ui": "착석 4 (API 경로 미확인)"}, d1_in, seats=4, after=d1)
    write(ROOT / "fixtures/cards/D3.json", d3)

    d4 = card_fixture(
        "D4", "1박 + 테마 바다",
        "가용 326−50−30 = 246, 반경 ≈ 94.6. 테마는 경계라 바다만 → 후보 2곳 → hint few_candidates(X2).",
        {}, dict(d1_in, days=2), themes=["sea"])
    write(ROOT / "fixtures/cards/D4.json", d4)

    # ---------------- 정산·충전 (D5·D6)
    prev = E.Snapshot(odometer_km=12_340, seats=2, price_per_unit=300, efficiency=EV_EFF,
                      ts="2026-11-01T09:00:00+09:00")
    s = E.settle(prev, 12_472, "양평 두물머리")
    write(ROOT / "fixtures/cards/D5.json", {
        "id": "D5", "title": "앱 열 때 정산: 12,340 → 12,472km, 직전 착석 2, 마지막 목적지 양평 두물머리 (PT와 같은 EV)",
        "note": "132 ÷ 5.27 × 300 = 7,514 → 8,000. 1인 몫 ÷2 → 4,000. PRD 대표 문구 '약 1만 8천 원'과 다름 — DECISIONS 열린 결정.",
        "virtual_console": {"PUT /vehicle/odometer?traveled_distance_km=": "현재값 + 132"},
        "snapshot_prev": vars(prev), "odometer_now": 12_472, "last_destination": "양평 두물머리", "span_days": 0,
        "expected": vars(s)})
    kwh, krw, text = E.charge_amount(20, 80, 77.4, 300)
    write(ROOT / "fixtures/cards/D6.json", {
        "id": "D6", "title": "PT 1단계 충전 직후: SOC 20 → 80, 집 단가 300 → 바로 한 장",
        "note": "46.44kWh × 300 = 13,932 → 14,000 (AC-F9-1, DECISIONS 열린 결정 8 해결). 이어서 F2 기본값으로 /v1/cards → D1과 같은 picked.",
        "virtual_console": {"charge_on": True, "PUT /vehicle/ev-battery?field=soc&value=": 80, "charge_off": True},
        "charge_start_soc": 20, "charge_end_soc": 80, "capacity_kwh": 77.4, "price": 300,
        "expected": {"kwh": E.round1(kwh * 10) / 10, "krw": krw, "text": text, "then": "D1"}})

    # ---------------- 주유 직후 '가득' (D11, F9 내연 금액 시트)
    tank = CONST["vehicles"]["grandeur_hev"]["capacity"]
    gas_d11 = CONST["price"]["gasoline_krw_per_l"]
    liters, krw, text = E.full_tank_amount(25, tank, gas_d11)
    write(ROOT / "fixtures/cards/D11.json", {
        "id": "D11", "title": f"내연 주유 직후 '가득': 그랜저 HEV 탱크 {tank:g}L, 시작 연료 25%, 휘발유 {gas_d11:,}원",
        "note": f"(100 − 25) ÷ 100 × {tank:g} = {liters:g}L × {gas_d11:,} = {liters * gas_d11:,.0f} → 천 원 어림 "
                f"{krw:,}(PRD v1.3 F9 '가득', 5-3).",
        "virtual_console": {"settings": "모의 연료 % 25 → 주유 뒤 3%p 이상 상승(AC-F9-3) → 금액 시트"},
        "fuel_start_pct": 25, "tank_l": tank, "price": gas_d11,
        "expected": {"liters": E.round1(liters * 10) / 10, "krw": krw, "text": text}})

    # ---------------- 앱 로컬 경계 (D7~D10)
    write(ROOT / "fixtures/cards/D7.json", local_fixture(
        "D7", "Remaining Range 0 → 조립식", "AC-F1-2. 61.92kWh × 6.2 × 0.85 ≈ 326km, range_source=assembled.",
        {"PUT /vehicle/odometer?remaining_range_m=": 0}, dict(d1_in, remaining_range_m=0)))
    write(ROOT / "fixtures/cards/D8.json", local_fixture(
        "D8", "콘솔 용량 60kWh → INFO_EV_BATTERY_CAPACITY 우선", "AC-F1-5. 0.8 × 60 × 300 = 14,400 → 1만 4천 원. km는 주행가능거리.",
        {"PUT /vehicle/ev-battery?field=capacity&value=": 60}, dict(d1_in, capacity_sdk=60)))
    d9_in = dict(d1_in, level_pct=12, remaining_range_m=40_000, extra_krw=10_000)
    write(ROOT / "fixtures/cards/D9.json", card_fixture(
        "D9", "잔고 40km < 안전 여유 → 추가 0원 비활성, +1만 기본",
        "AC-F2-2. zero_available=false → 앱이 extra_krw=10000으로 요청.",
        {"PUT /vehicle/ev-battery?field=soc&value=": 12, "PUT /vehicle/odometer?remaining_range_m=": 40_000}, d9_in))
    write(ROOT / "fixtures/cards/D10.json", card_fixture(
        "D10", "내연: 아반떼 HEV, 모의 연료 50%, 단가 미설정(앱 기본값), 착석 2",
        "에뮬은 내연 연료 %가 없어 설정 모의 값. Remaining Range 0 → 조립식. 배지는 '추가 주유 없이'.",
        {"settings": "모의 연료 % = 50"},
        {"model": "avante_hev", "level_pct": 50, "remaining_range_m": 0}, seats=2))

    # ---------------- T1~T10 정산 경계 케이스 (엔진 PRD 8절)
    base = dict(odometer_km=10_000, seats=1, price_per_unit=300, efficiency=EV_EFF, ts="2026-11-01T09:00:00+09:00")
    gas = CONST["price"]["gasoline_krw_per_l"]
    cases = [
        ("T1", "중간 주유·충전: Δodo 300, 잔량 상승 — 주행거리 기준 그대로", dict(base), 10_300, "두물머리", 0),
        ("T2", "며칠 만에 열기: 3일 · 412km", dict(base), 10_412, "강릉", 3),
        ("T3", "착석 변화: 직전 4, 지금 1 → 1인 몫은 ÷4", dict(base, seats=4), 10_132, "두물머리", 0),
        ("T4", "최근 목적지 없음 → 핀 없이 금액만", dict(base), 10_050, None, 0),
        ("T5", "주행거리 0 → 정산 카드 생략", dict(base), 10_000, "두물머리", 0),
        ("T6", "단가 결측 → 앱 기본값 + '평균 단가'", dict(base, price_source="default"), 10_132, "두물머리", 0),
        ("T7", "직전 스냅샷 없음 → 저장만", None, 10_132, "두물머리", 0),
        ("T8", "주행거리 감소(콘솔 오입력·초기화) → 생략", dict(base), 9_900, "두물머리", 0),
        ("T9", f"내연: 직전 연비 L/100km 8.0 → 12.5km/L, 휘발유 {gas:,}",
         dict(base, price_per_unit=gas, efficiency=E.l_per_100km_to_km_per_l(8.0)), 10_132, "두물머리", 0),
        ("T10", "1km만 이동 → '1천 원 미만'(0 원 표기 금지)", dict(base), 10_001, "두물머리", 0),
    ]
    for tid, title, prev_d, now, dest, days in cases:
        p = E.Snapshot(**prev_d) if prev_d else None
        write(ROOT / "fixtures/settle" / f"{tid}.json", {
            "id": tid, "title": title, "snapshot_prev": prev_d, "odometer_now": now, "last_destination": dest,
            "span_days": days, "expected": vars(E.settle(p, now, dest, days))})

    # ---------------- L1~L5 로컬 장부 (F8·F10·F17)
    write(ROOT / "fixtures/ledger/L1.json", ledger_fixture(
        "L1", "PT 5~6단계(에뮬): 출발 → 기어 P에서 운전석 문(Δodo 0) → 다시 열기",
        f"도착 Δodo 0 → 편도 {DUMUL_KM}km 추정, demo=true('시연용 값'). 도착이 스냅샷을 갱신해 다음 열기는 정산 없음(AC-F17-4).",
        [ev_open("2026-12-03T14:00:00+09:00", 12_472, seats=4),
         {"type": "depart", "ts": "2026-12-03T14:05:00+09:00", "odometer_km": 12_472, "place": DUMUL, "route_km": DUMUL_KM},
         {"type": "arrive", "ts": "2026-12-03T14:06:00+09:00", "odometer_km": 12_472, "seats": 4,
          "price": 300, "efficiency": EV_EFF},
         ev_open("2026-12-03T14:10:00+09:00", 12_472, seats=4, recent=DUMUL)],
        "2026-12"))
    write(ROOT / "fixtures/ledger/L2.json", ledger_fixture(
        "L2", "실차: 출발 뒤 앱이 가려진 채 도착 → 다음 열기가 trip을 닫음(내연, 착석 2)",
        "도착 이벤트를 못 받아도 다음 정산이 trip을 닫고 목적지는 trip 장소. 110 ÷ 12.5 × 1,856 → 1만 6천, 1인 8천.",
        [ev_open("2026-11-08T09:00:00+09:00", 10_000, seats=2, price=gas, eff=12.5),
         {"type": "depart", "ts": "2026-11-08T09:02:00+09:00", "odometer_km": 10_000, "place": DUMUL, "route_km": DUMUL_KM},
         ev_open("2026-11-08T18:30:00+09:00", 10_110, seats=2, recent=DUMUL, price=gas, eff=12.5)],
        "2026-11"))
    write(ROOT / "fixtures/ledger/L3.json", ledger_fixture(
        "L3", "핀 중복: 직전 핀과 같은 최근 목적지 → 금액만 쌓음, 다른 곳이면 핀 추가",
        "AC-F10-6. 같은 장소 = 이름 같음 또는 300m 안.",
        [ev_open("2026-11-02T10:00:00+09:00", 10_132, recent=DUMUL),
         ev_open("2026-11-03T10:00:00+09:00", 10_200, recent=PLACE["p_namhansanseong"])],
        "2026-11",
        state0={"snapshot": dict(base), "trip": None, "rows": [], "last_pin": {"name": DUMUL["name"],
                "lat": DUMUL["lat"], "lng": DUMUL["lng"]}}))
    write(ROOT / "fixtures/ledger/L4.json", ledger_fixture(
        "L4", "월 경계: 10월 기록은 11월 합계에서 빠진다",
        "AC-F17-2. 기록의 to_ts 월 기준.",
        [ev_open("2026-10-30T09:00:00+09:00", 20_000),
         ev_open("2026-10-31T19:00:00+09:00", 20_100, recent=PLACE["p_namhansanseong"]),
         ev_open("2026-11-02T19:00:00+09:00", 20_150, recent=PLACE["p_seoul_forest"])],
        "2026-11"))
    write(ROOT / "fixtures/ledger/L5.json", ledger_fixture(
        "L5", "며칠 만에 열기 + 주행거리 감소(콘솔 초기화) 뒤 기준 재설정",
        "감소하면 정산 없이 스냅샷을 지금 값으로 다시 잡는다(그러지 않으면 원래 값을 넘을 때까지 정산이 멈춤).",
        [ev_open("2026-11-01T09:00:00+09:00", 30_000),
         ev_open("2026-11-04T09:30:00+09:00", 30_412, recent={"name": "강릉 경포해변", "lat": 37.8055, "lng": 128.9087}),
         ev_open("2026-11-04T10:00:00+09:00", 500),
         ev_open("2026-11-04T18:00:00+09:00", 510)],
        "2026-11"))

    write(ROOT / "fixtures/ledger/L6.json", ledger_fixture(
        "L6", "평균 단가로 출발·도착 → 다음 열기 정산에도 '(평균 단가)'",
        "도착 이벤트도 단가 출처(price_source)를 스냅샷에 남긴다. 37 ÷ 5.27 × 300 = 2,106 → 약 2천 원 (평균 단가). "
        "도착 스냅샷을 user로 고정하면 이 문구에서 '(평균 단가)'가 빠진다(PR #28 리뷰).",
        [ev_open("2026-11-07T09:00:00+09:00", 15_000, src="default"),
         {"type": "depart", "ts": "2026-11-07T09:05:00+09:00", "odometer_km": 15_000, "place": DUMUL, "route_km": DUMUL_KM},
         {"type": "arrive", "ts": "2026-11-07T10:00:00+09:00", "odometer_km": 15_037, "seats": 1,
          "price": 300, "efficiency": EV_EFF, "price_source": "default"},
         ev_open("2026-11-07T18:00:00+09:00", 15_074, src="default")],
        "2026-11"))

    # ---------------- 계약 샘플 (contract/samples) — D1에서 그대로
    write(ROOT / "contract/samples/D1.request.json", d1["request"])
    write(ROOT / "contract/samples/D1.response.json", d1["expected"])
    write(ROOT / "contract/samples/D2.recall.request.json", dict(d2["request"], session_id="<D1 응답의 session_id>"))
    # GET /v1/weather (명세 v0.5 12절). 데모 출발지에서 R1처럼 비(PTY 1), 실패하면 unknown(AC-C-6)
    precip, observed_at = W.parse_ncst(kma_ncst(ORIGIN["lat"], ORIGIN["lng"], "1", "20261107", "1600"))
    write(ROOT / "contract/samples/weather.rain.response.json", W.Result(precip, observed_at).public())
    write(ROOT / "contract/samples/weather.unknown.response.json", W.unknown("timeout").public())
    # GET /v1/weather/trip (#63). 데모 출발지 → 두물머리, 14:20~16:45 출발, 16:50에 조회(최근 발표분 16:00)
    # ok: 출발 때 오던 비가 15시 전에 그침(15:00 실황 PTY 0·RN1 2.0 → 14시 조각 비). 도착 지점은 내내 맑음
    # partial: 도착 지점 16:00분이 3초를 넘김 → 15·16시 조각 unknown(16시 조각은 발표 전이라 16:00분을 같이 씀)
    # unavailable: 서버 키 없음 등 → 전부 unknown
    trip_dep = (ORIGIN["lat"], ORIGIN["lng"])
    trip_arr = (float(KAKAO_DUMUL_DOCS[0]["y"]), float(KAKAO_DUMUL_DOCS[0]["x"]))
    trip_at = {"now": datetime(2026, 11, 7, 16, 50, tzinfo=W.KST)}
    trip_span = (datetime(2026, 11, 7, 14, 20, tzinfo=W.KST), datetime(2026, 11, 7, 16, 45, tzinfo=W.KST))
    by_grid = {W.to_grid(*trip_dep): trip_dep, W.to_grid(*trip_arr): trip_arr}

    def trip_fetch(fail_arr_bt: str | None = None):
        def fetch(nx: int, ny: int, bd: str, bt: str) -> str:
            pt = by_grid[(nx, ny)]
            if pt == trip_arr and bt == fail_arr_bt:
                raise W.WeatherError("timeout")
            rn1 = "2.0" if pt == trip_dep and bt == "1500" else "0"
            return kma_ncst(*pt, "0", bd, bt, rn1=rn1)
        return fetch

    for name, key, fetch in (("ok", "k", trip_fetch()), ("partial", "k", trip_fetch("1600")),
                             ("unavailable", None, trip_fetch())):
        res = W.trip(trip_dep, trip_arr, *trip_span, key, fetch=fetch, **trip_at)
        assert res.status == name, (name, res.status)
        write(ROOT / f"contract/samples/weather.trip.{name}.response.json", res.public())
    # GET /v1/places (노고 PRD v1.3 8장, #56). 결과 있음 / 결과 없음 / 실패(카카오 오류·3초 초과)
    found = PL.parse_keyword(kakao_keyword(KAKAO_DUMUL_DOCS))
    write(ROOT / "contract/samples/places.ok.response.json", PL.Result("ok", tuple(found)).public())
    write(ROOT / "contract/samples/places.empty.response.json", PL.Result("empty").public())
    write(ROOT / "contract/samples/places.unavailable.response.json", PL.unavailable("timeout").public())

    sweep_stale("fixtures/cards", "fixtures/settle", "fixtures/ledger", "fixtures/effort", "contract/samples")


if __name__ == "__main__":
    main()
