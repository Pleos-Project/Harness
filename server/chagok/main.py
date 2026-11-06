"""차곡 서버. 앱이 부르는 건 /v1/cards, /v1/cards/swap, /v1/prices(평균 단가, 읽기 전용).
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
from typing import Literal

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field

from . import cards as C
from . import engine as E
from . import live
from . import prices as P
from . import routes as R
from . import sources as S
from . import weather as W
from .cards import SessionStore, build_cards, load_constants, load_demo_candidates, pick_hint, swap
from .obs import log

@asynccontextmanager
async def _lifespan(_app):
    rows, refreshed = INDEX.rows()
    log("start", live_candidates=len(rows), tourapi_refreshed=refreshed, kakao=KAKAO is not None, ranker=RANKER)
    yield


app = FastAPI(title="chagok", version="0.3.0", lifespan=_lifespan)
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
WEATHER = W.WeatherIndex()  # 기상청 단기예보(크론). 없으면 모두 맑음 취급
# 순위 규칙: a1 = 규칙(목적지성·날씨), b = a1 + A2 사전 큐레이션. 블라인드 검수(PRD 7-4)로 고른 쪽을 .env에 둔다
RANKER = ENV.get("CHAGOK_RANKER", "a1").strip() or "a1"
if RANKER not in C.VARIANTS:
    raise RuntimeError(f"CHAGOK_RANKER는 {C.VARIANTS} 중 하나: {RANKER!r}")

Theme = Literal["sea", "lake", "river", "mountain", "park", "city", "island", "drive", "indoor", "festival"]


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


@app.get("/v1/health")
def health() -> dict:
    rows, _, cur, refreshed = INDEX.snapshot()
    WEATHER.rain_map(W.trip_date())
    return {"ok": True, "demo_candidates": len(DEMO), "constants_seed": CONST["demo_seed"],
            "live_candidates": len(rows), "tourapi_refreshed": refreshed, "kakao": KAKAO is not None,
            "ranker": RANKER, "curated": len(cur), "weather_refreshed": WEATHER.refreshed}


def _ms(t0: float) -> int:
    return int((time.perf_counter() - t0) * 1000)


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
    common = {"recall": req.session_id is not None, "extra_krw": req.extra_krw, "days": req.days,
              "themes": len(req.themes), "ranker": RANKER}
    if req.demo_seed is not None:
        # 데모 고정 모드: 후보 = fixtures, 네트워크 없음, 같은 입력 → 같은 출력(fixtures/cards/D*.json). 날씨 맑음 고정
        res = build_cards(body, DEMO, STORE, C.demo_ctx(RANKER))
        log("cards", mode="demo", status=200, ms=_ms(t0), in_range=res["candidates_in_range"],
            picked=res["picked"] is not None, **common)
        return res
    rows, meta, cur, _ = INDEX.snapshot()
    if not rows:
        log("cards", mode="live", status=503, ms=_ms(t0), err="candidates_not_ready", **common)
        raise HTTPException(status_code=503, detail={"error": "candidates_not_ready"})
    try:
        sess = STORE.get(req.session_id) if req.session_id else None
        keep = [i for i in ([sess["picked_id"]] + sess["stack_ids"]) if i] if sess else []
        prev = POOLS.get(req.session_id) if sess else None
        day = W.trip_date()
        ctx = E.RankContext(cfg=C.RANKING, rain=WEATHER.rain_map(day), trip_date=day, variant=RANKER)
        cands, refined, st = live.candidates_for(body, rows, fetch=KAKAO, cache=ROUTE_CACHE, keep_ids=keep, refined=prev,
                                                 ctx=ctx, meta=meta, cur=cur)
        res = build_cards(body, cands, STORE, ctx)
        # 순위는 실경로 후보끼리 매기지만, '범위 안 후보'·힌트는 추정 기준 전체 수로(후보가 12곳뿐인 게 아니므로)
        res["candidates_in_range"] = st.in_range
        res["hint"] = pick_hint(st.in_range, req.extra_krw, req.themes, res["picked"], ctx)
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
        rows, meta, cur, _ = INDEX.snapshot()
        candidates = live.swap_candidates(sess["request"], rows, refined, meta, cur) if sess else []
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
