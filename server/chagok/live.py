"""실서비스 모드(요청에 demo_seed 없음): TourAPI 캐시 후보 + 상위 후보만 카카오 실경로(PRD v1.3 7-1·7-4).

순위·스택·교체·세션 규칙은 cards.py를 그대로 쓴다. 그 규칙은 Kotlin DemoServer와 두 벌로 맞춰 둔 것이라
여기서 따로 만들지 않는다. 여기서 하는 일은 cards.py에 넣을 후보 목록을 만드는 것뿐이다.

흐름: SQLite 후보(메모리에 올림) → 출발지 기준 직선 추정(sources.estimate) → cards 순위로 '실경로 후보' N곳을 고름
      → N곳을 카카오로 한 번에(병렬) 바꿈 → **실경로를 받은 후보끼리만** cards 순위·스택·교체.
왜 실경로끼리만: 점수가 대부분 같아 동점 규칙("반경의 절반쯤", cards.sort_ranked)이 순위를 가른다. 추정값(직선 × 1.3)은
그 목표 거리 근처에 몰려 있고 실경로로 바꾸면 흩어져 밀려나므로, 섞어 두면 못 바꾼 추정값이 늘 1위로 올라온다.
카카오가 전부 실패하거나 키가 없으면 추정값 전체로 순위를 매긴다(카드는 계속 나옴).
"""

from __future__ import annotations

import sqlite3
import threading
import time
from dataclasses import dataclass
from pathlib import Path

from . import cards as C
from . import engine as E
from . import routes as R
from . import sources as S

REFINE_WINDOW = 12   # [가정] 실경로로 바꿀 후보 수(한 번에 병렬). 스택은 상위 9장 안에서 고른다(cards.build_stack)
FAR_IN_WINDOW = 2    # 그중 '조금 먼 곳'(75~90분) 몫 — 스택에 먼 곳 1장을 넣을 수 있게
# 앱 서버 타임아웃 3초(기능명세서 '서버 실패'). 카카오 1회 약 0.7초(실측, 12곳 병렬 1.2~2.0초) → 호출 타임아웃 1.2초.
ROUTE_TIMEOUT_S = 1.2


class CandidateIndex:
    """SQLite 후보를 메모리에 올려 둔다. 크론 갱신(meta.tourapi_refreshed가 바뀜)은 check_s마다 확인해 다시 읽는다.
    DB 파일이 없으면 만들지 않고 빈 목록(→ /v1/cards 503, 앱은 데모 fallback)."""

    def __init__(self, path: Path | None = None, check_s: float = 60.0, clock=time.monotonic) -> None:
        self._path = path
        self._check_s = check_s
        self._clock = clock
        self._rows: list[S.Row] = []
        self._meta: dict[str, S.Meta] = {}
        self._cur: dict[str, S.Curation] = {}
        self._refreshed: str | None = None
        self._curated: str | None = None
        self._checked: float | None = None
        self._lock = threading.Lock()

    def _db(self) -> Path:
        return Path(self._path) if self._path else S.db_path()

    def rows(self) -> tuple[list[S.Row], str | None]:
        rows, _, _, refreshed = self.snapshot()
        return rows, refreshed

    def snapshot(self) -> tuple[list[S.Row], dict[str, S.Meta], dict[str, S.Curation], str | None]:
        """(후보 행, 소분류·시군구·행사 날짜, A2 큐레이션, 갱신일). 큐레이션은 meta.curation_updated가 바뀌면 다시 읽는다."""
        with self._lock:
            now = self._clock()
            if self._checked is None or now - self._checked >= self._check_s:
                self._checked = now
                p = self._db()
                if p.exists():
                    con = sqlite3.connect(p)
                    try:
                        when = con.execute("SELECT value FROM meta WHERE key='tourapi_refreshed'").fetchone()
                        when = when[0] if when else None
                        if when != self._refreshed or not self._rows:
                            self._rows, self._refreshed = S.load_rows(con)
                            self._meta = S.load_meta(con)
                        cur = con.execute("SELECT value FROM meta WHERE key='curation_updated'").fetchone()
                        cur = cur[0] if cur else None
                        if cur != self._curated:
                            self._cur, self._curated = S.load_curation(con), cur
                    except sqlite3.Error:
                        pass  # 갱신 중이거나 스키마 전 → 지난 값 유지
                    finally:
                        con.close()
            return self._rows, self._meta, self._cur, self._refreshed


class LivePools:
    """세션별로 실경로를 받은 후보만 메모리에 둔다(세션보다 조금 길게, 디스크·로그에 안 씀).
    빈 딕셔너리도 '실서비스 세션'이라는 표시다(카카오 전부 실패 → 교체도 추정값으로)."""

    def __init__(self, ttl_s: float = C.SESSION_TTL_S + 300, clock=time.monotonic) -> None:
        self._s: dict[str, tuple[dict[str, E.Candidate], float]] = {}
        self._ttl = ttl_s
        self._clock = clock
        self._lock = threading.Lock()

    def put(self, sid: str, refined: dict[str, E.Candidate]) -> None:
        with self._lock:
            now = self._clock()
            for k in [k for k, (_, t) in self._s.items() if now - t > self._ttl]:
                del self._s[k]
            self._s[sid] = (refined, now)

    def get(self, sid: str) -> dict[str, E.Candidate] | None:
        with self._lock:
            hit = self._s.get(sid)
            if not hit or self._clock() - hit[1] > self._ttl:
                self._s.pop(sid, None)
                return None
            self._s[sid] = (hit[0], self._clock())
            return hit[0]


@dataclass
class LiveStats:
    pool: int = 0       # 직선 MAX_STRAIGHT_KM 안 후보 수
    in_range: int = 0   # 추정 기준 고른 프리셋·테마 안 후보 수(응답 candidates_in_range·hint)
    calls: int = 0
    hits: int = 0
    failures: int = 0
    ranked_on: str = "estimate"   # route = 실경로 후보끼리 순위 / estimate = 추정값 전체(키 없음·전부 실패)


def same_place(a: dict, b: dict) -> bool:
    """같은 장소의 다른 항목(예: '오동근린공원'·'오동근린공원 유아숲체험장'): 이름이 앞부분에서 겹치거나 PIN_SAME_KM 안."""
    na, nb = a["name"].replace(" ", ""), b["name"].replace(" ", "")
    return (na.startswith(nb) or nb.startswith(na)
            or S.haversine_km(a["lat"], a["lng"], b["lat"], b["lng"]) <= E.PIN_SAME_KM)


def window(pool: list[dict], n: int = REFINE_WINDOW, far: int = FAR_IN_WINDOW) -> list[str]:
    """실경로로 바꿀 후보 id. 추정 순위 상위 + '조금 먼 곳' + 아직 없는 테마 1곳씩 → 남으면 순위대로.
    같은 장소의 다른 항목은 하나만(한 장·스택에 같은 곳이 두 번 나오지 않게)."""
    out: list[dict] = []

    def take(c: dict) -> bool:
        if len(out) >= n or any(c["id"] == x["id"] or same_place(c, x) for x in out):
            return False
        out.append(c)
        return True

    head = max(n - far - 2, 1)
    for c in pool:
        if len(out) >= head:
            break
        take(c)
    k = 0
    for c in pool:
        if k >= far:
            break
        if c["time_label"] == "조금 먼 곳" and take(c):
            k += 1
    seen = {x["theme"] for x in out}
    for c in pool:
        if c["theme"] not in seen and take(c):
            seen.add(c["theme"])
    for c in pool:
        if len(out) >= n:
            break
        take(c)
    return [x["id"] for x in out]


def estimates(req: dict, rows: list[S.Row], meta: dict[str, S.Meta] | None = None,
              cur: dict[str, S.Curation] | None = None) -> list[E.Candidate]:
    o = req["origin"]
    return S.estimate(o["lat"], o["lng"], rows, meta, cur)


def candidates_for(req: dict, rows: list[S.Row], *, fetch, cache: R.RouteCache, keep_ids: list[str] = (),
                   refined: dict[str, E.Candidate] | None = None, ctx: E.RankContext | None = None,
                   meta: dict[str, S.Meta] | None = None, cur: dict[str, S.Curation] | None = None
                   ) -> tuple[list[E.Candidate], dict[str, E.Candidate], LiveStats]:
    """(순위에 넣을 후보, 이 세션에서 실경로를 받은 후보, 통계).
    keep_ids = 세션의 지금 카드·스택(재호출 때 같은 카드가 순위에 남게). refined = 세션에서 이미 받은 실경로.
    ctx = 순위 조건(A1·B, 날씨, 나들이 날). 실경로로 바꿀 후보(window)도 이 순위로 고른다."""
    refined = dict(refined or {})
    est = estimates(req, rows, meta, cur)
    _, pool, _ = C._ranked(req, est, ctx)
    st = LiveStats(pool=len(est), in_range=len(pool))
    if fetch is None:
        return est, refined, st
    by_id = {c.id: c for c in est}
    ids = [i for i in dict.fromkeys(list(keep_ids) + window(pool)) if i in by_id and i not in refined]
    if ids:
        o = req["origin"]
        new, rs = R.refine(o["lat"], o["lng"], [by_id[i] for i in ids], fetch=fetch, cache=cache,
                           max_workers=len(ids))
        st.calls, st.hits, st.failures = rs.calls, rs.hits, rs.failures
        # 실패한 후보(추정값 그대로)는 넣지 않는다 — 캐시에 있는 것 = 실경로를 받은 것
        refined.update({c.id: c for c in new if cache.get(R.grid_key(o["lat"], o["lng"], c.id)) is not None})
    ranked = list(refined.values())
    if not ranked or not C._ranked(req, ranked, ctx)[1]:   # 실경로 후보가 없거나 전부 범위 밖 → 추정값 전체로
        return est, refined, st
    st.ranked_on = "route"
    return ranked, refined, st


def swap_candidates(req: dict, rows: list[S.Row], refined: dict[str, E.Candidate],
                    meta: dict[str, S.Meta] | None = None, cur: dict[str, S.Curation] | None = None
                    ) -> list[E.Candidate]:
    """교체 때 순위에 넣을 후보: 카드를 만들 때와 같은 집합."""
    return list(refined.values()) if refined else estimates(req, rows, meta, cur)
