"""차곡 서버. 앱이 부르는 건 /v1/cards, /v1/cards/swap 둘뿐.
/v1/range, /v1/settle 은 검증용 참조 구현(앱 로컬 계산과 fixture 대조용) — 앱은 부르지 않는다.

실행:  make dev    (개발, --reload. 코드가 바뀌면 세션이 날아간다)
       make serve  (리허설·PT, reload 없음)
"""

from __future__ import annotations

from typing import Literal

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from . import engine as E
from .cards import SessionStore, build_cards, load_constants, load_demo_candidates, swap

app = FastAPI(title="chagok", version="0.2.0")
CONST = load_constants()
DEMO = load_demo_candidates()
STORE = SessionStore()

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


@app.get("/v1/health")
def health() -> dict:
    return {"ok": True, "demo_candidates": len(DEMO), "constants_seed": CONST["demo_seed"]}


@app.post("/v1/cards")
def cards(req: CardsRequest) -> dict:
    # 1차는 데모 고정 모드뿐(후보 풀 = fixtures). 실서비스 모드는 demo_seed가 없을 때 TourAPI 캐시로 교체.
    return build_cards(req.model_dump(), DEMO, STORE)


@app.post("/v1/cards/swap")
def cards_swap(req: SwapRequest) -> dict:
    res = swap(req.session_id, req.exclude_ids, DEMO, STORE)
    if "error" in res:
        raise HTTPException(status_code=404 if res["error"] == "session_not_found" else 409, detail=res)
    return res


# ---- 검증용 참조 구현 (앱은 부르지 않는다)
@app.post("/v1/range")
def range_ref(req: RangeRequest) -> dict:
    return vars(E.compute_range(E.Vehicle(**req.vehicle.model_dump()), req.extra_krw, req.days))


@app.post("/v1/settle")
def settle_ref(req: SettleRequest) -> dict:
    prev = E.Snapshot(**req.snapshot_prev) if req.snapshot_prev else None
    return vars(E.settle(prev, req.odometer_now, req.last_destination, req.span_days))
