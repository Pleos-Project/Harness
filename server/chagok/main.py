"""차곡 서버. 운전 노고 정산(명세 v0.5, #46)에서 앱이 부르는 건 세 가지다.
- GET /v1/weather: 출발·도착 지점 강수(chagok/weather.py)
- GET /v1/weather/trip: 도착 때 한 번, 출발~도착 1시간 조각마다 두 지점 강수(chagok/weather.py, #63)
- GET /v1/places: 출발 화면 목적지 검색, 카카오 로컬 키워드 검색 대행(chagok/places.py, 노고 PRD v1.3 8장)
좌표·시각·검색어는 그 조회 한 번에만 쓰고 저장·캐시·로그하지 않는다(AC-N-4).

피봇 전 흐름(정리 전까지 남겨 둔다, DECISIONS 11/7 '다음 PR로 넘김' ④):
/v1/cards, /v1/cards/swap, /v1/prices(평균 단가, 읽기 전용).
/v1/range, /v1/settle 은 검증용 참조 구현(앱 로컬 계산과 fixture 대조용) — 앱은 부르지 않는다.

모드: 요청에 demo_seed가 있으면 데모 고정(후보 = fixtures, 네트워크 없음),
      없으면 실서비스(TourAPI 캐시 + 상위 후보만 카카오 실경로, chagok/live.py). 캐시가 비어 있으면 503 → 앱은 데모 fallback.

실행:  make dev    (개발, --reload. 코드가 바뀌면 세션이 날아간다)
       make serve  (리허설·PT, reload 없음)
       배포: deploy/README.md
"""

from __future__ import annotations

import time
from contextlib import asynccontextmanager
from typing import Annotated, Literal

from fastapi import FastAPI, HTTPException, Query, Request, Response
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, StringConstraints, model_validator

from . import engine as E
from . import live
from . import places as PL
from . import prices as P
from . import routes as R
from . import sources as S
from . import weather as W
from .cards import SessionStore, build_cards, few_hint, load_constants, load_demo_candidates, swap
from .obs import log

@asynccontextmanager
async def _lifespan(_app):
    rows, refreshed = INDEX.rows()
    log("start", live_candidates=len(rows), tourapi_refreshed=refreshed, kakao=KAKAO is not None)
    yield


app = FastAPI(title="chagok", version="0.6.0", lifespan=_lifespan)
CONST = load_constants()
DEMO = load_demo_candidates()
STORE = SessionStore()

# 실서비스 모드(demo_seed 없음). 키는 서버 .env 에만(CLAUDE.md). 카카오 키가 없으면 추정값만 쓴다.
ENV = S.load_env()
INDEX = live.CandidateIndex()
POOLS = live.LivePools()
ROUTE_CACHE = R.RouteCache()
KAKAO = R.kakao_fetcher(ENV["KAKAO_REST_API_KEY"], timeout=live.ROUTE_TIMEOUT_S) if ENV.get("KAKAO_REST_API_KEY") else None
PRICES = P.PriceReader()  # 오피넷 전국 평균(크론 KST 04:20 갱신). DB 없으면 /v1/prices 503
# 기상청 초단기실황도 공공데이터포털 계정 키를 같이 쓴다(명세 v0.5 7절). 없으면 /v1/weather는 늘 unknown
WEATHER_KEY = ENV.get("TOURAPI_SERVICE_KEY") or None
# 목적지 검색(카카오 로컬)은 길찾기와 같은 카카오 REST 키를 쓴다. 없으면 /v1/places는 늘 unavailable
PLACES_KEY = ENV.get("KAKAO_REST_API_KEY") or None

Theme = Literal["sea", "lake", "river", "mountain", "park", "city", "island", "drive"]


class Strict(BaseModel):
    # 모르는 필드는 422. 잔량·주행가능거리·주행거리 같은 원시 차량값이 실수로 실려 와도 받지 않는다(PRD 5-2).
    model_config = ConfigDict(extra="forbid")


class Origin(Strict):
    lat: float
    lng: float


class BudgetIn(Strict):
    fuel_type: Literal["ev", "ice"]
    usable_km: float
    margin_km: float = Field(ge=0)
    efficiency: float = Field(gt=0)
    price_per_unit: float = Field(gt=0)
    price_source: Literal["user", "default"] = "user"


class CardsRequest(Strict):
    origin: Origin
    budget: BudgetIn
    seats_occupied: int = Field(1, ge=1, le=9)
    extra_krw: Literal[0, 10000, 30000] = 0
    days: Literal[1, 2] = 1
    themes: list[Theme] = Field(default_factory=list, max_length=2)
    recent_visits: list[str] = Field(default_factory=list, max_length=10)
    demo_seed: str | None = None
    session_id: str | None = None


class SwapRequest(Strict):
    session_id: str
    exclude_ids: list[str] = Field(default_factory=list)


class VehicleRef(Strict):
    fuel_type: Literal["ev", "ice"]
    level_pct: float
    capacity: float
    efficiency: float
    price_per_unit: float
    remaining_range_m: float = 0
    price_source: Literal["user", "default"] = "user"


class RangeRequest(Strict):
    vehicle: VehicleRef
    extra_krw: Literal[0, 10000, 30000] = 0
    days: Literal[1, 2] = 1


class SettleRequest(Strict):
    snapshot_prev: dict | None
    odometer_now: float
    last_destination: str | None = None
    span_days: int = 0


class WeatherQuery(Strict):
    # 쿼리는 lat·lon 둘뿐. 다른 쿼리(누적 주행거리·VIN 같은 원시 차량값)가 붙으면 422(extra="forbid")
    lat: float = Field(ge=-90, le=90, allow_inf_nan=False)
    lon: float = Field(ge=-180, le=180, allow_inf_nan=False)


class WeatherTripQuery(Strict):
    # 쿼리는 두 지점 좌표와 출발·도착 시각뿐. 다른 쿼리(원시 차량값, /v1/weather의 lat·lon 등)가 붙으면 422(extra="forbid")
    dep_lat: float = Field(ge=-90, le=90, allow_inf_nan=False)
    dep_lon: float = Field(ge=-180, le=180, allow_inf_nan=False)
    arr_lat: float = Field(ge=-90, le=90, allow_inf_nan=False)
    arr_lon: float = Field(ge=-180, le=180, allow_inf_nan=False)
    # ISO 8601, 시간대 필수(2026-11-07T14:20:00+09:00 또는 ...Z). 쿼리에서 +는 %2B로 보낸다(안 그러면 공백이 돼 422)
    from_: AwareDatetime = Field(alias="from")
    to: AwareDatetime

    @model_validator(mode="after")
    def _span(self):
        if self.from_ >= self.to:
            raise ValueError("from은 to보다 앞이어야 해요")
        if self.to - self.from_ > W.TRIP_MAX:
            raise ValueError("출발~도착은 24시간까지만 받아요(기상청은 최근 24시간 실황만 줘요)")
        if self.to > W.now_kst() + W.FUTURE_SLACK:
            raise ValueError("to가 지금보다 10분 넘게 늦어요")
        return self


class PlacesQuery(Strict):
    # 쿼리는 검색어와 (선택) 현재 위치 lat·lng뿐. 다른 쿼리가 붙으면 422(extra="forbid")
    query: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]
    lat: float | None = Field(None, ge=-90, le=90, allow_inf_nan=False)
    lng: float | None = Field(None, ge=-180, le=180, allow_inf_nan=False)

    @model_validator(mode="after")
    def _both_or_neither(self):
        if (self.lat is None) != (self.lng is None):
            raise ValueError("lat·lng는 둘 다 보내거나 둘 다 빼요")
        return self


@app.get("/v1/health")
def health() -> dict:
    rows, refreshed = INDEX.rows()
    return {"ok": True, "demo_candidates": len(DEMO), "constants_seed": CONST["demo_seed"],
            "live_candidates": len(rows), "tourapi_refreshed": refreshed, "kakao": KAKAO is not None,
            "weather": WEATHER_KEY is not None}


def _ms(t0: float) -> int:
    return int((time.perf_counter() - t0) * 1000)


@app.get("/v1/weather")
def weather(q: Annotated[WeatherQuery, Query()], response: Response) -> dict:
    """한 지점의 지금 강수 형태(기상청 초단기실황 PTY). 명세 v0.5 F20·F22, 12절.
    실패·3초 초과는 200 + precip "unknown" → 앱은 '정보 없음'(가산 0)으로 두고 진행한다(AC-C-6).
    좌표·격자는 로그에 남기지 않는다(AC-N-4). URL에 좌표가 들어가므로 응답을 어디에도 캐시하지 않게 한다."""
    t0 = time.perf_counter()
    res = W.lookup(q.lat, q.lon, WEATHER_KEY)
    response.headers["Cache-Control"] = "no-store"
    log("weather", status=200, ms=_ms(t0), precip=res.precip, **({"err": res.err} if res.err else {}))
    return res.public()


@app.get("/v1/weather/trip")
def weather_trip(q: Annotated[WeatherTripQuery, Query()], response: Response) -> dict:
    """도착 때 한 번, 출발~도착 1시간 조각마다 출발·도착 지점 강수(기상청 초단기실황 PTY·RN1). #63.
    실패·3초 초과 조각은 unknown, 전부 실패면 status "unavailable"(모두 200) → 앱은 지금 규칙(출발·도착 1회)으로.
    좌표·시각은 로그에 남기지 않는다(AC-N-4). URL에 좌표가 들어가므로 응답을 캐시하지 않게 한다."""
    t0 = time.perf_counter()
    res = W.trip((q.dep_lat, q.dep_lon), (q.arr_lat, q.arr_lon), q.from_, q.to, WEATHER_KEY, now=W.now_kst())
    response.headers["Cache-Control"] = "no-store"
    log("weather_trip", status=200, ms=_ms(t0), result=res.status, hours=len(res.hours), calls=res.calls,
        unknown=res.unknown, **({"err": ",".join(res.errs)} if res.errs else {}))
    return res.public()


@app.get("/v1/places")
def places_search(q: Annotated[PlacesQuery, Query()], response: Response) -> dict:
    """출발 화면 목적지 검색(카카오 로컬 키워드 검색 대행). 노고 PRD v1.3 8장, #56.
    결과는 최대 10곳, 실패·3초 초과는 200 + 빈 목록 + status "unavailable".
    검색어·현재 위치·결과는 로그에 남기지 않는다(AC-N-4). URL에 검색어·좌표가 들어가므로 응답을 캐시하지 않게 한다."""
    t0 = time.perf_counter()
    near = (q.lat, q.lng) if q.lat is not None and q.lng is not None else None
    res = PL.search(q.query, PLACES_KEY, near=near)
    response.headers["Cache-Control"] = "no-store"
    log("places", status=200, ms=_ms(t0), result=res.status, n=len(res.places), near=near is not None,
        **({"err": res.err} if res.err else {}))
    return res.public()


@app.get("/v1/prices")
def prices_latest(request: Request) -> dict:
    """오피넷 전국 평균 단가(읽기 전용). 앱은 사용자 단가가 없을 때 이 값을 '평균 단가'(price_source=default)로 쓴다.
    입력이 없다. 쿼리 문자열이 붙으면 422 — /v1/cards 의 extra="forbid"처럼 원시 차량값이 실려 와도 받지 않는다(PRD 5-2).
    아직 한 번도 못 받았으면 503 → 앱은 내장 기본값(fixtures/constants.json)."""
    t0 = time.perf_counter()
    if request.query_params:
        log("prices", status=422, ms=_ms(t0), err="unexpected_query")
        raise HTTPException(status_code=422, detail={"error": "unexpected_query"})
    res = PRICES.public()
    if res is None:
        log("prices", status=503, ms=_ms(t0), err="prices_not_ready")
        raise HTTPException(status_code=503, detail={"error": "prices_not_ready"})
    log("prices", status=200, ms=_ms(t0), trade_dt=res["trade_dt"])
    return res


@app.post("/v1/cards")
def cards(req: CardsRequest) -> dict:
    t0 = time.perf_counter()
    body = req.model_dump()
    common = {"recall": req.session_id is not None, "extra_krw": req.extra_krw, "days": req.days, "themes": len(req.themes)}
    if req.demo_seed is not None:
        # 데모 고정 모드: 후보 = fixtures, 네트워크 없음, 같은 입력 → 같은 출력(fixtures/cards/D*.json)
        res = build_cards(body, DEMO, STORE)
        log("cards", mode="demo", status=200, ms=_ms(t0), in_range=res["candidates_in_range"],
            picked=res["picked"] is not None, **common)
        return res
    rows, _ = INDEX.rows()
    if not rows:
        log("cards", mode="live", status=503, ms=_ms(t0), err="candidates_not_ready", **common)
        raise HTTPException(status_code=503, detail={"error": "candidates_not_ready"})
    try:
        sess = STORE.get(req.session_id) if req.session_id else None
        keep = [i for i in ([sess["picked_id"]] + sess["stack_ids"]) if i] if sess else []
        prev = POOLS.get(req.session_id) if sess else None
        cands, refined, st = live.candidates_for(body, rows, fetch=KAKAO, cache=ROUTE_CACHE, keep_ids=keep, refined=prev)
        res = build_cards(body, cands, STORE)
        # 순위는 실경로 후보끼리 매기지만, '범위 안 후보'·힌트는 추정 기준 전체 수로(후보가 12곳뿐인 게 아니므로)
        res["candidates_in_range"] = st.in_range
        res["hint"] = few_hint(st.in_range, req.extra_krw, req.themes)
        if res["session_id"]:
            POOLS.put(res["session_id"], refined)
    except Exception as e:
        log("cards", mode="live", status=500, ms=_ms(t0), err=type(e).__name__, **common)
        raise
    log("cards", mode="live", status=200, ms=_ms(t0), pool=st.pool, in_range=st.in_range, ranked=len(cands),
        ranked_on=st.ranked_on, picked=res["picked"] is not None, route_calls=st.calls, route_hits=st.hits,
        route_fail=st.failures, **common)
    return res


@app.post("/v1/cards/swap")
def cards_swap(req: SwapRequest) -> dict:
    t0 = time.perf_counter()
    refined = POOLS.get(req.session_id)
    mode = "demo" if refined is None else "live"
    if refined is None:
        candidates = DEMO
    else:
        sess = STORE.get(req.session_id)
        candidates = live.swap_candidates(sess["request"], INDEX.rows()[0], refined) if sess else []
    res = swap(req.session_id, req.exclude_ids, candidates, STORE)
    if "error" in res:
        code = 404 if res["error"] == "session_not_found" else 409
        log("swap", mode=mode, status=code, ms=_ms(t0), err=res["error"])
        raise HTTPException(status_code=code, detail=res)
    log("swap", mode=mode, status=200, ms=_ms(t0), swaps_used=res["swaps_used"])
    return res


# ---- 검증용 참조 구현 (앱은 부르지 않는다)
@app.post("/v1/range")
def range_ref(req: RangeRequest) -> dict:
    return vars(E.compute_range(E.Vehicle(**req.vehicle.model_dump()), req.extra_krw, req.days))


@app.post("/v1/settle")
def settle_ref(req: SettleRequest) -> dict:
    prev = E.Snapshot(**req.snapshot_prev) if req.snapshot_prev else None
    return vars(E.settle(prev, req.odometer_now, req.last_destination, req.span_days))
