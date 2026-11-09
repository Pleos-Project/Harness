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
KNOWN_PRECIP = ("none", "rain", "snow")   # GET /v1/weather 의 precip. 그 밖(unknown·실패)은 '정보 없음', 가산 0
ZENITH_DEG = 90.833   # 일출·일몰 천정각: 대기 굴절 34′ + 해 반지름 16′(해 윗부분이 지평선에 닿는 순간)


# ---------------------------------------------------------------- 상수
@dataclass(frozen=True)
class Item:
    key: str
    label: str                   # 선택지 버튼 "공동 경비"
    price_krw: float
    phrase_text: str = ""        # B 문장에 들어갈 말 "커피값"·"밥값"·"공동 경비"
    examples: tuple[str, ...] = ()   # 그 자리 글자가 차례로 바뀌는 예시(공동 경비만, 첫 값 = phrase_text)


@dataclass(frozen=True)
class EffortConstants:
    rates_pct: dict[str, float]          # rain·snow·congestion·delay·night (정수 %)
    delay_threshold_pct: float           # 예상보다 더 문턱(ETA의 %)
    fatigue_limit_min: float             # 장시간: 이 분을 넘은 만큼
    fatigue_rate_pct: float              # 넘은 분 × 이 %
    fatigue_reset_rest_min: float        # 이 분 이상 쉬면 연속 운전 구간을 나눈다
    arrival_eta_times: float             # 도착 상한(#68): 경과 > max(ETA × 이 값, ETA + 아래 분) 이면 정리하지 못함
    arrival_eta_plus_min: float
    arrival_max_min: float               # 경과 > 이 분(12시간)이면 정리하지 못함
    wage_per_hour: float                 # 1분 단가 = 시급 ÷ 60
    wage_label: str                      # 단가 출처 표기("최저임금")
    items: tuple[Item, ...]              # 방식 B 항목, 1인분 가격 오름차순
    copy: "Copy"                          # 화면 문구(effort.copy) — 임시, 디자인 확정 대기
    wage_year: int | None = None         # 시급 기준 연도(표기용, 계산에 안 씀). 보여 줄지는 앱·디자인(#82·#86)


@dataclass(frozen=True)
class Copy:
    """화면 문구는 모두 constants.json effort.copy에 둔다(명준 #60: 디자인이 확정해 넘겨줌). 엔진은 {…}만 채운다."""
    text: dict[str, str]                 # *_text 템플릿(headline_text·a_main_text·b_text 등)
    line_labels: dict[str, str]          # 줄 key → 줄 이름
    line_detail: dict[str, str]          # 줄 값 템플릿(weather_all_text 등)
    precip_words: dict[str, str]         # rain → 비, snow → 눈
    basis: dict[str, str]                # 줄 key → 근거 한 줄(AC-F23-4)
    rest_answers: tuple[tuple[int, str], ...]   # 도착 때 질문의 선택지(분, 문구)


def copy_from_json(cp: dict) -> Copy:
    return Copy(
        text={k: v for k, v in cp.items() if isinstance(v, str) and k.endswith("_text")},
        line_labels=dict(cp["line_labels"]), line_detail=dict(cp["line_detail"]),
        precip_words=dict(cp["precip_words"]), basis=dict(cp["basis"]),
        rest_answers=tuple((int(a["min"]), a["label_text"]) for a in cp["rest_answers"]),
    )


def fill(template: str, **values: str) -> str:
    """템플릿의 {이름} 자리를 채운다(Kotlin fill과 같음)."""
    for k, v in values.items():
        template = template.replace("{" + k + "}", v)
    return template


def effort_constants(const: dict) -> EffortConstants:
    """constants.json 전체(dict)에서 effort 블록을 읽는다."""
    e = const["effort"]
    f = e["fatigue"]
    items = sorted((Item(i["key"], i["label"], float(i["price_krw"]), i["phrase_text"], tuple(i.get("examples", ())))
                    for i in e["items"]), key=lambda i: i.price_krw)
    return EffortConstants(
        rates_pct={k: float(e["rates_pct"][k]) for k in ("rain", "snow", "congestion", "delay", "night")},
        delay_threshold_pct=float(e["delay_threshold_pct"]),
        fatigue_limit_min=float(f["limit_min"]),
        fatigue_rate_pct=float(f["rate_pct"]),
        fatigue_reset_rest_min=float(f["reset_rest_min"]),
        arrival_eta_times=float(e["arrival_cap"]["eta_times"]),
        arrival_eta_plus_min=float(e["arrival_cap"]["eta_plus_min"]),
        arrival_max_min=float(e["arrival_cap"]["max_min"]),
        wage_per_hour=float(e["wage_per_hour"]),
        wage_label=e["wage_label"],
        items=tuple(items),
        copy=copy_from_json(e["copy"]),
        wage_year=e.get("wage_year"),
    )


# ---------------------------------------------------------------- 입력·출력
@dataclass(frozen=True)
class HourPrecip:
    hour: str      # 조각 시작 정시(ISO 8601 + 오프셋). 조각은 [hour, hour + 1시간)
    precip: str    # none | rain | snow | unknown


@dataclass(frozen=True)
class WeatherTrip:
    """GET /v1/weather/trip 응답의 dep·arr(#63). 같은 조각을 같은 순서로 담는다. 앱은 응답을 그대로 넘기면 된다(status는 안 씀)."""
    dep: tuple[HourPrecip, ...]
    arr: tuple[HourPrecip, ...]


@dataclass(frozen=True)
class Midway:
    """중간 정산(명준 Q-08-02, 효성 계산 규칙 11/9). 가는 중 '출발~지금'을 도착과 같은 영수증으로 센다.
    앱은 arrived_at에 지금을 넣고 아래 두 값을 그대로 넘긴다. 지금까지 거리 = route_m − remain_m은 엔진이 만든다."""
    route_m: float     # 출발 때 경로 거리(requestTrip)
    remain_m: float    # 지금 내비 남은 거리(remainDistance)


@dataclass(frozen=True)
class Settlement:
    """앞서 한 중간 정산 한 건(효성 11/9). 앱 출발 기록 settlements의 한 줄에서 at·remain_krw만 넘긴다(method·choice는 안 씀).
    엔진이 이 목록으로 정산한 합(남은 금액을 낼 때 뺌)과 장시간 구간의 경계(정산 시각)를 둘 다 만든다."""
    at: str            # 정산한 시각(ISO 8601 + 오프셋) = 그 중간 정산 영수증의 arrived_at
    remain_krw: float  # 그 영수증의 proposal.remain_krw(원 단위, 어림 전)


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
    # 정차는 도착 때 답 하나로만 센다(#62, 명준·June 합의). 자동으로 잰 정차 목록은 받지 않는다.
    rest_answer_min: int = 0                # 도착 때 답한 쉰 시간 Q(0·15·30·60분)
    # 정체(열린 결정 28, 효성). 구간 목록이 있으면 통과 시간, 없으면 비율 × T. 둘 다 없으면 정체 정보 없음
    congestion_ratio: float | None = None   # 기본: 출발 전 requestTrip의 정체 비율(0~1)
    congestion_m: float | None = None       # 정체 km(비율 × 경로 거리, 표시만)
    congestion_s: float | None = None       # 보조: 구간 목록 Σ distance ÷ avrSpeed(나란히 배치)
    distance_km: float | None = None        # 표시만(누적 주행거리 Δ, 못 읽으면 route_m ÷ 1000)
    # 시간대별 강수(#63, GET /v1/weather/trip). 없거나 겹친 조각이 모두 unknown이면 weather_dep·weather_arr 절반씩
    weather_trip: WeatherTrip | None = None
    # 중간 정산(Q-08-02). 있으면 '출발~지금(arrived_at)'을 센다: 예상보다 더의 기준 ETA = ETA × 지금까지 거리 ÷ 경로 거리,
    # 정체 km = 비율 × 지금까지 거리, 거리 표시 = 지금까지 거리(distance_km는 안 씀). 나머지 줄은 도착과 같은 규칙
    midway: Midway | None = None
    # 앞서 한 중간 정산들(앱 settlements의 at·remain_krw, 정산한 순서). 있으면
    # ① 제안(1인 몫·운전자 몫·B 항목)은 남은 금액 = 금액 − Σ remain_krw로 한다(영수증 금액은 출발~도착 전체)
    # ② 장시간은 정산 시각에서 끊는다(쉰 시간 답으로 가운데에서 끊지 않음). 다른 줄은 그대로
    settlements: tuple[Settlement, ...] = ()


@dataclass
class Line:
    key: str
    label: str
    add_min: float       # 그 항의 가산(분)
    pct: int             # 가산 ÷ T × 100, 정수 % 반올림
    pct_text: str        # "+40%", 어림이 0이면 "+1% 미만"
    detail_text: str | None   # 줄 값(PRD 5-1): "비, 주행 내내"·"38km (경로의 36%)"·"+25분"·"50분"·"2시간 넘긴 10분"
    basis_text: str | None    # 줄을 탭하면 보이는 근거 한 줄(AC-F23-4, constants effort.basis)


@dataclass
class ScreenA:
    """방식 A 화면(명세 v0.5 F24, 노고 PRD v1.3 5-2). 방식 B로 넘어가는 문구·버튼은 두지 않는다."""
    title_text: str              # "오늘 운전을 챙긴다면"
    main_text: str               # 큰 숫자 "한 사람 약 9천 원"(동승자 1인 몫)
    basis_text: str              # "운전 노고 약 3만 6천 원을 운전자 포함 4명이 나눈 값"
    wage_text: str               # "최저임금 1분 178원 기준"


@dataclass
class ScreenB:
    """방식 B 화면(명세 v0.5 F25, 노고 PRD v1.3 5-3). 금액은 보여 주지 않는다. 선택지는 effort.items 전부."""
    item: str                    # 기본값으로 올릴 항목 key
    text: str                    # "오늘은 밥값에서 운전자 빼고 나누는 게 어때요" / "오늘은 공동 경비에서 …"
    item_examples: list[str]     # 공동 경비면 그 자리에 차례로 보일 예시(첫 값이 text 안의 말), 커피·밥은 빈 목록


@dataclass
class Proposal:
    amount_krw: float            # 노고 금액(어림 전). 중간 정산 뒤 도착이어도 출발~도착 전체
    amount_approx: int
    amount_text: str             # "약 3만 6천 원"
    per_person_krw: float        # 동승자 1인 몫 = 남은 금액 ÷ n (어림 전을 나눔. 정산 입력이 없으면 남은 금액 = 금액)
    per_person_approx: int
    per_person_text: str         # "약 9천 원"
    driver_share_krw: float      # 운전자 몫 G = 남은 금액 × (n − 1) ÷ n
    a: ScreenA
    b: ScreenB | None            # None = 방식 B 제안 없음(영수증만)
    # 중간 정산(Q-08-02). 입력에 midway·settlements가 둘 다 없으면 None이고 JSON에도 키를 쓰지 않는다
    settled_krw: float | None = None   # 앞서 정산한 원 단위 합(settlements의 remain_krw 합, 중간 정산이 처음이면 0)
    remain_krw: float | None = None    # 남은 금액 = max(0, 금액 − settled_krw), 어림 전. 이번에 정산하면 이 값을 기록에 더함


@dataclass
class Receipt:
    status: str                      # ok = 영수증 | unsettled = 정리하지 못함(#68, 도착이 너무 늦게 잡힘)
    status_text: str | None          # unsettled일 때 안내 문구(effort.copy.unsettled_text), ok면 None
    elapsed_limit_min: float         # 적용한 경과 상한(분) = min(max(ETA × 2, ETA + 60), 720)
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
    footnote_text: str | None        # 정체 줄이 있으면 "정체 구간은 내비 안내 경로 기준"
    drive_text: str                  # "2시간 10분"
    multiplier_text: str             # "평소보다 1.56배 힘든 길" | "평소와 같은 길"
    headline_text: str               # "오늘 운전 2시간 10분 · 평소보다 1.56배 힘든 길"
    distance_text: str | None        # "104km"
    proposal: Proposal | None        # n = 1이면 None(영수증만)

    def to_json(self) -> dict:
        d = asdict(self)
        p = d["proposal"]
        if p is not None and p["settled_krw"] is None:   # 정산 입력이 없으면 키도 없음(Kotlin toJson과 같음)
            del p["settled_krw"], p["remain_krw"]
        return d


def input_from_json(d: dict) -> EffortInput:
    """fixture·저장 기록의 JSON → 입력. 일출·일몰은 "HH:MM"."""
    return EffortInput(
        departed_at=d["departed_at"], arrived_at=d["arrived_at"], eta_s=float(d["eta_s"]),
        weather_dep=d["weather_dep"], weather_arr=d["weather_arr"],
        sunrise_min=parse_hhmm(d["sunrise"]), sunset_min=parse_hhmm(d["sunset"]), n=int(d["n"]),
        rest_answer_min=int(d.get("rest_answer_min") or 0), congestion_ratio=d.get("congestion_ratio"),
        congestion_m=d.get("congestion_m"), congestion_s=d.get("congestion_s"), distance_km=d.get("distance_km"),
        weather_trip=weather_trip_from_json(d.get("weather_trip")),
        midway=Midway(float(d["midway"]["route_m"]), float(d["midway"]["remain_m"])) if d.get("midway") else None,
        settlements=tuple(Settlement(s["at"], float(s["remain_krw"])) for s in d.get("settlements") or ()),
    )


def weather_trip_from_json(wt: dict | None) -> WeatherTrip | None:
    if wt is None:
        return None
    return WeatherTrip(tuple(HourPrecip(h["hour"], h["precip"]) for h in wt["dep"]),
                       tuple(HourPrecip(h["hour"], h["precip"]) for h in wt["arr"]))


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


def multiplier_text(m100: int, c: EffortConstants) -> str:
    """배수 x.xx(명세 2절). 1.00이면 '평소와 같은 길' 문구."""
    if m100 <= 100:
        return c.copy.text["multiplier_same_text"]
    return fill(c.copy.text["multiplier_more_text"], x=f"{m100 // 100}.{m100 % 100:02d}")


def pct_text(pct: int) -> str:
    return f"+{pct}%" if pct > 0 else "+1% 미만"


def km_text(m: float) -> str:
    """거리 m → "104km"(명세 2절 km 정수). 0으로 어림되면 "1km 미만"."""
    km = round_half_up(m / 1000)
    return f"{km}km" if km > 0 else "1km 미만"


def ratio_text(ratio: float) -> str:
    """정체 비율 → "36%". 0으로 어림되면 "1% 미만"."""
    p = round_half_up(ratio * 100)
    return f"{p}%" if p > 0 else "1% 미만"


def congestion_text(congestion_m: float | None, ratio: float | None, c: EffortConstants) -> str | None:
    """정체 구간 줄 값 "38km (경로의 36%)"(노고 PRD v1.3 5-1, 열린 결정 28). km·비율을 모르면 None."""
    if congestion_m is None or ratio is None:
        return None
    return fill(c.copy.line_detail["congestion_text"], km=km_text(congestion_m), pct=ratio_text(ratio))


@dataclass
class DeparturePreview:
    """출발 화면 미리보기(명세 v0.5 F20·12절 시연 순서 2). requestTrip 값으로 만든다."""
    eta_text: str                    # "1시간 45분"
    route_text: str                  # "104km"
    congestion_text: str | None      # "38km (경로의 36%)". 비율을 모르거나 0이면 None(정체 표시 없음)


def departure_preview(eta_s: float, route_m: float, congestion_ratio: float | None,
                      congestion_m: float | None, c: EffortConstants) -> DeparturePreview:
    has = congestion_ratio is not None and congestion_ratio > 0
    return DeparturePreview(
        eta_text=drive_text(float(eta_s)),
        route_text=km_text(float(route_m)),
        congestion_text=congestion_text(congestion_m, congestion_ratio, c) if has else None,
    )


def weather_detail(key: str, whole: bool, c: EffortConstants) -> str:
    """날씨 줄 값: 주행 내내면 "비, 주행 내내", 일부면 "비"(임시 문구, 디자인 확정 대기)."""
    t = c.copy.line_detail["weather_all_text" if whole else "weather_part_text"]
    return fill(t, precip=c.copy.precip_words[key])


@dataclass
class HourlyPrecip:
    rain_wall_s: float        # 출발~도착과 비 조각이 겹친 벽시계 초
    snow_wall_s: float
    known: bool               # 겹친 조각 가운데 판정된(none·rain·snow) 조각이 있음
    unknown: bool             # 겹친 조각 가운데 unknown 조각이 있음


def hourly_precip(dep: float, arr: float, wt: WeatherTrip) -> HourlyPrecip:
    """1시간 조각 [H, H+1)마다 조각 가운데가 주행 경과의 앞 절반이면 출발 지점, 뒤 절반이면 도착 지점 관측을 쓴다(#63).
    조각 가운데가 경과 가운데와 같으면 출발 지점(앞 절반에 넣음). 출발~도착과 겹치지 않는 조각은 안 쓴다."""
    mid = dep + (arr - dep) / 2
    rain = 0.0
    snow = 0.0
    known = False
    unknown = False
    for i in range(min(len(wt.dep), len(wt.arr))):
        h0 = float(epoch_s(wt.dep[i].hour))
        ov = overlap(dep, arr, h0, h0 + 3600)
        if ov <= 0:
            continue
        p = wt.dep[i].precip if h0 + 1800 <= mid else wt.arr[i].precip
        if p not in KNOWN_PRECIP:
            unknown = True
            continue
        known = True
        if p == "rain":
            rain += ov
        elif p == "snow":
            snow += ov
    return HourlyPrecip(rain, snow, known, unknown)


def time_detail(template_key: str, minutes_s: float, c: EffortConstants) -> str:
    """"+25분"·"50분"·"2시간 넘긴 10분". {time}은 운전 시간과 같은 "○시간 ○분" 표기(명세 2절)."""
    return fill(c.copy.line_detail[template_key], limit=drive_text(c.fatigue_limit_min * 60),
                time=drive_text(minutes_s))


def basis_text(key: str, c: EffortConstants) -> str | None:
    """줄 근거 한 줄(명세 v0.5 AC-F23-4). {pct}는 그 줄의 가산율(rates_pct)로 채워 숫자가 상수와 어긋나지 않게 한다."""
    t = c.copy.basis.get(key)
    if t is None:
        return None
    rate = c.rates_pct.get(key)
    return fill(t, pct=str(round_half_up(rate))) if rate is not None else t


def approx_text(krw: float) -> tuple[int, str]:
    """천 원 어림. 0이면 '1천 원 미만'(명세 v0.5 2절)."""
    v, text, under = approx(krw, 1000)
    return v, (text if under else f"약 {text}")


# ---------------------------------------------------------------- F22 도착 상한 (#68)
def elapsed_limit_s(eta_s: float, c: EffortConstants) -> float:
    """경과 상한(초). 기본 배치에서는 도착 시각이 '앱을 다시 연 시각'이라 늦게 잡힐 수 있다(#68).
    경과가 max(ETA × 2, ETA + 60분)를 넘거나 12시간을 넘으면 정리하지 못함. ETA를 모르면(0 이하) 12시간만."""
    cap = c.arrival_max_min * 60
    if eta_s > 0:
        cap = min(max(eta_s * c.arrival_eta_times, eta_s + c.arrival_eta_plus_min * 60), cap)
    return cap


def unsettled(elapsed: float, limit: float, c: EffortConstants) -> Receipt:
    """정리하지 못함: 금액·줄은 계산하지 않는다. headline_text에도 안내 문구를 넣어,
    status를 보기 전의 화면이 이 값을 그대로 보여도 잘못된 영수증이 뜨지 않게 한다."""
    t = c.copy.text["unsettled_text"]
    return Receipt(
        status="unsettled", status_text=t, elapsed_limit_min=limit / 60,
        elapsed_min=elapsed / 60, drive_min=0.0, rest_min=0.0, fatigue_segments_min=[],
        congestion_min=0.0, delay_min=0.0, night_min=0.0, rain_min=0.0, snow_min=0.0, fatigue_excess_min=0.0,
        effort_min=0.0, multiplier=0.0, lines=[], notes=[], footnote_text=None,
        drive_text="", multiplier_text="", headline_text=t, distance_text=None, proposal=None,
    )


# ---------------------------------------------------------------- F23 장시간 구간
def settlement_groups(dep: float, arr: float, drive: float, settlements: tuple[Settlement, ...]) -> list[float]:
    """중간 정산 시각에서 연속 운전을 끊는다(효성 11/9). [출발, 정산1], [정산1, 정산2], …, [정산k, 도착(또는 지금)].
    구간 = 벽시계 길이 × (T ÷ E)(밤길·날씨처럼 비례). 쉰 곳은 정산한 휴게소라고 보고 Q로 따로 끊지 않는다.
    정산 시각은 [출발, 도착]으로 자르고 시각 순으로 본다. 길이 0인 구간(같은 시각에 다시 정산 등)은 넣지 않는다."""
    cuts = sorted(min(arr, max(dep, float(epoch_s(s.at)))) for s in settlements)
    groups = []
    prev = dep
    for b in cuts + [arr]:
        if b > prev:
            groups.append((b - prev) * drive / (arr - dep))
            prev = b
    return groups or [drive]


# ---------------------------------------------------------------- F23 노고 · F24 금액 · F25 방식 B
def effort(inp: EffortInput, c: EffortConstants) -> Receipt:
    dep = float(epoch_s(inp.departed_at))
    arr = max(dep, float(epoch_s(inp.arrived_at)))
    elapsed = arr - dep
    limit = elapsed_limit_s(float(inp.eta_s), c)
    if elapsed > limit:
        return unsettled(elapsed, limit, c)
    nights = night_intervals(inp.departed_at, arr, inp.sunrise_min, inp.sunset_min)

    # 정차는 도착 때 답한 쉰 시간 Q만 안다(#62). 언제 쉬었는지 모르니 Q ≥ 15분이면 "중간쯤 한 번 쉬었다"고 보고
    # 운전 시간을 반으로 나눈다(#47). 중간 정산이 있으면 그 시각에서만 끊는다(효성 11/9, Q는 T에서 빼기만).
    # 밤길은 [출발, 도착]과 밤이 겹친 분 × (T ÷ E).
    q = float(inp.rest_answer_min)
    drive = max(0.0, elapsed - q * 60)
    if inp.settlements:
        groups = settlement_groups(dep, arr, drive, inp.settlements)
    elif q >= c.fatigue_reset_rest_min:
        groups = [drive / 2, drive / 2]
    else:
        groups = [drive]
    night_all = 0.0
    for a, b in nights:
        night_all += overlap(dep, arr, a, b)
    night = night_all * drive / elapsed if elapsed > 0 else 0.0

    if inp.congestion_s is not None:       # 구간 목록이 있으면 통과 시간 합
        congestion = max(0.0, min(float(inp.congestion_s), drive))
    elif inp.congestion_ratio is not None:   # 기본: 정체 비율 × T
        congestion = max(0.0, min(float(inp.congestion_ratio) * drive, drive))
    else:
        congestion = 0.0
    eta = float(inp.eta_s)   # 예상보다 더의 기준 ETA
    congestion_m = inp.congestion_m
    distance_m = inp.distance_km * 1000 if inp.distance_km is not None else None
    if inp.midway is not None:
        # 중간 정산(Q-08-02): 지금까지 거리 = 경로 거리 − 내비 남은 거리(0~경로 거리로 자름).
        # 도착 상한(#68)은 위에서 출발 때 ETA 그대로 봤다(효성 11/9). 경로 거리를 모르면 예상보다 더는 0
        route = float(inp.midway.route_m)
        so_far = max(0.0, min(route, route - float(inp.midway.remain_m)))
        eta = eta * so_far / route if route > 0 else 0.0
        congestion_m = float(inp.congestion_ratio) * so_far if inp.congestion_ratio is not None else None
        distance_m = so_far
    delay = max(0.0, drive - eta) if eta > 0 else 0.0
    if delay * 100 < c.delay_threshold_pct * eta:
        delay = 0.0
    hp = hourly_precip(dep, arr, inp.weather_trip) if inp.weather_trip is not None else None
    if hp is not None and hp.known:
        # 시간대별(#63): 강수 분 = 벽시계 겹침 × T/경과(밤길과 같은 방식). unknown 조각은 가산 0
        rain = hp.rain_wall_s * drive / elapsed
        snow = hp.snow_wall_s * drive / elapsed
        rain_whole = hp.rain_wall_s >= elapsed
        snow_whole = hp.snow_wall_s >= elapsed
        weather_note = c.copy.text["weather_partial_text"] if hp.unknown else None
    else:
        # 시간대별 입력이 없거나 겹친 조각이 모두 unknown이면 출발·도착 1회 관측, 앞·뒤 절반씩
        half = drive / 2
        rain = (half if inp.weather_dep == "rain" else 0.0) + (half if inp.weather_arr == "rain" else 0.0)
        snow = (half if inp.weather_dep == "snow" else 0.0) + (half if inp.weather_arr == "snow" else 0.0)
        rain_whole = inp.weather_dep == "rain" and inp.weather_arr == "rain"
        snow_whole = inp.weather_dep == "snow" and inp.weather_arr == "snow"
        weather_note = (c.copy.text["weather_unknown_text"]
                        if inp.weather_dep not in KNOWN_PRECIP or inp.weather_arr not in KNOWN_PRECIP else None)
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
            if k == "rain" or k == "snow":
                detail = weather_detail(k, rain_whole if k == "rain" else snow_whole, c)
            elif k == "congestion":
                detail = congestion_text(congestion_m, inp.congestion_ratio, c)
            elif k == "delay":
                detail = time_detail("delay_text", delay, c)
            elif k == "night":
                detail = time_detail("night_text", night, c)
            else:
                detail = time_detail("fatigue_text", excess, c)   # 구간이 여러 개면 넘긴 시간의 합
            lines.append(Line(k, c.copy.line_labels[k], adds[k] / 6000, pct, pct_text(pct), detail, basis_text(k, c)))
    m100 = round_half_up(total / drive) if drive > 0 else 100

    notes = []
    if weather_note is not None:
        notes.append(weather_note)
    if inp.congestion_s is None and inp.congestion_ratio is None:
        notes.append(c.copy.text["congestion_unknown_text"])

    dt = drive_text(drive)
    mt = multiplier_text(m100, c)
    # 정산 입력: 앞서 정산한 합(정산한 순서대로 더함, 엔진 두 벌 같은 순서), 처음 하는 중간 정산이면 0,
    # 둘 다 아니면 None(지금과 같은 출력)
    settled = None
    if inp.settlements:
        settled = 0.0
        for s in inp.settlements:
            settled += s.remain_krw
    elif inp.midway is not None:
        settled = 0.0
    return Receipt(
        status="ok", status_text=None, elapsed_limit_min=limit / 60,
        elapsed_min=elapsed / 60, drive_min=drive / 60, rest_min=(elapsed - drive) / 60,
        fatigue_segments_min=[g / 60 for g in groups],
        congestion_min=congestion / 60, delay_min=delay / 60, night_min=night / 60,
        rain_min=rain / 60, snow_min=snow / 60, fatigue_excess_min=excess / 60,
        effort_min=total / 6000, multiplier=m100 / 100, lines=lines, notes=notes,
        footnote_text=c.copy.text["footnote_congestion_text"] if any(l.key == "congestion" for l in lines) else None,
        drive_text=dt, multiplier_text=mt, headline_text=fill(c.copy.text["headline_text"], drive=dt, multiplier=mt),
        distance_text=km_text(distance_m) if distance_m is not None else None,   # 0km 대신 "1km 미만"
        proposal=proposal(total, inp.n, c, settled) if inp.n >= 2 else None,
    )


def proposal(effort_pct_s: float, n: int, c: EffortConstants, settled_krw: float | None = None) -> Proposal:
    """F24 금액(방식 A)과 F25 한 번 빼 주기(방식 B). 같은 노고 금액을 보여 주는 방식만 다르고, 두 화면은 따로다.
    중간 정산(Q-08-02, 효성 11/9): 앞서 정산한 원 단위 합을 뺀 남은 금액(어림 전)으로 1인 몫·운전자 몫·B 항목을 정하고,
    천 원 어림은 마지막에 한 번만 한다. 어림한 값끼리 빼지 않는다."""
    amount = effort_pct_s * c.wage_per_hour / 360_000
    remain = max(0.0, amount - settled_krw) if settled_krw is not None else amount
    per = remain / n
    share = remain * (n - 1) / n
    a_v, a_t = approx_text(amount)
    p_v, p_t = approx_text(per)
    item = b_item(share, c.items)   # 남은 몫이 0이거나 커피 1인분 절반보다 작으면 None(제안 없음)
    t = c.copy.text
    # 앞서 정산한 몫이 있으면 근거도 '뺀 나머지'(임시 문구, 명준 확정 대기)
    basis = t["a_basis_remain_text"] if settled_krw is not None and settled_krw > 0 else t["a_basis_text"]
    return Proposal(
        amount_krw=amount, amount_approx=a_v, amount_text=a_t,
        per_person_krw=per, per_person_approx=p_v, per_person_text=p_t,
        driver_share_krw=share,
        a=ScreenA(
            title_text=t["a_title_text"],
            main_text=fill(t["a_main_text"], per_person=p_t),
            basis_text=fill(basis, amount=a_t, n=str(n)),
            wage_text=fill(t["a_wage_text"], wage_label=c.wage_label,
                           wage_per_min=str(round_half_up(c.wage_per_hour / 60))),
        ),
        b=ScreenB(item.key, fill(t["b_text"], phrase=item.phrase_text), list(item.examples))
        if item else None,
        settled_krw=settled_krw,
        remain_krw=remain if settled_krw is not None else None,
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
