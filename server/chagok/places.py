"""목적지 검색 — 카카오 로컬 키워드 검색 대행.

노고 PRD v1.3 8장(출발 화면 목적지 = 검색 + 최근 목적지), 명세 v0.5 F20 입력, #56.
GET /v1/places?query=&lat=&lng= (main.py)가 부른다. 앱은 고른 결과의 name·address·lat·lng·place_id를
그대로 requestRoute(RouteInfo)에 넘긴다(poiName·address·latitude·longitude·poiId).

- 결과는 최대 10곳. 이름이 비슷한 결과를 목록에서 구분하도록 category(카카오 분류 마지막 칸)와
  distance_m(현재 위치를 보냈을 때 카카오가 준 거리)을 같이 준다(June 제안 #57, 효성 결정 11/7).
- category_path는 카카오 분류의 모든 칸이다(윗칸부터). 출발 미리보기 배경의 장소 종류를 고르는 데 쓴다(#116, 11/15).
  카카오가 준 값을 나눠 그대로 돌려줄 뿐, 새로 밖으로 보내는 값은 없다.
- 검색은 됐는데 결과가 없으면 빈 목록 + status "empty",
  키 없음·연결 실패·카카오 오류·3초 초과는 빈 목록 + "unavailable"(둘 다 200).
- 현재 위치(lat·lng)를 같이 받으면 카카오에 x·y로 넘겨 가까운 곳이 위로 오게 한다. 정렬은 accuracy 그대로 둔다.
  distance(거리순)로 바꾸면 '두물머리'에서 근처 '두물머리해장국'이 두물머리보다 먼저 나온다(11/7 실호출).
  accuracy에 x·y를 주면 이름이 분명한 곳은 그곳이 먼저, '스타벅스' 같은 체인은 가까운 지점이 먼저 나온다.
- 검색어·현재 위치·결과(이름·좌표)는 저장·캐시·로그하지 않는다(AC-N-4). 카카오 요청 URL에 검색어·좌표가
  들어가므로 URL을 어디에도 찍지 않는다.
- 키는 KAKAO_REST_API_KEY(서버 .env). Authorization 헤더로만 보낸다.
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
from dataclasses import asdict, dataclass

from .engine import round_half_up

URL = "https://dapi.kakao.com/v2/local/search/keyword.json"
TIMEOUT_S = 3.0          # #56: 3초 안에 돌려준다. 넘기면 unavailable
MAX_RESULTS = 10         # #56: 최대 10곳 (카카오 size는 1~15)
SORT = "accuracy"        # 위 설명 참고. distance로 바꾸지 않는다


class PlacesError(RuntimeError):
    """kind = 로그에 남길 오류 종류(짧은 영문). 키·URL·검색어·좌표·응답 본문은 싣지 않는다."""

    def __init__(self, kind: str):
        super().__init__(kind)
        self.kind = kind


@dataclass(frozen=True)
class Place:
    name: str           # 카카오 place_name → RouteInfo.poiName
    address: str        # 도로명 주소, 없으면 지번 주소 → RouteInfo.address
    lat: float          # 카카오 y → RouteInfo.latitude
    lng: float          # 카카오 x → RouteInfo.longitude
    place_id: str       # 카카오 장소 id → RouteInfo.poiId
    category: str | None = None     # 카카오 category_name 마지막 칸("여행 > 관광,명소" → "관광,명소"). 없으면 None
    distance_m: int | None = None   # 현재 위치에서의 거리(m). 현재 위치를 안 보냈으면 카카오가 빈 값 → None
    # 카카오 category_name의 모든 칸, 윗칸부터("여행 > 관광,명소" → ("여행", "관광,명소")). 없으면 ()
    # 지하철역·기차역은 마지막 칸이 노선 이름("수도권2호선")이라 윗칸("지하철,전철")이 있어야 종류를 안다(#116)
    category_path: tuple[str, ...] = ()

    def public(self) -> dict:
        return {**asdict(self), "category_path": list(self.category_path)}


@dataclass(frozen=True)
class Result:
    status: str                    # ok | empty | unavailable
    places: tuple[Place, ...] = ()
    err: str | None = None         # 로그용 오류 종류. 성공·결과 없음이면 None

    def public(self) -> dict:
        """GET /v1/places 응답 본문. err는 싣지 않는다."""
        return {"status": self.status, "places": [p.public() for p in self.places]}


def unavailable(err: str) -> Result:
    return Result("unavailable", (), err)


# ------------------------------------------------------------ 응답 해석
def _coord(v, lo: float, hi: float) -> float | None:
    try:
        f = float(str(v).strip())
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) and lo <= f <= hi else None


def _category_path(v) -> tuple[str, ...]:
    """'교통,수송 > 지하철,전철 > 수도권2호선' → ('교통,수송', '지하철,전철', '수도권2호선'). 빈 칸은 버린다."""
    return tuple(p for p in (s.strip() for s in str(v or "").split(">")) if p)


def _category(v) -> str | None:
    """'음식점 > 패스트푸드' → '패스트푸드'. category_group_name은 '두물머리'처럼 빈 곳이 있어 쓰지 않는다."""
    last = str(v or "").split(">")[-1].strip()
    return last or None


def _distance_m(v) -> int | None:
    """카카오 distance(m, 문자열). x·y를 안 보냈으면 "" → None."""
    try:
        f = float(str(v).strip())
    except (TypeError, ValueError):
        return None
    return round_half_up(f) if math.isfinite(f) and f >= 0 else None


def parse_keyword(text: str) -> list[Place]:
    """키워드 검색 JSON → Place 목록(앞에서부터 최대 MAX_RESULTS). 쓸 수 없으면 PlacesError.

    응답 모양: {"meta": {"total_count": 94, "is_end": false, ...},
                "documents": [{"id": "7845373", "place_name": "두물머리", "address_name": "경기 양평군 양서면 양수리",
                               "road_address_name": "", "x": "127.315829778049", "y": "37.5328127891601",
                               "category_name": "여행 > 관광,명소", "distance": "23671", ...}]}
    이름·id·좌표가 없거나 좌표가 숫자가 아닌 항목은 버린다.
    """
    try:
        data = json.loads(text)
    except (json.JSONDecodeError, TypeError):
        raise PlacesError("not_json") from None
    docs = data.get("documents") if isinstance(data, dict) else None
    if not isinstance(docs, list):
        raise PlacesError("no_documents")
    out: list[Place] = []
    for d in docs:
        if not isinstance(d, dict):
            continue
        name = str(d.get("place_name") or "").strip()
        pid = str(d.get("id") or "").strip()
        lat, lng = _coord(d.get("y"), -90, 90), _coord(d.get("x"), -180, 180)
        if not name or not pid or lat is None or lng is None:
            continue
        address = str(d.get("road_address_name") or "").strip() or str(d.get("address_name") or "").strip()
        cat = d.get("category_name")
        out.append(Place(name, address, lat, lng, pid, _category(cat), _distance_m(d.get("distance")), _category_path(cat)))
        if len(out) == MAX_RESULTS:
            break
    return out


# ------------------------------------------------------------ 출발 미리보기 배경의 장소 종류(참조 구현)
def preview_bg_kind(category_path, table: dict) -> str:
    """카카오 분류(윗칸부터) → 출발 미리보기 배경 종류. table = constants.json의 preview_bg(#116).

    ① 마지막 칸이 leaf에 정확히 있으면 그 종류 ② 아니면 윗칸 가운데 upper에 정확히 있는 칸의 종류
    ③ 둘 다 아니면 neutral. 이름 속 낱말로는 고르지 않는다(강남역 · 부산역이 '강' · '산'으로 걸린다).
    서버 응답에는 쓰지 않는다. 앱(Kotlin)이 같은 표와 같은 순서로 고르고, 이 함수는 표의 examples를 맞추는 기준이다.
    """
    path = [str(p) for p in (category_path or ())]
    if not path:
        return "neutral"
    leaf, upper = table.get("leaf", {}), table.get("upper", {})
    if path[-1] in leaf:
        return leaf[path[-1]]
    for seg in reversed(path[:-1]):      # 마지막 칸에 가까운 윗칸부터
        if seg in upper:
            return upper[seg]
    return "neutral"


# ------------------------------------------------------------ 카카오 호출
def fetch_keyword(key: str, query: str, near: tuple[float, float] | None, timeout: float) -> str:
    """키워드 검색 1회 → 응답 본문. near = (lat, lng). 실패하면 PlacesError(kind만)."""
    q: dict = {"query": query, "page": 1, "size": MAX_RESULTS, "sort": SORT}
    if near is not None:
        q["y"], q["x"] = near            # 카카오는 x = 경도, y = 위도
    url = f"{URL}?{urllib.parse.urlencode(q)}"   # 검색어·좌표가 URL에 들어간다 → URL은 어디에도 찍지 않는다
    req = urllib.request.Request(url, headers={"Authorization": f"KakaoAK {key}", "User-Agent": "Chagok"})
    try:
        raw = urllib.request.urlopen(req, timeout=timeout).read()
    except urllib.error.HTTPError as e:
        raise PlacesError(f"http_{e.code}") from None      # 401 키 오류, 403 카카오맵 서비스 꺼짐, 429 한도
    except urllib.error.URLError as e:
        timed_out = isinstance(e.reason, (TimeoutError, socket.timeout))
        raise PlacesError("timeout" if timed_out else "network") from None
    except (TimeoutError, socket.timeout):
        raise PlacesError("timeout") from None
    except OSError:
        raise PlacesError("network") from None
    return raw.decode("utf-8", "replace")


# 3초 마감용(weather.py와 같은 방식). urlopen의 timeout은 연결·읽기 한 번마다라서 전체 시간을 묶지 못한다.
_POOL = ThreadPoolExecutor(max_workers=8, thread_name_prefix="kakao-local")


def search(query: str, key: str | None, *, near: tuple[float, float] | None = None,
           timeout: float | None = None, fetch: Callable[[], str] | None = None) -> Result:
    """목적지 검색. 어떤 실패도 예외로 올리지 않고 unavailable을 돌려준다.

    fetch는 테스트용(카카오 응답 본문을 돌려주는 함수). 없으면 fetch_keyword.
    """
    if not key:
        return unavailable("no_key")
    limit = TIMEOUT_S if timeout is None else timeout
    call = fetch or (lambda: fetch_keyword(key, query, near, limit))
    try:
        text = _POOL.submit(call).result(timeout=limit)
        found = parse_keyword(text)
    except PlacesError as e:
        return unavailable(e.kind)
    except TimeoutError:          # concurrent.futures.TimeoutError = TimeoutError (3.11+)
        return unavailable("timeout")
    except Exception as e:        # 무엇이 와도 출발 화면을 막지 않는다. 종류만 남긴다
        return unavailable(type(e).__name__)
    return Result("ok" if found else "empty", tuple(found))
