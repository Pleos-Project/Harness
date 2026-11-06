"""차곡 추정 엔진 — 수식의 기준 구현(Python).

기준: 서버·추정 엔진 PRD v0.1 4절, 기능 명세서 v0.4, PRD v1.2 5-3(표기)·5-4(계산).
앱은 같은 수식을 Kotlin(app/android/app/src/main/java/com/mist/pleos_app/engine/Engine.kt·Ledger.kt)으로 갖는다. 둘 다 fixtures/ 를 통과해야 하고,
이 파일을 바꾸면 `make fixtures` 로 fixture를 다시 만들고 Kotlin도 같은 PR에서 고친다.

포팅 규칙(Kotlin과 숫자가 1원까지 같게):
- 반올림은 round_half_up / round_to / round1 만 쓴다. 파이썬 내장 round()는 은행가 반올림이라 쓰지 않는다.
- 나눗셈·곱셈 순서를 Kotlin과 똑같이 적는다(부동소수 결과가 순서에 따라 달라진다).

단위: 거리 km, 금액 원(정수), 전비 km/kWh, 연비 km/L(내연 SDK값 L/100km는 l_per_100km_to_km_per_l로 환산).
"""

from __future__ import annotations

import math
from dataclasses import dataclass

ROAD_FACTOR = 1.3          # 직선 → 도로 굴곡 계수 [가정]
MARGIN_MIN_KM = 50.0       # 안전 여유 하한 [가정]
MARGIN_RATIO = 0.15        # 안전 여유 비율 [가정, 11/13 민감도 결과로 0.20 가능]
OVERNIGHT_KM = 30.0        # 1박 현지 이동 여유
TIGHT_LOW = 0.90           # 왕복/가용거리 90~100% → "빠듯"
OFFICIAL_TO_REAL = 0.85    # 공인 연비 → 실연비 [가정]. 차종 표 값을 쓸 때만 곱한다
RING_KRW = (0, 10_000, 30_000)
MAX_SWAPS = 2
NEAR_MINUTES = 75          # "75분 안"
FAR_MINUTES = 90           # "조금 먼 곳" 상한. 넘으면 카드로 내지 않는다
PIN_SAME_KM = 0.3          # 같은 장소로 보는 거리(핀 중복 방지)
AVG_PRICE_SOURCES = ("default",)  # 앱 내장 기본 단가 → "평균 단가" 표기


# ---------------------------------------------------------------- 숫자·문구 helpers
def round_half_up(x: float) -> int:
    return int(math.floor(x + 0.5))


def round_to(n: float, unit: int) -> int:
    """unit 단위 반올림(11/7 결정: 반올림). JS: Math.floor((n + unit / 2) / unit) * unit"""
    return int(math.floor((n + unit / 2) / unit) * unit)


def round1(x: float) -> float:
    """소수 첫째 자리 반올림(표시·fixture용)."""
    return math.floor(x * 10 + 0.5) / 10


def l_per_100km_to_km_per_l(l_per_100km: float) -> float:
    """내연 SDK 연비(L/100km) → km/L. 0 이하이면 0."""
    return 100.0 / l_per_100km if l_per_100km > 0 else 0.0


def krw_text(n: int) -> str:
    """천 원 단위 정수 → '1만 9천 원', '8천 원', '12만 원'. 0은 호출하지 않는다(approx 사용)."""
    n = int(n)
    man, rest = divmod(n, 10_000)
    cheon = rest // 1_000
    parts = []
    if man:
        parts.append(f"{man}만")
    if cheon:
        parts.append(f"{cheon}천")
    return (" ".join(parts) or "0") + " 원"


def approx(n: float, unit: int) -> tuple[int, str, bool]:
    """unit 단위 어림. 0으로 떨어지면 '{unit} 미만'(표기 규칙: '0 원'은 화면에 내지 않는다).
    반환: (어림값, 문구, 미만 여부)"""
    r = round_to(n, unit)
    if r > 0:
        return r, krw_text(r), False
    return 0, f"{krw_text(unit)} 미만", True


# ---------------------------------------------------------------- 입력
@dataclass
class Vehicle:
    fuel_type: str                 # "ev" | "ice"
    level_pct: float               # SOC % 또는 연료 %
    capacity: float                # kWh 또는 L
    efficiency: float              # km/kWh 또는 km/L (이미 실연비)
    price_per_unit: float          # 원/kWh 또는 원/L
    remaining_range_m: float = 0   # AAOS RANGE_REMAINING. 0이면 조립식
    price_source: str = "user"     # user | default(앱 내장 기본값 → "평균 단가")


def vehicle_inputs(const: dict, model: str, level_pct: float, *, remaining_range_m: float = 0,
                   capacity_sdk: float | None = None, efficiency_sdk: float | None = None,
                   price: float | None = None) -> Vehicle:
    """F1 입력 우선순위(명세서 4절 F1, S1).
    - 용량: SDK/AAOS 값(kWh·L로 환산해 넘김) > 차종 표
    - 연비: SDK 값(실연비 그대로) > 차종 표 × 0.85
    - 단가: 설정값 > 앱 내장 기본값(price_source="default" → '평균 단가')
    """
    spec = const["vehicles"][model]
    ev = spec["fuel_type"] == "ev"
    if price is None:
        price = const["price"]["home_charge_krw_per_kwh" if ev else "gasoline_krw_per_l"]
        source = "default"
    else:
        source = "user"
    return Vehicle(
        fuel_type=spec["fuel_type"],
        level_pct=level_pct,
        capacity=capacity_sdk if capacity_sdk else spec["capacity"],
        efficiency=efficiency_sdk if efficiency_sdk else spec["official_efficiency"] * OFFICIAL_TO_REAL,
        price_per_unit=price,
        remaining_range_m=remaining_range_m,
        price_source=source,
    )


# ---------------------------------------------------------------- E1 잔고·범위 (앱 로컬)
@dataclass
class Budget:
    """앱이 /v1/cards 로 보내는 값. 잔량·주행가능거리·주행거리 같은 원시 차량값이 아니라
    앱이 로컬에서 계산한 결과(파생값)만 담는다(PRD 5-2, 7-1)."""
    fuel_type: str
    usable_km: float               # 잔고 km − 안전 여유 − (1박 30)
    margin_km: float               # 배지 "여유 {n}km"
    efficiency: float              # 실연비
    price_per_unit: float
    price_source: str = "user"


@dataclass
class RangeResult:
    balance_km: float
    balance_krw: int               # 천 원 반올림
    margin_km: float
    usable_km: float
    radius_km: float
    range_source: str              # "range_remaining" | "assembled"
    zero_available: bool           # False → 추가 0원 비활성, 기본 +1만 (AC-F2-2)
    balance_text: str              # "배터리에 1만 9천 원어치 · 약 326km"


def balance_km(v: Vehicle) -> tuple[float, str]:
    if v.remaining_range_m and v.remaining_range_m > 0:
        return v.remaining_range_m / 1000.0, "range_remaining"
    return v.level_pct / 100.0 * v.capacity * v.efficiency, "assembled"


def extra_km(b: Budget, extra_krw: int) -> float:
    return extra_krw / b.price_per_unit * b.efficiency if extra_krw else 0.0


def radius_km(b: Budget, extra_krw: int) -> float:
    return max(0.0, (b.usable_km + extra_km(b, extra_krw)) / 2.0 / ROAD_FACTOR)


def compute_range(v: Vehicle, extra_krw: int = 0, days: int = 1) -> RangeResult:
    km, src = balance_km(v)
    krw, krw_txt, under = approx(v.level_pct / 100.0 * v.capacity * v.price_per_unit, 1_000)
    margin = max(MARGIN_MIN_KM, km * MARGIN_RATIO)
    usable = km - margin - (OVERNIGHT_KM if days == 2 else 0.0)
    b = Budget(v.fuel_type, usable, margin, v.efficiency, v.price_per_unit, v.price_source)
    where = "배터리에" if v.fuel_type == "ev" else "탱크에"
    amount = krw_txt if under else f"{krw_txt}어치"
    text = f"{where} {amount} · 약 {round_half_up(km)}km"   # 단가 출처("평균 단가")는 화면에 따로 작게(F1)
    return RangeResult(km, krw, margin, usable, radius_km(b, extra_krw), src, usable > 0, text)


def budget_of(r: RangeResult, v: Vehicle) -> Budget:
    return Budget(v.fuel_type, r.usable_km, r.margin_km, v.efficiency, v.price_per_unit, v.price_source)


def budget_json(b: Budget) -> dict:
    """요청 JSON의 budget. 값을 자르지 않는다 — 앱 화면의 반경과 서버 배지가 같은 숫자에서 나오게."""
    return {"fuel_type": b.fuel_type, "usable_km": b.usable_km, "margin_km": b.margin_km,
            "efficiency": b.efficiency, "price_per_unit": b.price_per_unit, "price_source": b.price_source}


# ---------------------------------------------------------------- E3 비용·배지
@dataclass
class Candidate:
    id: str
    name: str
    theme: str
    lat: float
    lng: float
    route_km: float                # 편도 도로 거리
    minutes: int                   # 편도
    toll_krw: int = 0              # 편도
    parking_krw: int | None = None  # None → 정보 없음
    has_parking_info: bool = False


@dataclass
class Cost:
    fuel: int
    toll: int
    parking: int | None
    total: int                     # 어림 전 합계
    total_text: str                # 5천 원 어림 "1만 원 안팎" / "5천 원 미만"
    per_person: int | None
    per_person_text: str | None


def compute_cost(c: Candidate, b: Budget, seats: int) -> Cost:
    fuel = round_half_up(2 * c.route_km / b.efficiency * b.price_per_unit)
    toll = 2 * c.toll_krw
    parking = c.parking_krw if c.has_parking_info else None
    total = fuel + toll + (parking or 0)
    _, txt, under = approx(total, 5_000)
    total_text = txt if under else f"{txt} 안팎"
    pp = pp_text = None
    if seats >= 2:
        pp, txt, under = approx(total / seats, 1_000)
        pp_text = f"1인 {txt}" if under else f"1인 {txt} 안팎"
    return Cost(fuel, toll, parking, total, total_text, pp, pp_text)


@dataclass
class Badge:
    zero_extra: bool
    tight: bool
    ring_krw: int | None           # 0 / 10000 / 30000 / None(+3만 밖)
    text: str
    margin_km: int


def compute_badge(c: Candidate, b: Budget) -> Badge:
    rt = 2 * c.route_km
    ring = None
    for ring_krw in RING_KRW:
        if rt <= b.usable_km + extra_km(b, ring_krw):
            ring = ring_krw
            break
    zero = ring == 0
    tight = zero and rt >= b.usable_km * TIGHT_LOW
    margin = round_half_up(b.margin_km)
    if tight:
        text = "빠듯"                                   # 경계에서는 0원을 약속하지 않는다(PRD 5-3)
    elif zero:
        word = "충전" if b.fuel_type == "ev" else "주유"
        text = f"추가 {word} 없이 · 여유 {margin}km"
    elif ring is not None:
        text = f"+{krw_text(ring)} 안팎"
    else:
        text = "범위 밖"
    return Badge(zero, tight, ring, text, margin)


def time_label(minutes: int) -> str | None:
    if minutes <= NEAR_MINUTES:
        return "75분 안"
    if minutes <= FAR_MINUTES:
        return "조금 먼 곳"
    return None                                       # 90분 초과는 카드로 내지 않는다


# ---------------------------------------------------------------- E2 점수·이유
WEIGHTS = {"zero": 0.30, "time": 0.25, "theme": 0.20, "diversity": 0.15, "parking": 0.10}
PHRASE = {"zero": "추가 0원 안", "time": "75분 안", "diversity": "지난번과 다른 테마"}
THEME_KO = {"sea": "바다", "lake": "호수", "river": "강", "mountain": "산",
            "park": "공원", "city": "도시", "island": "섬", "drive": "드라이브길"}


def score(c: Candidate, b: Badge, themes: list[str], recent_themes: list[str]) -> dict[str, float]:
    s: dict[str, float] = {}
    s["zero"] = 1.0 if (b.zero_extra and not b.tight) else (0.5 if b.tight else 0.0)
    if c.minutes <= NEAR_MINUTES:
        s["time"] = 1.0
    elif c.minutes <= FAR_MINUTES:
        s["time"] = 1.0 - (c.minutes - NEAR_MINUTES) / 15.0
    else:
        s["time"] = 0.0
    s["theme"] = 0.5 if not themes else (1.0 if c.theme in themes else 0.0)
    # [명준 확인] 최근 방문이 없어도 1.0 → 이유에 "지난번과 다른 테마"가 붙는다(AC-F5-1 문구 그대로)
    s["diversity"] = 1.0 if c.theme not in recent_themes else 0.0
    s["parking"] = 1.0 if c.has_parking_info else 0.0
    return s


def weighted(s: dict[str, float]) -> float:
    total = 0.0
    for k in ("zero", "time", "theme", "diversity", "parking"):
        total += WEIGHTS[k] * s[k]
    return math.floor(total * 10_000 + 0.5) / 10_000


def reason_line(c: Candidate, s: dict[str, float], b: Badge) -> str:
    """점수 상위 근거 최대 3개. 사실이 아닌 문구는 점수가 있어도 쓰지 않는다:
    - 빠듯·+1만 카드에 "추가 0원 안" 금지(PRD 5-3)
    - 75분 넘는 카드에 "75분 안" 금지
    - 주차는 점수에만(PRD 7-4)"""
    contrib = sorted(((WEIGHTS[k] * s[k], k) for k in ("zero", "time", "theme", "diversity")), reverse=True)
    phrases: list[str] = []
    for val, key in contrib:
        if val <= 0:
            continue
        if key == "zero" and not (b.zero_extra and not b.tight):
            continue
        if key == "time" and c.minutes > NEAR_MINUTES:
            continue
        phrase = THEME_KO.get(c.theme, c.theme) if key == "theme" else PHRASE[key]
        if phrase not in phrases:
            phrases.append(phrase)
        if len(phrases) == 3:
            break
    theme_ko = THEME_KO.get(c.theme, c.theme)
    if len(phrases) < 3 and theme_ko not in phrases:
        phrases.append(theme_ko)
    return " · ".join(phrases)


def card_json(c: Candidate, cost: Cost, badge: Badge, reason: str) -> dict:
    return {
        "id": c.id, "name": c.name, "theme": c.theme, "lat": c.lat, "lng": c.lng,
        "route_km": c.route_km, "minutes": c.minutes, "time_label": time_label(c.minutes),
        "cost": {"fuel": cost.fuel, "toll": cost.toll, "parking": cost.parking,
                 "total": cost.total, "per_person": cost.per_person},
        "display": {"total_text": cost.total_text, "per_person_text": cost.per_person_text,
                    "zero_extra": badge.zero_extra, "tight": badge.tight,
                    "ring_krw": badge.ring_krw, "badge_text": badge.text,
                    "margin_km": badge.margin_km},
        "reason": reason,
    }


# ---------------------------------------------------------------- E4 정산
@dataclass
class Snapshot:
    odometer_km: float
    seats: int
    price_per_unit: float
    efficiency: float
    ts: str
    price_source: str = "user"


@dataclass
class Settlement:
    km: float
    krw: int                       # 천 원 반올림(미만이면 0)
    per_person: int | None
    text: str
    skipped: bool
    reason: str | None = None


def settle(prev: Snapshot | None, odometer_now: float, last_destination: str | None,
           span_days: int = 0) -> Settlement:
    """F10. 직전 스냅샷의 연비·단가·착석으로 계산(직전 정산 시점 기준)."""
    if prev is None:
        return Settlement(0, 0, None, "", True, "no_snapshot")
    delta = odometer_now - prev.odometer_km
    if delta < 0:
        return Settlement(0, 0, None, "", True, "odometer_decreased")
    if delta == 0:
        return Settlement(0, 0, None, "", True, "no_distance")
    krw, txt, under = approx(delta / prev.efficiency * prev.price_per_unit, 1_000)
    pp = None
    if prev.seats >= 2:
        pp, _, _ = approx(delta / prev.efficiency * prev.price_per_unit / prev.seats, 1_000)
    head = f"지난번 이후 {span_days}일 · " if span_days >= 1 else "지난번 이후 "
    amount = txt if under else f"약 {txt}"
    text = f"{head}{round_half_up(delta)}km · {amount}"
    if prev.price_source in AVG_PRICE_SOURCES:
        text += " (평균 단가)"
    if last_destination:
        text += f" · 마지막 목적지 {last_destination}"
    return Settlement(delta, krw, pp, text, False)


# ---------------------------------------------------------------- F9 충전 직후
def charge_amount(start_soc: float, end_soc: float, capacity_kwh: float, price: float) -> tuple[float, int, str]:
    kwh = max(0.0, end_soc - start_soc) / 100.0 * capacity_kwh
    krw, txt, under = approx(kwh * price, 1_000)
    text = f"방금 {txt} 충전" if under else f"방금 약 {txt}어치 충전"
    return kwh, krw, text


def full_tank_amount(start_level_pct: float, tank_l: float, price: float) -> tuple[float, int, str]:
    """F9 내연 금액 시트의 '가득'(PRD v1.3 F9: 3만 / 5만 / 가득 + 1만 원 단위 ±).
    주유 시작 때 연료 %에서 100%까지 채우는 양 × 단가. 기록·정산처럼 천 원 어림(PRD 5-3).
    반환: (리터, 원, 문구 "약 7만 원"(그랜저 HEV 50L·25%·1,856원, fixture D11) / 어림이 0이면 "1천 원 미만")"""
    liters = max(0.0, 100.0 - start_level_pct) / 100.0 * tank_l
    krw, txt, under = approx(liters * price, 1_000)
    text = txt if under else f"약 {txt}"
    return liters, krw, text


# ---------------------------------------------------------------- F8·F10·F17 로컬 기록(장부)
def _dist_km(a: dict, b: dict) -> float:
    r = 6371.0
    la1, la2 = math.radians(a["lat"]), math.radians(b["lat"])
    dla, dlo = la2 - la1, math.radians(b["lng"] - a["lng"])
    h = math.sin(dla / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin(dlo / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def same_place(a: dict | None, b: dict | None) -> bool:
    """핀 중복 판정: 이름이 같거나 300m 안."""
    if not a or not b:
        return False
    if a.get("name") and a.get("name") == b.get("name"):
        return True
    if None in (a.get("lat"), a.get("lng"), b.get("lat"), b.get("lng")):
        return False
    return _dist_km(a, b) < PIN_SAME_KM


def _days_between(ts_from: str, ts_to: str) -> int:
    from datetime import datetime
    d = datetime.fromisoformat(ts_to) - datetime.fromisoformat(ts_from)
    return max(0, int(d.total_seconds() // 86_400))


def _snapshot(ev: dict) -> dict:
    return {"odometer_km": ev["odometer_km"], "seats": ev["seats"], "price_per_unit": ev["price"],
            "efficiency": ev["efficiency"], "ts": ev["ts"], "price_source": ev.get("price_source", "user")}


def _place(p: dict | None) -> dict | None:
    return {"name": p["name"], "lat": p.get("lat"), "lng": p.get("lng")} if p else None


def new_ledger() -> dict:
    return {"snapshot": None, "trip": None, "rows": [], "last_pin": None}


def ledger_step(state: dict, event: dict) -> tuple[dict, dict]:
    """앱 로컬 장부의 기준 구현. state·event·출력 모두 JSON 그대로라 JS가 같은 fixture로 대조한다.

    event.type:
      open   — 앱 실행·복귀(기어 P). {ts, odometer_km, seats, price, efficiency, price_source, recent_destination?}
      depart — F8 출발 1탭. {ts, odometer_km, place{id,name,lat,lng}, route_km}
      arrive — F17 도착(기어 P + 운전석 문 / onDestinationArrived, 앱이 보일 때). {ts, odometer_km, seats, price, efficiency, price_source}
               price_source는 open과 같다(없으면 user). 도착이 남긴 스냅샷으로 다음 정산할 때 '(평균 단가)' 판단에 쓴다(fixture L6).

    규칙:
    - open: 직전 스냅샷 이후 주행거리로 정산(E4). 진행 중 trip이 있으면 그 trip을 이 정산으로 닫고
      목적지는 trip 장소(명세 F8 "F10 정산 또는 도착 때 닫힘"). 실차는 도착 때 앱이 가려져 있어
      arrive 이벤트를 못 받는 경우가 대부분이라, 여기서 닫지 않으면 trip이 영원히 열려 있다.
    - arrive: trip 출발 대비 Δodo로 기록. Δodo가 0이면(에뮬) 편도 추정 + demo. 스냅샷 갱신 → 다음 open과 중복 없음.
    - 핀: 직전 핀(정산·도착 모두)과 같은 장소면 핀을 더하지 않고 금액만 쌓는다(AC-F10-6).
    출력: {"card": 정산 카드 문구 | None, "row": 새 기록 | None, "skipped": 사유 | None}
    """
    st = {"snapshot": state["snapshot"], "trip": state["trip"], "rows": list(state["rows"]),
          "last_pin": state["last_pin"]}
    t = event["type"]

    if t == "depart":
        st["trip"] = {"place": event["place"], "route_km": event["route_km"],
                      "start_odometer_km": event["odometer_km"], "departed_ts": event["ts"]}
        return st, {"card": None, "row": None, "skipped": None}

    if t == "open":
        prev_d = st["snapshot"]
        trip = st["trip"]
        if prev_d is None:
            st["snapshot"] = _snapshot(event)
            return st, {"card": None, "row": None, "skipped": "no_snapshot"}
        prev = Snapshot(**prev_d)
        dest = _place(trip["place"]) if trip else _place(event.get("recent_destination"))
        s = settle(prev, event["odometer_km"], dest["name"] if dest else None,
                   _days_between(prev.ts, event["ts"]))
        if s.skipped:
            if s.reason == "odometer_decreased":  # 콘솔 오입력·초기화 → 기준을 지금으로 다시 잡는다
                st["snapshot"] = _snapshot(event)
            return st, {"card": None, "row": None, "skipped": s.reason}
        pinned = bool(dest) and not same_place(dest, st["last_pin"])
        row = {"type": "settle", "from_ts": prev.ts, "to_ts": event["ts"], "km": s.km, "krw": s.krw,
               "per_person": s.per_person, "place": dest, "pinned": pinned, "demo": False}
        st["rows"].append(row)
        if pinned:
            st["last_pin"] = dest
        st["snapshot"] = _snapshot(event)
        st["trip"] = None
        return st, {"card": s.text, "row": row, "skipped": None}

    if t == "arrive":
        trip = st["trip"]
        if trip is None:
            return st, {"card": None, "row": None, "skipped": "no_trip"}
        delta = event["odometer_km"] - trip["start_odometer_km"]
        demo = delta <= 0
        km = trip["route_km"] if demo else delta
        krw, _, _ = approx(km / event["efficiency"] * event["price"], 1_000)
        dest = _place(trip["place"])
        pinned = not same_place(dest, st["last_pin"])
        row = {"type": "arrive", "from_ts": trip["departed_ts"], "to_ts": event["ts"], "km": km, "krw": krw,
               "per_person": None, "place": dest, "pinned": pinned, "demo": demo}
        st["rows"].append(row)
        if pinned:
            st["last_pin"] = dest
        st["snapshot"] = _snapshot(event)
        st["trip"] = None
        return st, {"card": None, "row": row, "skipped": None}

    raise ValueError(f"unknown event type: {t}")


def month_summary(rows: list[dict], month: str) -> dict:
    """F17 '이번 달 이동비 약 7만 원 · 다녀온 곳 3곳'. month = 'YYYY-MM', 기록의 to_ts 기준."""
    mine = [r for r in rows if r["to_ts"][:7] == month]
    if not mine:
        return {"krw": 0, "places": 0, "demo": False, "text": "첫 드라이브를 기다리는 중"}
    total = sum(r["krw"] for r in mine)
    places = sum(1 for r in mine if r["pinned"])
    krw, txt, under = approx(total, 1_000)
    amount = txt if under else f"약 {txt}"
    return {"krw": krw, "places": places, "demo": any(r["demo"] for r in mine),
            "text": f"이번 달 이동비 {amount} · 다녀온 곳 {places}곳"}
