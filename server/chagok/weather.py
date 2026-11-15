"""날씨 조회 — 기상청 초단기실황(getUltraSrtNcst)의 강수 형태(PTY)·1시간 강수량(RN1).

명세 v0.5 F20·F22(출발·도착 지점 날씨), 7절 강수 판정, 12절 서버 계약. main.py가 부르는 곳은 둘이다.
- GET /v1/weather?lat=&lon= → lookup: 한 지점의 지금 강수 형태. PTY 하나만 본다.
- GET /v1/weather/trip → trip: 도착 때 한 번, 출발~도착 사이 1시간 조각마다 두 지점의 정시 실황(#63).

- 판정: PTY 1·4·5 → rain, 2·3·6·7 → snow, 0 → none. 그 밖의 값은 unknown.
  시간대별(trip)은 RN1도 본다: PTY가 0이어도 RN1 > 0이면 rain(아래 judge_hour).
- 실패(키 없음·격자 밖·연결 실패·기상청 오류·3초 초과)는 모두 unknown으로 200을 준다.
  앱은 unknown을 '정보 없음'(가산 0)으로 두고 출발·도착을 이어 간다(AC-C-6).
- 좌표는 격자(nx, ny)로 바꿔 이번 요청 한 번에만 쓰고 버린다. 좌표·격자로 결과를 캐시하지 않고,
  로그에도 남기지 않는다(AC-N-4). 기상청 요청 URL에는 키와 격자가 들어가므로 URL을 어디에도 찍지 않는다.
- 키는 공공데이터포털 계정 인증키(TOURAPI_SERVICE_KEY)를 같이 쓴다. .env 에만 둔다.
"""

from __future__ import annotations

import json
import math
import socket
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor, wait
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from .engine import round_half_up

URL = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst"
TIMEOUT_S = 3.0          # 명세 v0.5 2절 '날씨 실패': 3초를 넘기면 unknown
KST = timezone(timedelta(hours=9))   # 서버 시계는 UTC(deploy/crontab). base_date·base_time은 KST로 정한다
# 정시 실황은 매시 10분 이후에 부른다. 그 전이면 한 시간 전 발표분. 활용가이드: 초단기실황 매시 10분 이후 제공
# (40분은 초단기예보 기준, June #54). 11/7 실측: 21:00 발표분이 21:04까지 NO_DATA, 21:06부터 수신
BASE_READY_MIN = 10

# PTY: 0 없음, 1 비, 2 비/눈, 3 눈, 4 소나기, 5 빗방울, 6 빗방울눈날림, 7 눈날림(명세 v0.5 7절).
# 4(소나기)는 단기예보 코드라 실황에는 보통 안 오지만, 오면 비로 본다(11/7 효성 결정)
PTY_PRECIP = {0: "none", 1: "rain", 4: "rain", 5: "rain", 2: "snow", 3: "snow", 6: "snow", 7: "snow"}

# ------------------------------------------------------------ 위경도 → 기상청 격자(LCC, 5km)
# 기상청 「단기예보 조회서비스 활용가이드」 격자 변환 식. 격자는 149 × 253(nx 1~149, ny 1~253).
_RE = 6371.00877         # 지구 반경(km)
_GRID = 5.0              # 격자 간격(km)
_SLAT1, _SLAT2 = 30.0, 60.0   # 투영 위도
_OLON, _OLAT = 126.0, 38.0    # 기준점 경도·위도
_XO, _YO = 43, 136            # 기준점 격자 좌표
NX_MAX, NY_MAX = 149, 253


def to_grid(lat: float, lon: float) -> tuple[int, int]:
    """위경도(WGS84) → 기상청 격자 (nx, ny)."""
    d = math.pi / 180.0
    re = _RE / _GRID
    slat1, slat2, olon, olat = _SLAT1 * d, _SLAT2 * d, _OLON * d, _OLAT * d
    sn = math.log(math.cos(slat1) / math.cos(slat2)) / math.log(
        math.tan(math.pi * 0.25 + slat2 * 0.5) / math.tan(math.pi * 0.25 + slat1 * 0.5))
    sf = math.tan(math.pi * 0.25 + slat1 * 0.5) ** sn * math.cos(slat1) / sn
    ro = re * sf / math.tan(math.pi * 0.25 + olat * 0.5) ** sn
    ra = re * sf / math.tan(math.pi * 0.25 + lat * d * 0.5) ** sn
    theta = lon * d - olon
    if theta > math.pi:
        theta -= 2.0 * math.pi
    if theta < -math.pi:
        theta += 2.0 * math.pi
    theta *= sn
    return round_half_up(ra * math.sin(theta) + _XO), round_half_up(ro - ra * math.cos(theta) + _YO)


def in_grid(nx: int, ny: int) -> bool:
    return 1 <= nx <= NX_MAX and 1 <= ny <= NY_MAX


# ------------------------------------------------------------ 발표 시각
def base_time(now: datetime) -> tuple[str, str]:
    """지금 부를 수 있는 실황 발표분 (base_date YYYYMMDD, base_time HH00). now는 시간대가 있어야 한다."""
    t = now.astimezone(KST)
    if t.minute < BASE_READY_MIN:
        t -= timedelta(hours=1)
    return t.strftime("%Y%m%d"), t.strftime("%H00")


# ------------------------------------------------------------ 응답 해석
class WeatherError(RuntimeError):
    """kind = 로그에 남길 오류 종류(짧은 영문). 키·URL·좌표·응답 본문은 싣지 않는다."""

    def __init__(self, kind: str):
        super().__init__(kind)
        self.kind = kind


@dataclass(frozen=True)
class Result:
    precip: str                  # none | rain | snow | unknown
    observed_at: str | None      # 실황 기준 시각 ISO 8601(+09:00). unknown이면 None
    err: str | None = None       # 로그용 오류 종류. 성공이면 None

    def public(self) -> dict:
        """GET /v1/weather 응답 본문. err는 싣지 않는다."""
        return {"precip": self.precip, "observed_at": self.observed_at}


def unknown(err: str) -> Result:
    return Result("unknown", None, err)


def _code(v) -> str:
    """기상청 resultCode를 로그에 실을 모양으로(짧은 영숫자만)."""
    s = str(v or "").strip()
    return s if s.isalnum() and len(s) <= 4 else "?"


def _observed_at(base_date: str, base_time_: str) -> str:
    if not (len(base_date) == 8 and base_date.isdigit() and len(base_time_) == 4 and base_time_.isdigit()):
        raise WeatherError("bad_base_time")
    return f"{base_date[:4]}-{base_date[4:6]}-{base_date[6:]}T{base_time_[:2]}:{base_time_[2:]}:00+09:00"


def _items(text: str) -> list:
    """getUltraSrtNcst JSON → 항목 목록. resultCode가 00이 아니거나 모양이 다르면 WeatherError.

    응답 모양: {"response": {"header": {"resultCode": "00", "resultMsg": "NORMAL_SERVICE"},
                              "body": {"items": {"item": [{"baseDate": "20261107", "baseTime": "1400",
                                                            "category": "PTY", "nx": 62, "ny": 123, "obsrValue": "0"}, ...]}}}}
    키 오류 같은 게이트웨이 오류는 dataType=JSON이어도 XML로 온다 → not_json.
    24시간보다 오래된 발표분은 resultCode 10("최근 1일 간의 자료만 제공합니다", 11/7 실측) → kma_10.
    """
    try:
        data = json.loads(text)
    except (json.JSONDecodeError, TypeError):
        raise WeatherError("not_json") from None
    resp = data.get("response") if isinstance(data, dict) else None
    head = resp.get("header") if isinstance(resp, dict) else None
    if not isinstance(head, dict):
        raise WeatherError("no_header")
    if str(head.get("resultCode")) != "00":
        raise WeatherError(f"kma_{_code(head.get('resultCode'))}")
    body = resp.get("body")
    items = body.get("items") if isinstance(body, dict) else None
    items = items.get("item") if isinstance(items, dict) else None
    if isinstance(items, dict):
        items = [items]
    return items if isinstance(items, list) else []


def parse_ncst(text: str) -> tuple[str, str]:
    """getUltraSrtNcst JSON → (precip, observed_at). PTY만 본다. 쓸 수 없으면 WeatherError."""
    for it in _items(text):
        if not isinstance(it, dict) or it.get("category") != "PTY":
            continue
        try:
            pty = int(float(str(it.get("obsrValue")).strip()))
        except (TypeError, ValueError):
            raise WeatherError("pty_not_number") from None
        if pty not in PTY_PRECIP:
            raise WeatherError("pty_unexpected")
        return PTY_PRECIP[pty], _observed_at(str(it.get("baseDate") or ""), str(it.get("baseTime") or ""))
    raise WeatherError("no_pty")


# ------------------------------------------------------------ 기상청 호출
def fetch_ncst(key: str, nx: int, ny: int, base_date: str, base_time_: str, timeout: float) -> str:
    """초단기실황 1회 호출 → 응답 본문. 실패하면 WeatherError(kind만, 키·URL 없음)."""
    q = {"serviceKey": urllib.parse.unquote(key) if "%" in key else key, "pageNo": 1, "numOfRows": 10,
         "dataType": "JSON", "base_date": base_date, "base_time": base_time_, "nx": nx, "ny": ny}
    url = f"{URL}?{urllib.parse.urlencode(q)}"   # 키·격자가 URL에 들어간다 → URL은 어디에도 찍지 않는다
    try:
        raw = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Chagok"}),
                                     timeout=timeout).read()
    except urllib.error.HTTPError as e:
        raise WeatherError(f"http_{e.code}") from None
    except urllib.error.URLError as e:
        timed_out = isinstance(e.reason, (TimeoutError, socket.timeout))
        raise WeatherError("timeout" if timed_out else "network") from None
    except (TimeoutError, socket.timeout):
        raise WeatherError("timeout") from None
    except OSError:
        raise WeatherError("network") from None
    return raw.decode("utf-8", "replace")


# 3초 마감용. urlopen의 timeout은 연결·읽기 한 번마다라서 전체 시간을 묶지 못한다 → 따로 기다리다 끊는다.
# 끊긴 호출은 urlopen 자체 timeout으로 곧 끝난다. 결과·예외는 버려진다(로그에 남지 않는다).
_POOL = ThreadPoolExecutor(max_workers=8, thread_name_prefix="kma")


def lookup(lat: float, lon: float, key: str | None, *, now: datetime | None = None,
           timeout: float | None = None, fetch: Callable[[], str] | None = None) -> Result:
    """한 지점의 지금 강수 형태. 어떤 실패도 예외로 올리지 않고 unknown을 돌려준다(AC-C-6).

    fetch는 테스트용(기상청 응답 본문을 돌려주는 함수). 없으면 fetch_ncst.
    """
    if not key:
        return unknown("no_key")
    try:
        nx, ny = to_grid(lat, lon)
    except (ArithmeticError, ValueError):      # 남극점(위도 −90)처럼 투영이 안 되는 곳
        return unknown("out_of_grid")
    if not in_grid(nx, ny):
        return unknown("out_of_grid")
    limit = TIMEOUT_S if timeout is None else timeout
    bd, bt = base_time(now or datetime.now(KST))
    call = fetch or (lambda: fetch_ncst(key, nx, ny, bd, bt, limit))
    try:
        text = _POOL.submit(call).result(timeout=limit)
        precip, observed_at = parse_ncst(text)
    except WeatherError as e:
        return unknown(e.kind)
    except TimeoutError:          # concurrent.futures.TimeoutError = TimeoutError (3.11+)
        return unknown("timeout")
    except Exception as e:        # 무엇이 와도 출발·도착을 막지 않는다. 종류만 남긴다
        return unknown(type(e).__name__)
    return Result(precip, observed_at)


# ------------------------------------------------------------ 시간대별 실황 (GET /v1/weather/trip, #63)
# 앱은 주행 중 부르지 않는다(AC-C-2). 도착 때 한 번, 출발~도착 사이 1시간 조각마다 두 지점의 정시 실황을 받는다.
# 조각 [H, H+1)은 H+1 정시 실황으로 판정한다: RN1 = H~H+1 1시간 강수량(mm), PTY = H+1 시각 강수 형태.
# H+1 정시분이 아직 발표 전이면(매시 10분 이후 제공) 지금 부를 수 있는 가장 최근 발표분으로 대신한다.
TRIP_MAX = timedelta(hours=24)        # 기상청은 최근 24시간 실황만 준다(11/7 실측: 23시간 전 정시분까지 수신, 24시간 전부터 resultCode 10)
FUTURE_SLACK = timedelta(minutes=10)  # 앱 시계가 서버보다 조금 빨라도 받는다(to ≤ 지금 + 10분)
HOUR = timedelta(hours=1)
_MISSING = 900.0                      # 기상청 결측값: +900 이상, −900 이하


def now_kst() -> datetime:
    """지금(KST). 테스트가 바꿔 끼운다."""
    return datetime.now(KST)


def _hour_iso(t: datetime) -> str:
    return t.astimezone(KST).strftime("%Y-%m-%dT%H:00:00+09:00")


def trip_hours(start: datetime, end: datetime) -> list[datetime]:
    """출발~도착에 걸친 1시간 조각의 시작 시각(KST 정시). [H, H+1)이 [start, end)와 겹치는 H만."""
    if start.tzinfo is None or end.tzinfo is None:
        raise ValueError("시간대가 있어야 한다")
    h = start.astimezone(KST).replace(minute=0, second=0, microsecond=0)
    out = []
    while h < end:
        out.append(h)
        h += HOUR
    return out


def _latest(t: datetime) -> datetime:
    """지금 부를 수 있는 가장 최근 정시 발표분(base_time과 같은 규칙)."""
    bd, bt = base_time(t)
    return datetime.strptime(bd + bt, "%Y%m%d%H%M").replace(tzinfo=KST)


def _pty(raw) -> tuple[int | None, str | None]:
    """PTY obsrValue → (값, 못 쓰면 오류 종류)."""
    if raw is None:
        return None, "no_pty"
    try:
        v = int(float(str(raw).strip()))
    except (TypeError, ValueError):
        return None, "pty_not_number"
    return (v, None) if v in PTY_PRECIP else (None, "pty_unexpected")


def _rn1(raw) -> float | None:
    """RN1 obsrValue(실황은 "0", "1.5" 같은 숫자 문자열, 11/7 실측) → mm. 없거나 숫자가 아니거나 결측이면 None."""
    try:
        v = float(str(raw).strip())
    except (TypeError, ValueError):
        return None
    return v if math.isfinite(v) and 0 <= v < _MISSING else None


def judge_hour(pty_raw, rn1_raw) -> str:
    """한 시간 조각의 강수(#63 판정 기준). 판정할 수 없으면 WeatherError.

    - PTY가 1~7이면 그 형태(1·4·5 비, 2·3·6·7 눈).
    - PTY가 0이거나 못 읽어도 RN1 > 0이면 비(그 1시간에 내렸다).
    - PTY 0이고 RN1이 0·없음 → none. PTY를 못 읽고 RN1도 0·없음 → 판정 못 함.
    """
    pty, err = _pty(pty_raw)
    if pty:
        return PTY_PRECIP[pty]
    rn1 = _rn1(rn1_raw)
    if rn1 is not None and rn1 > 0:
        return "rain"
    if pty == 0:
        return "none"
    raise WeatherError(err or "no_pty")


def parse_ncst_hour(text: str) -> str:
    """getUltraSrtNcst JSON → 그 정시 실황의 강수(PTY·RN1). 쓸 수 없으면 WeatherError."""
    vals: dict[str, object] = {}
    for it in _items(text):
        if isinstance(it, dict) and it.get("category") in ("PTY", "RN1"):
            vals.setdefault(it["category"], it.get("obsrValue"))
    return judge_hour(vals.get("PTY"), vals.get("RN1"))


@dataclass(frozen=True)
class TripResult:
    status: str                   # ok(모든 조각 판정) | partial(일부 unknown) | unavailable(전부 unknown)
    hours: tuple[str, ...]        # 조각 시작 시각 ISO 8601(+09:00, 정시)
    dep: tuple[str, ...]          # 조각별 출발 지점 강수 none | rain | snow | unknown
    arr: tuple[str, ...]          # 조각별 도착 지점 강수
    errs: tuple[str, ...] = ()    # 로그용 오류 종류(중복 없이, 정렬). 응답에는 싣지 않는다
    calls: int = 0                # 기상청 호출 수(로그용)

    def public(self) -> dict:
        """GET /v1/weather/trip 응답 본문."""
        return {"status": self.status,
                "dep": [{"hour": h, "precip": p} for h, p in zip(self.hours, self.dep)],
                "arr": [{"hour": h, "precip": p} for h, p in zip(self.hours, self.arr)]}

    @property
    def unknown(self) -> int:
        return (self.dep + self.arr).count("unknown")


def _grid(lat: float, lon: float) -> tuple[int, int] | None:
    try:
        g = to_grid(lat, lon)
    except (ArithmeticError, ValueError):      # 남극점처럼 투영이 안 되는 곳
        return None
    return g if in_grid(*g) else None


# 시간대별 호출 전용(한 번에 최대 50회). /v1/weather의 _POOL과 나눠서 서로 막지 않게 한다
_TRIP_POOL = ThreadPoolExecutor(max_workers=8, thread_name_prefix="kma-trip")


def trip(dep: tuple[float, float], arr: tuple[float, float], start: datetime, end: datetime, key: str | None, *,
         now: datetime | None = None, timeout: float | None = None,
         fetch: Callable[[int, int, str, str], str] | None = None) -> TripResult:
    """출발~도착 1시간 조각마다 출발·도착 지점의 강수. 어떤 실패도 예외로 올리지 않는다(AC-C-6).

    - 호출은 (격자, 발표분)마다 한 번. 두 지점이 같은 격자이거나 발표 전 조각들이 같은 최근분을 쓰면 한 번만 부른다.
    - 모두 병렬로 부르고 전체 3초에서 끊는다. 못 받은 조각만 unknown.
    - 24시간보다 오래된 발표분은 기상청이 주지 않으니 부르지 않고 unknown(too_old).
    fetch는 테스트용 (nx, ny, base_date, base_time) → 응답 본문. 없으면 fetch_ncst.
    """
    hours = trip_hours(start, end)
    t_now = (now or datetime.now(KST)).astimezone(KST)
    latest, oldest = _latest(t_now), t_now - TRIP_MAX
    # 지점마다 조각별 할 일: ("job", (nx, ny, 발표분)) 또는 ("err", 종류)
    plans: list[list[tuple[str, object]]] = []
    for lat, lon in (dep, arr):
        g = _grid(lat, lon)
        plan: list[tuple[str, object]] = []
        for h in hours:
            obs = min(h + HOUR, latest)
            if not key:
                plan.append(("err", "no_key"))
            elif g is None:
                plan.append(("err", "out_of_grid"))
            elif obs < oldest:
                plan.append(("err", "too_old"))
            else:
                plan.append(("job", (*g, obs)))
        plans.append(plan)

    limit = TIMEOUT_S if timeout is None else timeout
    call = fetch or (lambda nx, ny, bd, bt: fetch_ncst(key, nx, ny, bd, bt, limit))
    jobs = {j for plan in plans for kind, j in plan if kind == "job"}
    futures = {j: _TRIP_POOL.submit(call, j[0], j[1], j[2].strftime("%Y%m%d"), j[2].strftime("%H00"))
               for j in jobs}
    done = wait(futures.values(), timeout=limit).done if futures else set()
    got: dict[object, tuple[str, str | None]] = {}
    for j, f in futures.items():
        if f not in done:
            f.cancel()                # 아직 시작 못 한 호출은 버린다. 이미 도는 호출은 urlopen timeout으로 곧 끝난다
            got[j] = ("unknown", "timeout")
            continue
        try:
            got[j] = (parse_ncst_hour(f.result()), None)
        except WeatherError as e:
            got[j] = ("unknown", e.kind)
        except Exception as e:        # 무엇이 와도 도착을 막지 않는다. 종류만 남긴다
            got[j] = ("unknown", type(e).__name__)

    errs: set[str] = set()
    lists: list[tuple[str, ...]] = []
    for plan in plans:
        out = []
        for kind, v in plan:
            precip, err = got[v] if kind == "job" else ("unknown", v)
            if err:
                errs.add(err)
            out.append(precip)
        lists.append(tuple(out))
    dep_p, arr_p = lists
    every = dep_p + arr_p
    n_unknown = every.count("unknown")
    status = "ok" if every and n_unknown == 0 else "partial" if 0 < n_unknown < len(every) else "unavailable"
    return TripResult(status, tuple(_hour_iso(h) for h in hours), dep_p, arr_p, tuple(sorted(errs)), len(futures))
