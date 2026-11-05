"""실서비스 모드 실경로 보정 — 상위 후보만 카카오모빌리티 길찾기로 거리·시간·통행료를 덮어쓴다.

PRD v1.3 7-4: 후보 전체는 직선 × 굴곡 계수로 추정하고(sources.real_candidates), 순위 상위 후보만
경로 API로 정밀값을 받는다. 키는 .env(KAKAO_REST_API_KEY)에만 둔다.

프라이버시(PRD 7-1 "위치 이력 저장 금지"):
- 출발지 좌표는 디스크·로그·예외 메시지 어디에도 남기지 않는다. 이 모듈은 로그를 쓰지 않는다.
- 캐시는 메모리에만 둔다(프로세스 재시작하면 비워짐). 캐시 키는 원좌표가 아니라 0.01°(약 1km) 격자값이다.
- 예외 메시지에서 키와 출발지 좌표를 지운다.

반올림은 engine 헬퍼(round1·round_half_up)만 쓴다. 내장 round()는 은행가 반올림이라 쓰지 않는다.
"""

from __future__ import annotations

import dataclasses
import json
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import OrderedDict
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

from . import engine as E

URL = "https://apis-navi.kakaomobility.com/v1/directions"
GRID_PER_DEG = 100          # 0.01° 격자(위도 방향 약 1.1km, 서울 경도 방향 약 0.9km)


@dataclass(frozen=True)
class Route:
    route_km: float          # 편도 도로 거리(소수 첫째 자리)
    minutes: int             # 편도 시간(분, half-up)
    toll_krw: int            # 편도 통행료(원)


class RouteError(RuntimeError):
    pass


# fetch(olat, olng, dlat, dlng) -> Route
Fetch = Callable[[float, float, float, float], Route]


# ------------------------------------------------------------ 카카오 길찾기
def parse_directions(text: str) -> Route:
    """길찾기 응답(JSON 문자열) → Route. routes[0].result_code 가 0이 아니면 RouteError.

    변환: route_km = round1(distance m / 1000), minutes = round_half_up(duration s / 60), toll_krw = int(toll or 0)
    """
    try:
        data = json.loads(text)
    except (json.JSONDecodeError, TypeError):
        raise RouteError("길찾기 응답이 JSON 아님") from None
    routes = data.get("routes") if isinstance(data, dict) else None
    if not routes or not isinstance(routes, list) or not isinstance(routes[0], dict):
        raise RouteError("길찾기 응답에 routes 없음")
    r = routes[0]
    if r.get("result_code") != 0:
        # result_msg 는 "출발지와 도착지가 너무 가까움" 같은 고정 문구라 좌표가 없다
        raise RouteError(f"길찾기 실패 result_code={r.get('result_code')} {r.get('result_msg') or ''}".rstrip())
    s = r.get("summary") or {}
    try:
        distance = float(s["distance"])
        duration = float(s["duration"])
        toll = (s.get("fare") or {}).get("toll")
        return Route(route_km=E.round1(distance / 1000), minutes=E.round_half_up(duration / 60),
                     toll_krw=int(toll or 0))
    except (KeyError, TypeError, ValueError):
        raise RouteError("길찾기 응답 summary 형식 오류") from None


def _scrub(text: str, key: str, *coords: float) -> str:
    """메시지에서 키와 출발지 좌표를 지운다(카카오 오류 본문이 키를 되돌려 주는 경우가 있다)."""
    if key:
        text = text.replace(key, "<KEY>")
    for c in coords:
        text = text.replace(str(c), "<COORD>")
    return text


def fetch_kakao(key: str, olat: float, olng: float, dlat: float, dlng: float, timeout: float = 3.0) -> Route:
    """출발지 → 목적지 자동차 추천 경로 1건. 실패하면 RouteError(메시지에 키·출발지 좌표 없음)."""
    q = urllib.parse.urlencode({"origin": f"{olng},{olat}", "destination": f"{dlng},{dlat}",
                                "priority": "RECOMMEND", "summary": "true"})
    req = urllib.request.Request(f"{URL}?{q}", headers={"Authorization": f"KakaoAK {key}", "User-Agent": "Chagok"})
    try:
        raw = urllib.request.urlopen(req, timeout=timeout).read()
    except urllib.error.HTTPError as e:
        try:
            body = e.read().decode("utf-8", "replace")[:200]
        except Exception:  # noqa: BLE001 — 본문을 못 읽어도 코드만으로 보고한다
            body = ""
        raise RouteError(_scrub(f"길찾기 HTTP {e.code} {body}".rstrip(), key, olat, olng)) from None
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        # URLError 문자열에는 URL이 없지만 혹시 몰라 좌표·키를 지운다
        raise RouteError(_scrub(f"길찾기 연결 실패 {type(e).__name__}: {e}", key, olat, olng)) from None
    return parse_directions(raw.decode("utf-8", "replace"))


def kakao_fetcher(key: str, timeout: float = 3.0) -> Fetch:
    """refine 에 넘길 fetch 콜러블. 키는 클로저 안에만 둔다."""
    def fetch(olat: float, olng: float, dlat: float, dlng: float) -> Route:
        return fetch_kakao(key, olat, olng, dlat, dlng, timeout)
    return fetch


# ------------------------------------------------------------ 캐시
def _cell(x: float) -> int:
    """0.01° 격자 칸 번호. round_half_up 기반(내장 round 금지)."""
    return E.round_half_up(x * GRID_PER_DEG)


def grid_key(olat: float, olng: float, cand_id: str) -> tuple:
    """캐시 키 = (출발지 위도 칸, 경도 칸, 후보 id). 칸은 0.01° 정수라 원좌표를 되살릴 수 없다."""
    return (_cell(olat), _cell(olng), cand_id)


class RouteCache:
    """메모리 전용 TTL 캐시. 넘치면 가장 오래 전에 넣은 것부터 버린다. 스레드 안전."""

    def __init__(self, ttl_s: float = 7 * 86400, max_entries: int = 20000, clock: Callable[[], float] = time.time):
        self.ttl_s = ttl_s
        self.max_entries = max_entries
        self._clock = clock
        self._lock = threading.Lock()
        self._data: OrderedDict[tuple, tuple[float, Route]] = OrderedDict()   # 넣은 순서 = 오래된 순서

    def get(self, key: tuple) -> Route | None:
        with self._lock:
            hit = self._data.get(key)
            if hit is None:
                return None
            stored_at, route = hit
            if self._clock() - stored_at >= self.ttl_s:
                del self._data[key]
                return None
            return route

    def put(self, key: tuple, route: Route) -> None:
        with self._lock:
            self._data[key] = (self._clock(), route)
            self._data.move_to_end(key)          # 다시 넣으면 가장 새것
            while len(self._data) > self.max_entries:
                self._data.popitem(last=False)

    def __len__(self) -> int:
        with self._lock:
            return len(self._data)


# ------------------------------------------------------------ 보정
@dataclass
class RefineStats:
    hits: int = 0            # 캐시에서 꺼낸 후보 수
    calls: int = 0           # 경로 API 호출 수
    failures: int = 0        # 호출이 실패한 수(그 후보는 추정값 유지)


def _apply(c: E.Candidate, r: Route) -> E.Candidate:
    return dataclasses.replace(c, route_km=r.route_km, minutes=r.minutes, toll_krw=r.toll_krw)


def refine(olat: float, olng: float, cands: list[E.Candidate], *, fetch: Fetch, cache: RouteCache,
           max_workers: int = 5) -> tuple[list[E.Candidate], RefineStats]:
    """후보의 route_km·minutes·toll_krw 를 실경로 값으로 바꾼 새 리스트를 돌려준다(입력 순서 유지, 입력 불변).

    - 캐시(같은 1km 격자 + 같은 후보)에 있으면 호출하지 않는다.
    - 없는 것만 ThreadPoolExecutor 로 병렬 호출한다. 같은 키는 한 번만 부른다.
    - 호출이 실패한 후보는 추정값을 그대로 둔다(예외를 밖으로 내지 않는다).
    """
    stats = RefineStats()
    out = list(cands)
    pending: dict[tuple, list[int]] = {}
    for i, c in enumerate(cands):
        k = grid_key(olat, olng, c.id)
        r = cache.get(k)
        if r is not None:
            stats.hits += 1
            out[i] = _apply(c, r)
        else:
            pending.setdefault(k, []).append(i)
    if not pending:
        return out, stats

    jobs = list(pending.items())
    stats.calls = len(jobs)
    with ThreadPoolExecutor(max_workers=max(1, min(max_workers, len(jobs)))) as ex:
        futures = [ex.submit(fetch, olat, olng, cands[idx[0]].lat, cands[idx[0]].lng) for _, idx in jobs]
        for (k, idx), fut in zip(jobs, futures):
            try:
                r = fut.result()
            except Exception:  # noqa: BLE001 — 어떤 실패든 추정값으로 남긴다(메시지는 남기지 않음)
                stats.failures += 1
                continue
            cache.put(k, r)
            for i in idx:
                out[i] = _apply(cands[i], r)
    return out, stats
