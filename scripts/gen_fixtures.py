"""fixtures/ 와 contract/samples/ 생성. 손으로 고치지 않는다.

엔진(server/chagok/*.py)이나 상수(fixtures/constants.json)·후보(fixtures/candidates_demo.json)가 바뀌면:
    python scripts/gen_fixtures.py        (= make fixtures)
서버 pytest와 앱 JUnit(app/android/app/src/test/.../FixturesTest)이 같은 파일을 읽는다. CI는 재생성 후 diff가 없어야 통과한다.

fixture 종류
- cards/D*.json   app_local(앱 로컬 E1 기대값) → request(앱이 보낼 요청) → expected(서버·오프라인 데모 응답)
- settle/T*.json  E4 정산 경계 케이스
- ledger/L*.json  F8·F10·F17 로컬 장부(출발·도착·다시 열기·핀·월 합계) 이벤트 순서
- effort/R*.json  운전 노고(명세 v0.5 F23~F25) input → expected(영수증·제안). R1~R8 = 명세 12절 표, R9 = AC-F21-1, R10~ = 하네스 추가
- effort/sun.json 일출·일몰 계산값(엔진 두 벌 대조) + 한국천문연구원 출몰시각(참고, pytest가 ±1분 대조)

계약 샘플(contract/samples)
- D1·D2           /v1/cards (D1·D2 fixture에서 그대로)
- weather.*       GET /v1/weather 응답. 기상청 초단기실황 모양의 고정 응답을 chagok.weather로 해석한 값
"""

from __future__ import annotations

import copy
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))

from chagok import effort as EF  # noqa: E402
from chagok import engine as E  # noqa: E402
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


def at(hm: str, day: str = DAY) -> str:
    return f"{day}T{hm}:00+09:00"


def effort_input(dep: str, arr: str, *, eta_min: float, n: int, answer: int | None = None,
                 stops: list[tuple[str, str]] | None = None, ratio: float | None = 0.0,
                 congestion_km: float | None = 0.0, segments_min: float | None = None, wdep: str = "none",
                 warr: str = "none", distance_km: float | None = None, arr_day: str = DAY) -> dict:
    """정차: answer = 기본(도착 때 답한 쉰 시간, 분), stops = 보조(자동으로 잰 정차 목록, 빈 목록이면 정차 없음). 둘 중 하나만.
    정체: ratio·congestion_km = 기본(출발 전 requestTrip 비율·km, 없으면 None), segments_min = 보조(구간 목록 통과 시간)."""
    assert (stops is None) != (answer is None)
    return {
        "departed_at": at(dep), "arrived_at": at(arr, arr_day),
        "rest_answer_min": answer,
        "stops": [{"start": s, "end": e} for s, e in stops] if stops is not None else None,
        "eta_s": eta_min * 60,
        "congestion_ratio": ratio,
        "congestion_m": congestion_km * 1000 if congestion_km is not None else None,
        "congestion_s": segments_min * 60 if segments_min is not None else None,
        "weather_dep": wdep, "weather_arr": warr, **SUN, "n": n, "distance_km": distance_km,
    }


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


def effort_fixtures() -> None:
    r1 = effort_input("16:00", "18:10", answer=0, eta_min=105, ratio=0.35, congestion_km=12, wdep="rain",
                      warr="rain", n=4, distance_km=104.3)
    cases = [
        ("R1", "PRD 예시: 16:00→18:10, ETA 105분, 정체 12km(경로의 35%), 비(출발·도착), 일몰 17:20, 쉰 시간 없음, 4명",
         "AC-F23-1·AC-F24-1·AC-F25-1. 정체 분 = 비율 0.35 × T 130 = 45.5(열린 결정 28). 노고 130 + 비 52 + 정체 12.285 "
         "+ 예상보다 더 6.75(25분) + 밤길 2(50분) + 장시간 1.25(10분) = 204.285 → 1.57배, 정체 줄 +9%. "
         "금액 204.285 × 10,700 ÷ 60 = 36,430.825 → 약 3만 6천, 한 사람 9,107.7 → 약 9천, 운전자 몫 27,323.1 → 밥", r1),
        ("R2", "보조(나란히 배치): 10:00 출발, 12:00~12:20 정차, 13:20 도착, ETA 170분, 맑음·낮, 2명",
         "AC-F21-2. 자동으로 잰 정차 20분(15분 이상)에서 장시간 구간이 120분 + 60분으로 나뉘어 장시간 0. "
         "예상보다 더 10분 < ETA의 10%(17분) → 0. 180 → 평소와 같은 길",
         effort_input("10:00", "13:20", stops=[(at("12:00"), at("12:20"))], eta_min=170, n=2)),
        ("R3", "R2와 같은 거리를 쉬지 않고 180분(쉰 시간 없음, ETA 170분, 맑음·낮, 2명)",
         "장시간 (180 − 120) × 0.125 = 7.5 → 187.5 → 1.04배. R2와 비교하면 쉰 효과가 보인다",
         effort_input("10:00", "13:00", answer=0, eta_min=170, n=2)),
        ("R4", "보조(나란히 배치): 10:00→12:40, 11:30~11:40 정차(10분), ETA 150분, 2명",
         "AC-F21-3. 10분 정차는 시간만 빠지고 장시간 구간은 이어진다(90 + 60 = 150) → 3.75. "
         "153.75 ÷ 150 = 1.025 → 1.03(반올림 경계)",
         effort_input("10:00", "12:40", stops=[(at("11:30"), at("11:40"))], eta_min=150, n=2)),
        ("R5", "60분, 출발 맑음·도착 눈, 쉰 시간 없음, ETA 60분, 낮, 3명",
         "눈은 T의 뒤 절반 30분 × 0.49 = 14.7 → 74.7. 74.7 ÷ 60 = 1.245 → 1.25(반올림 경계, Decimal과 같게)",
         effort_input("10:00", "11:00", answer=0, eta_min=60, warr="snow", n=3)),
        ("R6", "10분, 맑음·낮, 2명",
         "금액 1,783.3 → 약 2천, 한 사람 891.7 → 약 1천. 운전자 몫 891.7 < 커피 절반 2,350 → B 제안 없음",
         effort_input("10:00", "10:10", answer=0, eta_min=10, n=2)),
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
         effort_input("16:30", "19:30", answer=30, eta_min=140, ratio=None, congestion_km=None, wdep="unknown",
                      warr="unknown", n=3)),
        ("R11", "새벽 출발: 05:30→08:00, 쉰 시간 없음, 일출 07:13, ETA 150분, 맑음, 2명",
         "전날 일몰 ~ 그날 일출 구간과 겹친 05:30~07:13 = 103분이 밤길 → 4.12. 장시간 30 × 0.125 = 3.75",
         effort_input("05:30", "08:00", answer=0, eta_min=150, n=2)),
        ("R12", "보조 + 자정 넘김 + 정확히 15분 정차: 22:00 출발, 00:00~00:15 정차, 02:15 도착, ETA 240분, 2명",
         "15분 '이상'이면 나눈다 → 120 + 120, 장시간 0. 날짜를 넘겨도 밤길 240분 전부 → 9.6",
         effort_input("22:00", "02:15", stops=[(at("00:00", NEXT_DAY), at("00:15", NEXT_DAY))], eta_min=240, n=2,
                      arr_day=NEXT_DAY)),
        ("R13", "답 '1시간 이상'(60분): 09:00→15:00, ETA 300분, 맑음·낮, 2명",
         "#47 표 '5시간 + 점심 1시간'. 언제 쉬었는지 모르니 T 300을 150 + 150으로 나눔 → 장시간 2 × 30 × 0.125 = 7.5. "
         "307.5 ÷ 300 = 1.025 → 1.03(반올림 경계)",
         effort_input("09:00", "15:00", answer=60, eta_min=300, n=2)),
        ("R14", "보조(나란히 배치)로 구간 목록을 받은 R1: 정체 통과 시간 합 40분, 비율 35%·12km도 있음, 4명",
         "구간 목록이 있으면 비율 × T(45.5분) 대신 통과 시간 40분을 쓴다(명세 7절). 노고 202.8 → 1.56배, 정체 +8%, "
         "금액 36,166 → 약 3만 6천, 한 사람 약 9천, 운전자 몫 27,124.5 → 밥. 정체 줄 문구는 그대로 '12km (경로의 35%)'",
         dict(r1, rest_answer_min=None, stops=[], congestion_s=2400)),
    ]
    for fid, title, note, inp in cases:
        write(ROOT / "fixtures/effort" / f"{fid}.json", effort_fixture(fid, title, note, inp))

    sun = []
    for day, kasi in sorted(KASI_SEOUL["days"].items()):
        rise, sset = EF.sun_times(KASI_SEOUL["lat"], KASI_SEOUL["lng"], day)
        sun.append({"date": day, "expected": {"sunrise": EF.hhmm(rise), "sunset": EF.hhmm(sset)}, "kasi": kasi})
    write(ROOT / "fixtures/effort/sun.json", {
        "id": "SUN", "title": f"일출·일몰: {KASI_SEOUL['place']}({KASI_SEOUL['lat']}, {KASI_SEOUL['lng']}), KST",
        "note": "expected = 엔진 계산값(분 단위 반올림, 두 벌이 같아야 함). kasi = 한국천문연구원 출몰시각(참고, ±1분). "
                f"출처: {KASI_SEOUL['source']}",
        "lat": KASI_SEOUL["lat"], "lng": KASI_SEOUL["lng"], "tz_offset_min": 540, "cases": sun})


def kma_ncst(lat: float, lng: float, pty: str, base_date: str, base_time: str) -> str:
    """기상청 getUltraSrtNcst 응답과 같은 모양(8개 항목, obsrValue는 문자열). 계약 샘플을 만들 때만 쓴다."""
    nx, ny = W.to_grid(lat, lng)
    vals = {"PTY": pty, "REH": "88", "RN1": "1.5", "T1H": "9.4", "UUU": "-1.2", "VEC": "310", "VVV": "1.4", "WSD": "1.9"}
    items = [{"baseDate": base_date, "baseTime": base_time, "category": c, "nx": nx, "ny": ny, "obsrValue": v}
             for c, v in vals.items()]
    return json.dumps({"response": {"header": {"resultCode": "00", "resultMsg": "NORMAL_SERVICE"},
                                    "body": {"dataType": "JSON", "items": {"item": items},
                                             "pageNo": 1, "numOfRows": 10, "totalCount": len(items)}}})


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

    sweep_stale("fixtures/cards", "fixtures/settle", "fixtures/ledger", "fixtures/effort", "contract/samples")


if __name__ == "__main__":
    main()
