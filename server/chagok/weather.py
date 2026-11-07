"""날씨 조회 — 기상청 초단기실황(getUltraSrtNcst)의 강수 형태(PTY) 하나만 본다.

명세 v0.5 F20·F22(출발·도착 지점 날씨), 7절 강수 판정, 12절 서버 계약.
GET /v1/weather?lat=&lon= (main.py)가 부른다. 앱은 출발·도착 때 한 번씩 부른다.

- 판정: PTY 1·4·5 → rain, 2·3·6·7 → snow, 0 → none. 그 밖의 값은 unknown.
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
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from .engine import round_half_up

URL = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst"
TIMEOUT_S = 3.0          # 명세 v0.5 2절 '날씨 실패': 3초를 넘기면 unknown
KST = timezone(timedelta(hours=9))   # 서버 시계는 UTC(deploy/crontab). base_date·base_time은 KST로 정한다
BASE_READY_MIN = 40      # 정시 실황은 매시 40분 이후에 부른다. 그 전이면 한 시간 전 발표분

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


def parse_ncst(text: str) -> tuple[str, str]:
    """getUltraSrtNcst JSON → (precip, observed_at). 쓸 수 없으면 WeatherError.

    응답 모양: {"response": {"header": {"resultCode": "00", "resultMsg": "NORMAL_SERVICE"},
                              "body": {"items": {"item": [{"baseDate": "20261107", "baseTime": "1400",
                                                            "category": "PTY", "nx": 62, "ny": 123, "obsrValue": "0"}, ...]}}}}
    키 오류 같은 게이트웨이 오류는 dataType=JSON이어도 XML로 온다 → not_json.
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
    for it in items if isinstance(items, list) else []:
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
