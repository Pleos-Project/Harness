"""운전 노고 엔진 — 수식의 기준 구현(Python). 기능명세서 v0.5 F21·F23~F25, #47.

앱은 같은 수식을 Kotlin(app/android/app/src/main/java/com/mist/pleos_app/engine/Effort.kt)으로 갖는다.
둘 다 fixtures/effort/ 를 통과해야 하고(서버 pytest, 앱 FixturesTest), 이 파일을 바꾸면 `make fixtures` 로
fixture를 다시 만들고 Kotlin도 같은 PR에서 고친다. 상수는 fixtures/constants.json 의 `effort` 블록이다.

포팅 규칙(Kotlin과 숫자가 끝자리까지 같게)
- 반올림은 engine.round_half_up / round_to(approx) 만 쓴다. 내장 round()는 은행가 반올림이라 쓰지 않는다.
- 덧셈·곱셈·나눗셈 순서를 Kotlin과 똑같이 적는다(부동소수 결과가 순서에 따라 달라진다).
- 시각은 epoch 초, 시간 양(운전·정체·지연·밤·비·눈)은 초로 계산한다. 가산율은 정수 %(effort.rates_pct)라서
  가산은 '%·초'(= 초 × 가산율 %)로 더한다. 정수 초 × 정수 %에는 부동소수 오차가 없어서 명세 12절의 경계값
  (R4·R5 배수 x.xx5, R7 지연 = ETA의 10%)이 Decimal로 손 계산한 값과 같게 떨어진다. 0.40·0.10 같은 소수를
  곱하면 1.245가 1.2449…로 떨어져 배수가 1.24가 될 수 있다.
  · 분 = %·초 ÷ 6000, 배수 × 100 = 노고(%·초) ÷ T(초), 줄별 가산(%) = 그 항(%·초) ÷ T(초)
  · 금액 = 노고(%·초) × 시급 ÷ 360000  (= 노고(분) × 시급 ÷ 60)
"""

from __future__ import annotations

import math
from dataclasses import asdict, dataclass
from datetime import date, datetime

from chagok.engine import approx, round_half_up

DAY_S = 86_400
LINE_ORDER = ("rain", "snow", "congestion", "delay", "night", "fatigue")   # 영수증 줄 순서(명세 F23 출력)
LINE_LABEL = {"rain": "비", "snow": "눈", "congestion": "정체 구간", "delay": "예상보다 더",
              "night": "밤길", "fatigue": "장시간"}
KNOWN_PRECIP = ("none", "rain", "snow")   # GET /v1/weather 의 precip. 그 밖(unknown·실패)은 '정보 없음', 가산 0
ZENITH_DEG = 90.833   # 일출·일몰 천정각: 대기 굴절 34′ + 해 반지름 16′(해 윗부분이 지평선에 닿는 순간)


# ---------------------------------------------------------------- 상수
@dataclass(frozen=True)
class Item:
    key: str
    label: str
    price_krw: float


@dataclass(frozen=True)
class EffortConstants:
    rates_pct: dict[str, float]          # rain·snow·congestion·delay·night (정수 %)
    delay_threshold_pct: float           # 예상보다 더 문턱(ETA의 %)
    fatigue_limit_min: float             # 장시간: 이 분을 넘은 만큼
    fatigue_rate_pct: float              # 넘은 분 × 이 %
    fatigue_reset_rest_min: float        # 이 분 이상 쉬면 연속 운전 구간을 나눈다
    wage_per_hour: float                 # 1분 단가 = 시급 ÷ 60
    wage_label: str                      # 단가 출처 표기("최저임금")
    items: tuple[Item, ...]              # 방식 B 항목, 1인분 가격 오름차순


def effort_constants(const: dict) -> EffortConstants:
    """constants.json 전체(dict)에서 effort 블록을 읽는다."""
    e = const["effort"]
    f = e["fatigue"]
    items = sorted((Item(i["key"], i["label"], float(i["price_krw"])) for i in e["items"]), key=lambda i: i.price_krw)
    return EffortConstants(
        rates_pct={k: float(e["rates_pct"][k]) for k in ("rain", "snow", "congestion", "delay", "night")},
        delay_threshold_pct=float(e["delay_threshold_pct"]),
        fatigue_limit_min=float(f["limit_min"]),
        fatigue_rate_pct=float(f["rate_pct"]),
        fatigue_reset_rest_min=float(f["reset_rest_min"]),
        wage_per_hour=float(e["wage_per_hour"]),
        wage_label=e["wage_label"],
        items=tuple(items),
    )


# ---------------------------------------------------------------- 입력·출력
@dataclass(frozen=True)
class Stop:
    start: str   # ISO 8601 + 오프셋. 정차 시작 = 기어 P·운전석 문(안내 중, 남은 거리 > 0), 없으면 onStop
    end: str     # 정차 끝 = 재시동 뒤 기어 D, 없으면 onStart


@dataclass(frozen=True)
class EffortInput:
    departed_at: str                     # ISO 8601 + 오프셋
    arrived_at: str
    eta_s: float                         # onRouteStarted duration
    weather_dep: str                     # none | rain | snow | unknown
    weather_arr: str
    sunrise_min: int                     # 출발 지점·날짜의 일출·일몰(그 날 0시부터 분, sun_times 결과)
    sunset_min: int
    n: int                               # 나눠 낼 인원(운전자 포함)
    stops: tuple[Stop, ...] | None = None   # 1순위: 정차 목록. 빈 목록 = 신호는 받았고 정차 없음
    rest_answer_min: int | None = None      # 2순위: 도착 때 답한 쉰 시간 Q(0·15·30·60). stops가 있으면 안 씀
    congestion_s: float | None = None       # Σ 구간 distance ÷ avrSpeed. None = 정체 정보 없음
    distance_km: float | None = None        # 표시만(누적 주행거리 Δ, 못 읽으면 route_m ÷ 1000)


@dataclass
class Line:
    key: str
    label: str
    add_min: float       # 그 항의 가산(분)
    pct: int             # 가산 ÷ T × 100, 정수 % 반올림
    pct_text: str        # "+40%", 어림이 0이면 "+1% 미만"


@dataclass
class Proposal:
    wage_text: str               # "최저임금 1분 178원 기준"
    amount_krw: float            # 노고 금액(어림 전)
    amount_approx: int
    amount_text: str             # "약 3만 6천 원"
    per_person_krw: float        # 동승자 1인 몫 = 금액 ÷ n (어림 전 총액을 나눔)
    per_person_approx: int
    per_person_text: str
    driver_share_krw: float      # 운전자 몫 G = 금액 × (n − 1) ÷ n
    b_item: str | None           # 방식 B 기본값 항목 key. None = 제안 없음
    b_text: str | None


@dataclass
class Receipt:
    method: str                      # stops(1순위 정차 목록) | answer(2순위 쉰 시간 답)
    elapsed_min: float               # E
    drive_min: float                 # T
    rest_min: float                  # E − T
    fatigue_segments_min: list[float]
    congestion_min: float            # C
    delay_min: float                 # D
    night_min: float                 # N
    rain_min: float                  # R
    snow_min: float                  # S
    fatigue_excess_min: float        # Σ max(0, 구간 − 120)
    effort_min: float                # 노고(분)
    multiplier: float                # 부담 배수, 소수 둘째 자리 반올림
    lines: list[Line]                # 가산이 0인 줄은 생략
    notes: list[str]                 # "날씨 정보 없음", "정체 정보 없음"
    drive_text: str                  # "2시간 10분"
    multiplier_text: str             # "평소보다 1.56배 힘든 길" | "평소와 같은 길"
    headline_text: str               # "오늘 운전 2시간 10분 · 평소보다 1.56배 힘든 길"
    distance_text: str | None        # "104km"
    proposal: Proposal | None        # n = 1이면 None(영수증만)

    def to_json(self) -> dict:
        return asdict(self)


def input_from_json(d: dict) -> EffortInput:
    """fixture·저장 기록의 JSON → 입력. 일출·일몰은 "HH:MM"."""
    stops = d.get("stops")
    return EffortInput(
        departed_at=d["departed_at"], arrived_at=d["arrived_at"], eta_s=float(d["eta_s"]),
        weather_dep=d["weather_dep"], weather_arr=d["weather_arr"],
        sunrise_min=parse_hhmm(d["sunrise"]), sunset_min=parse_hhmm(d["sunset"]), n=int(d["n"]),
        stops=tuple(Stop(s["start"], s["end"]) for s in stops) if stops is not None else None,
        rest_answer_min=d.get("rest_answer_min"), congestion_s=d.get("congestion_s"),
        distance_km=d.get("distance_km"),
    )


# ---------------------------------------------------------------- 시각
def epoch_s(iso: str) -> int:
    return int(datetime.fromisoformat(iso).timestamp())


def local_midnight_s(iso: str) -> int:
    """그 시각의 오프셋 기준 그 날 0시(epoch 초)."""
    return int(datetime.fromisoformat(iso).replace(hour=0, minute=0, second=0, microsecond=0).timestamp())


def hhmm(minute_of_day: int) -> str:
    return f"{minute_of_day // 60:02d}:{minute_of_day % 60:02d}"


def parse_hhmm(s: str) -> int:
    h, m = s.split(":")
    return int(h) * 60 + int(m)


def overlap(a0: float, a1: float, b0: float, b1: float) -> float:
    return max(0.0, min(a1, b1) - max(a0, b0))


def drive_segments(dep: float, arr: float, stops: tuple[Stop, ...]) -> list[tuple[float, float]]:
    """[출발, 도착] − 정차들. 정차는 [출발, 도착] 안으로 자르고 겹치거나 맞닿으면 합친다."""
    clipped = []
    for st in stops:
        s = max(dep, float(epoch_s(st.start)))
        e = min(arr, float(epoch_s(st.end)))
        if e > s:
            clipped.append((s, e))
    clipped.sort()
    merged: list[tuple[float, float]] = []
    for s, e in clipped:
        if merged and s <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(merged[-1][1], e))
        else:
            merged.append((s, e))
    segs = []
    cur = dep
    for s, e in merged:
        if s > cur:
            segs.append((cur, s))
        cur = e
    if arr > cur:
        segs.append((cur, arr))
    return segs


def night_intervals(departed_at: str, arr: float, sunrise_min: int, sunset_min: int) -> list[tuple[float, float]]:
    """[일몰, 다음 일출] 구간들. 출발 전날 밤부터 도착하는 날 밤까지(출발 지점·날짜의 시각을 매일 같게 씀)."""
    mid = local_midnight_s(departed_at)
    last = (int(arr) - mid) // DAY_S
    return [(float(mid + k * DAY_S + sunset_min * 60), float(mid + (k + 1) * DAY_S + sunrise_min * 60))
            for k in range(-1, last + 1)]


# ---------------------------------------------------------------- 표기
def drive_text(drive_s: float) -> str:
    mins = round_half_up(drive_s / 60)
    if mins <= 0:
        return "1분 미만"
    h, m = divmod(mins, 60)
    if h == 0:
        return f"{m}분"
    if m == 0:
        return f"{h}시간"
    return f"{h}시간 {m}분"


def multiplier_text(m100: int) -> str:
    if m100 <= 100:
        return "평소와 같은 길"
    return f"평소보다 {m100 // 100}.{m100 % 100:02d}배 힘든 길"


def pct_text(pct: int) -> str:
    return f"+{pct}%" if pct > 0 else "+1% 미만"


def approx_text(krw: float) -> tuple[int, str]:
    """천 원 어림. 0이면 '1천 원 미만'(명세 v0.5 2절)."""
    v, text, under = approx(krw, 1000)
    return v, (text if under else f"약 {text}")


# ---------------------------------------------------------------- F23 노고 · F24 금액 · F25 방식 B
def effort(inp: EffortInput, c: EffortConstants) -> Receipt:
    dep = float(epoch_s(inp.departed_at))
    arr = max(dep, float(epoch_s(inp.arrived_at)))
    elapsed = arr - dep
    nights = night_intervals(inp.departed_at, arr, inp.sunrise_min, inp.sunset_min)
    reset_s = c.fatigue_reset_rest_min * 60

    if inp.stops is not None:
        # 1순위: 주행 구간을 안다. 15분 이상 정차에서 장시간 구간을 나누고, 짧은 정차는 시간만 뺀다.
        method = "stops"
        segs = drive_segments(dep, arr, inp.stops)
        drive = 0.0
        groups: list[float] = []
        for i, (s, e) in enumerate(segs):
            if i == 0 or s - segs[i - 1][1] >= reset_s:
                groups.append(0.0)
            groups[-1] += e - s
            drive += e - s
        night = 0.0
        for s, e in segs:
            for a, b in nights:
                night += overlap(s, e, a, b)
    else:
        # 2순위: 쉰 총시간만 안다. 15분 이상이면 "중간쯤 한 번 쉬었다"고 보고 반으로 나눈다(#47).
        method = "answer"
        q = float(inp.rest_answer_min or 0)
        drive = max(0.0, elapsed - q * 60)
        groups = [drive / 2, drive / 2] if q >= c.fatigue_reset_rest_min else [drive]
        night_all = 0.0
        for a, b in nights:
            night_all += overlap(dep, arr, a, b)
        night = night_all * drive / elapsed if elapsed > 0 else 0.0

    congestion = max(0.0, min(float(inp.congestion_s), drive)) if inp.congestion_s is not None else 0.0
    eta = float(inp.eta_s)
    delay = max(0.0, drive - eta) if eta > 0 else 0.0
    if delay * 100 < c.delay_threshold_pct * eta:
        delay = 0.0
    half = drive / 2
    rain = (half if inp.weather_dep == "rain" else 0.0) + (half if inp.weather_arr == "rain" else 0.0)
    snow = (half if inp.weather_dep == "snow" else 0.0) + (half if inp.weather_arr == "snow" else 0.0)
    limit_s = c.fatigue_limit_min * 60
    excess = 0.0
    for g in groups:
        excess += max(0.0, g - limit_s)

    r = c.rates_pct
    adds = {   # %·초
        "rain": r["rain"] * rain,
        "snow": r["snow"] * snow,
        "congestion": r["congestion"] * congestion,
        "delay": r["delay"] * delay,
        "night": r["night"] * night,
        "fatigue": c.fatigue_rate_pct * excess,
    }
    total = 100 * drive
    lines = []
    for k in LINE_ORDER:
        total += adds[k]
        if adds[k] > 0:
            pct = round_half_up(adds[k] / drive)
            lines.append(Line(k, LINE_LABEL[k], adds[k] / 6000, pct, pct_text(pct)))
    m100 = round_half_up(total / drive) if drive > 0 else 100

    notes = []
    if inp.weather_dep not in KNOWN_PRECIP or inp.weather_arr not in KNOWN_PRECIP:
        notes.append("날씨 정보 없음")
    if inp.congestion_s is None:
        notes.append("정체 정보 없음")

    dt = drive_text(drive)
    mt = multiplier_text(m100)
    return Receipt(
        method=method, elapsed_min=elapsed / 60, drive_min=drive / 60, rest_min=(elapsed - drive) / 60,
        fatigue_segments_min=[g / 60 for g in groups],
        congestion_min=congestion / 60, delay_min=delay / 60, night_min=night / 60,
        rain_min=rain / 60, snow_min=snow / 60, fatigue_excess_min=excess / 60,
        effort_min=total / 6000, multiplier=m100 / 100, lines=lines, notes=notes,
        drive_text=dt, multiplier_text=mt, headline_text=f"오늘 운전 {dt} · {mt}",
        distance_text=f"{round_half_up(inp.distance_km)}km" if inp.distance_km is not None else None,
        proposal=proposal(total, inp.n, c) if inp.n >= 2 else None,
    )


def proposal(effort_pct_s: float, n: int, c: EffortConstants) -> Proposal:
    """F24 금액(방식 A)과 F25 한 번 빼 주기(방식 B). 같은 노고 금액을 보여 주는 방식만 다르다."""
    amount = effort_pct_s * c.wage_per_hour / 360_000
    per = amount / n
    share = amount * (n - 1) / n
    a_v, a_t = approx_text(amount)
    p_v, p_t = approx_text(per)
    item = b_item(share, c.items)
    return Proposal(
        wage_text=f"{c.wage_label} 1분 {round_half_up(c.wage_per_hour / 60)}원 기준",
        amount_krw=amount, amount_approx=a_v, amount_text=a_t,
        per_person_krw=per, per_person_approx=p_v, per_person_text=p_t,
        driver_share_krw=share,
        b_item=item.key if item else None,
        b_text=f"오늘은 {item.label}에서 운전자 빼고 나누는 게 어때요" if item else None,
    )


def b_item(share: float, items: tuple[Item, ...]) -> Item | None:
    """1인분 가격이 운전자 몫 G에 가장 가까운 항목. 같은 거리면 비싼 쪽(명세 9절 경계 '이상').
    G가 가장 싼 항목 1인분의 절반보다 작으면 제안 없음."""
    if not items or share * 2 < items[0].price_krw:
        return None
    best = None
    best_d = 0.0
    for it in items:
        d = abs(it.price_krw - share)
        if best is None or d <= best_d:
            best, best_d = it, d
    return best


# ---------------------------------------------------------------- 일출·일몰 (F22·F23)
def _rad(x: float) -> float:
    return x * math.pi / 180.0


def _deg(x: float) -> float:
    return x * 180.0 / math.pi


def _sun_eqtime_decl(jd: float) -> tuple[float, float]:
    """균시차(분)·적위(도). NOAA Solar Calculator(Meeus 근사)."""
    t = (jd - 2451545.0) / 36525.0
    l0 = 280.46646 + t * (36000.76983 + t * 0.0003032)
    l0 = l0 - 360.0 * math.floor(l0 / 360.0)
    m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
    e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
    mr = _rad(m)
    c = (math.sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) + math.sin(2 * mr) * (0.019993 - 0.000101 * t)
         + math.sin(3 * mr) * 0.000289)
    omega = 125.04 - 1934.136 * t
    app_long = l0 + c - 0.00569 - 0.00478 * math.sin(_rad(omega))
    eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
    eps = eps0 + 0.00256 * math.cos(_rad(omega))
    decl = _deg(math.asin(math.sin(_rad(eps)) * math.sin(_rad(app_long))))
    y = math.tan(_rad(eps / 2))
    y = y * y
    l0r = _rad(l0)
    eqt = 4 * _deg(y * math.sin(2 * l0r) - 2 * e * math.sin(mr) + 4 * e * y * math.sin(mr) * math.cos(2 * l0r)
                   - 0.5 * y * y * math.sin(4 * l0r) - 1.25 * e * e * math.sin(2 * mr))
    return eqt, decl


def _sun_event_utc_min(jd0: float, lat: float, lng: float, rising: bool) -> float:
    """그 날 0시(UTC)부터 분. 남중에서 시작해 그 시각의 태양 위치로 3번 고쳐 계산한다."""
    t = 720.0 - 4.0 * lng
    for _ in range(3):
        eqt, decl = _sun_eqtime_decl(jd0 + t / 1440.0)
        cos_ha = (math.cos(_rad(ZENITH_DEG)) / (math.cos(_rad(lat)) * math.cos(_rad(decl)))
                  - math.tan(_rad(lat)) * math.tan(_rad(decl)))
        ha = _deg(math.acos(min(1.0, max(-1.0, cos_ha))))
        t = 720.0 - 4.0 * (lng + ha if rising else lng - ha) - eqt
    return t


def sun_times(lat: float, lng: float, day: str, tz_offset_min: int = 540) -> tuple[int, int]:
    """그 지점·날짜(현지)의 일출·일몰, 그 날 0시부터 분(분 단위 반올림). 엔진 두 벌이 같은 값을 쓴다(명세 7절)."""
    jd0 = (date.fromisoformat(day) - date(1970, 1, 1)).days + 2440587.5
    rise = _sun_event_utc_min(jd0, lat, lng, True) + tz_offset_min
    sset = _sun_event_utc_min(jd0, lat, lng, False) + tz_offset_min
    return round_half_up(rise), round_half_up(sset)
