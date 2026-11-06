"""F5 한 장 + 스택 + 교체 세션.

앱의 오프라인 데모 모드(Kotlin app/android/app/src/main/java/com/mist/pleos_app/engine/DemoServer.kt)가 이 파일과 같은 규칙을 갖고,
fixtures/cards/*.json 으로 둘을 대조한다. 여기를 바꾸면 Kotlin도 같은 PR에서 바꾼다.

데모 고정 모드: 같은 입력 → 같은 출력. 후보 풀은 fixtures/candidates_demo.json.
실서비스 모드(TourAPI 캐시 + 경로 API)는 candidates만 바꿔 넣는다.
"""

from __future__ import annotations

import json
import os
import time
import uuid
from pathlib import Path

from . import engine as E

FIXTURES = Path(os.environ.get("CHAGOK_FIXTURES") or Path(__file__).resolve().parents[2] / "fixtures")
SESSION_TTL_S = 30 * 60
STACK_SIZE = 3


def load_constants() -> dict:
    return json.loads((FIXTURES / "constants.json").read_text(encoding="utf-8"))


def load_demo_candidates() -> list[E.Candidate]:
    raw = json.loads((FIXTURES / "candidates_demo.json").read_text(encoding="utf-8"))
    return [E.Candidate(**c) for c in raw["candidates"]]


def load_ranking() -> E.RankingConfig:
    """fixtures/constants.json "ranking" — A1·B 점수 값(엔진 두 벌이 같이 읽는다)."""
    return E.RankingConfig.from_json(load_constants()["ranking"])


RANKING = load_ranking()
VARIANTS = ("a1", "b")   # a1 = 규칙(목적지성·날씨), b = a1 + A2 평판·소개(블라인드 검수로 고른다)


def demo_ctx(variant: str = "a1") -> E.RankContext:
    """데모 고정 모드: 날씨 맑음 고정, 행사 날짜 안 봄. 데모 후보는 소분류가 없어 결과가 기존 규칙과 같다."""
    return E.RankContext(cfg=RANKING, variant=variant)


def budget_from(req: dict) -> E.Budget:
    b = req["budget"]
    return E.Budget(fuel_type=b["fuel_type"], usable_km=b["usable_km"], margin_km=b["margin_km"],
                    efficiency=b["efficiency"], price_per_unit=b["price_per_unit"],
                    price_source=b.get("price_source", "user"))


# ---------------------------------------------------------------- 순위
def evaluate(candidates: list[E.Candidate], b: E.Budget, seats: int, themes: list[str],
             recent_ids: list[str], ctx: E.RankContext | None = None, days: int = 1) -> list[dict]:
    """후보를 카드로 만든다. 최근 방문·90분 초과·+3만 밖은 뺀다. 항목 = card_json + _score·_route_km.
    ctx가 있으면 A1 규칙: 제외 이름·소분류, 나들이 날 안 열리는 행사도 빼고 목적지성·날씨(B는 평판)를 점수에 더한다."""
    recent_themes = [c.theme for c in candidates if c.id in recent_ids]
    cfg = ctx.cfg if ctx is not None else None
    out = []
    for c in candidates:
        if c.id in recent_ids or E.time_label(c.minutes) is None:
            continue
        if ctx is not None and (E.excluded(c, cfg) or not E.event_active(c, ctx.trip_date, days)):
            continue
        badge = E.compute_badge(c, b)
        if badge.ring_krw is None:
            continue
        s = E.score(c, badge, themes, recent_themes, ctx)
        cj = E.card_json(c, E.compute_cost(c, b, seats), badge, E.reason_line(c, s, badge, ctx), ctx)
        cj["_score"] = E.weighted(s, cfg)
        cj["_route_km"] = c.route_km
        out.append(cj)
    return out


def sort_ranked(cards: list[dict], radius: float) -> list[dict]:
    # 동점이면 "반경의 절반쯤 쓰는 곳" 우선 [제안, DECISIONS.md]. 같은 값이면 id 순(결정적).
    target = radius * 0.5
    return sorted(cards, key=lambda x: (-x["_score"], abs(x["_route_km"] - target), x["id"]))


def eligible(card: dict, extra_krw: int, themes: list[str]) -> bool:
    """새로 뽑을 때의 경계: 고른 프리셋 안 + 고른 테마 안(PRD F2 범위, F3 '테마 경계')."""
    return card["display"]["ring_krw"] <= extra_krw and (not themes or card["theme"] in themes)


def build_stack(picked: dict, pool: list[dict], size: int = STACK_SIZE) -> list[dict]:
    """스택: 상위 9장 안에서 테마가 서로 다르게, 그중 1장은 75~90분(조금 먼 곳). 화면·교체 순서 = 이 순서."""
    pool = [c for c in pool if c["id"] != picked["id"]][:9]
    stack: list[dict] = []
    used_themes = {picked["theme"]}
    far = next((c for c in pool if c["time_label"] == "조금 먼 곳"), None)
    if far:
        stack.append(far)
        used_themes.add(far["theme"])
    for c in pool:
        if len(stack) >= size:
            break
        if c is far or c["theme"] in used_themes:
            continue
        stack.append(c)
        used_themes.add(c["theme"])
    for c in pool:  # 테마가 모자라면 채움
        if len(stack) >= size:
            break
        if c not in stack:
            stack.append(c)
    return stack


def pick_hint(n: int, extra_krw: int, themes: list[str], picked: dict | None,
              ctx: E.RankContext | None) -> dict | None:
    """응답 hint 하나. '후보가 적어요'가 먼저, 아니면 비 예보인데 야외 테마를 골라 야외 카드가 나온 경우 '실내로 바꿔 볼까요?'."""
    h = few_hint(n, extra_krw, themes)
    if h is not None or ctx is None or picked is None:
        return h
    if themes and "indoor" not in themes and not picked.get("indoor") and picked.get("rain_level", 0) >= 0.5:
        return dict(ctx.cfg.rain_hint)
    return None


def few_hint(n: int, extra_krw: int, themes: list[str]) -> dict | None:
    """X2 '범위 안 후보가 적어요' + 테마 해제 / 다음 프리셋."""
    if n >= 3:
        return None
    actions = ["clear_themes"] if themes else []
    nxt = next((r for r in E.RING_KRW if r > extra_krw), None)
    if nxt:
        actions.append(f"extra_{nxt}")
    return {"code": "few_candidates", "text": "범위 안 후보가 적어요", "actions": actions}


# ---------------------------------------------------------------- 세션
class SessionStore:
    """메모리 세션(30분). 디스크·로그에 쓰지 않는다(PRD 7-1 위치 이력 저장 금지).
    session_id → {picked_id, stack_ids, excluded, swaps, request, t}"""

    def __init__(self, clock=time.monotonic, ids=lambda: str(uuid.uuid4())) -> None:
        self._s: dict[str, dict] = {}
        self._clock = clock
        self._ids = ids

    def _prune(self) -> None:
        now = self._clock()
        for k in [k for k, v in self._s.items() if now - v["t"] > SESSION_TTL_S]:
            del self._s[k]

    def new(self, picked_id: str, stack_ids: list[str], request: dict, ctx: E.RankContext | None = None) -> str:
        self._prune()
        sid = self._ids()
        self._s[sid] = {"picked_id": picked_id, "stack_ids": stack_ids, "excluded": [], "swaps": 0,
                        "request": request, "ctx": ctx, "t": self._clock()}
        return sid

    def get(self, sid: str) -> dict | None:
        self._prune()
        s = self._s.get(sid)
        if s:
            s["t"] = self._clock()
        return s


def strip(card: dict | None) -> dict | None:
    return {k: v for k, v in card.items() if not k.startswith("_")} if card else None


def _ranked(req: dict, candidates: list[E.Candidate], ctx: E.RankContext | None = None
            ) -> tuple[list[dict], list[dict], float]:
    b = budget_from(req)
    extra, themes = req.get("extra_krw", 0), req.get("themes", [])
    radius = E.radius_km(b, extra)
    all_cards = sort_ranked(evaluate(candidates, b, req.get("seats_occupied", 1), themes,
                                     req.get("recent_visits", []), ctx, req.get("days", 1)), radius)
    pool = [c for c in all_cards if eligible(c, extra, themes)]
    return all_cards, pool, radius


def build_cards(req: dict, candidates: list[E.Candidate], store: SessionStore,
                ctx: E.RankContext | None = None) -> dict:
    """POST /v1/cards 본체. req는 contract/openapi.yaml의 CardsRequest. ctx = 순위 조건(없으면 기존 5항목만)."""
    req = {k: v for k, v in req.items() if k != "session_id"} | {"session_id": req.get("session_id")}
    sid = req["session_id"]
    sess = store.get(sid) if sid else None
    if sess and ctx is None:
        ctx = sess.get("ctx")
    all_cards, pool, radius = _ranked(req, candidates, ctx)
    if sess:
        sess["ctx"] = ctx
        # 재호출(차량 값 변화, 1초 디바운스): 같은 picked에 새 배지·1인 몫·이유. 세션 값도 최신으로 → 교체가 이 값을 쓴다
        sess["request"] = {k: v for k, v in req.items() if k != "session_id"}
        live = [c for c in pool if c["id"] not in sess["excluded"]]
        picked = next((c for c in all_cards if c["id"] == sess["picked_id"]), None)
        if picked is None:  # +3만 범위를 벗어남 → 다시 뽑기
            picked = live[0] if live else None
            sess["picked_id"] = picked["id"] if picked else None
        stack = build_stack(picked, live) if picked else []
        sess["stack_ids"] = [c["id"] for c in stack]
    else:
        picked = pool[0] if pool else None
        stack = build_stack(picked, pool) if picked else []
        sid = store.new(picked["id"], [c["id"] for c in stack],
                        {k: v for k, v in req.items() if k != "session_id"}, ctx) if picked else None

    return {
        "session_id": sid,
        "radius_km": E.round1(radius),
        "candidates_in_range": len(pool),
        "hint": pick_hint(len(pool), req.get("extra_krw", 0), req.get("themes", []), strip(picked), ctx),
        "picked": strip(picked),
        "stack": [strip(c) for c in stack],
    }


def swap(session_id: str, exclude_ids: list[str], candidates: list[E.Candidate], store: SessionStore) -> dict:
    """교체: 화면 스택 순서대로 다음 카드. 세션의 최신 값(재호출 반영)으로 배지를 다시 매긴다."""
    sess = store.get(session_id)
    if not sess:
        return {"error": "session_not_found"}
    if sess["swaps"] >= E.MAX_SWAPS:
        return {"error": "swap_limit", "hint": "어떤 쪽으로 다시 볼까요?"}
    req = sess["request"]
    all_cards, pool, _ = _ranked(req, candidates, sess.get("ctx"))
    by_id = {c["id"]: c for c in all_cards}
    excluded = list(dict.fromkeys(sess["excluded"] + list(exclude_ids) + [sess["picked_id"]]))
    nxt = next((by_id[i] for i in sess["stack_ids"] if i in by_id and i not in excluded), None)
    if nxt is None:
        nxt = next((c for c in pool if c["id"] not in excluded), None)
    if nxt is None:
        return {"error": "no_more_candidates", "hint": "어떤 쪽으로 다시 볼까요?"}
    sess["excluded"] = excluded
    sess["swaps"] += 1
    sess["picked_id"] = nxt["id"]
    sess["stack_ids"] = [i for i in sess["stack_ids"] if i != nxt["id"]]
    return {"session_id": session_id, "card": strip(nxt), "swaps_used": sess["swaps"],
            "swaps_left": E.MAX_SWAPS - sess["swaps"], "stack_left": len(sess["stack_ids"])}
