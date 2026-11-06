"""블라인드 검수(PRD v1.3 7-4): 같은 조건에서 규칙 상위 5곳과 LLM 5곳을 섞어 팀이 1~5점을 매긴다.

판정: '가고 싶다' 평균 차이(LLM − 규칙)가 0.5점 미만이면 규칙 확정. 이유 한 줄은 '납득된다' 점수로 같이 본다.

명령 (레포 루트에서)
  python scripts/blind_review/blind_review.py themes          # D1 반경 안 테마별 후보 수와 자동 선택 (외부 호출 없음)
  python scripts/blind_review/blind_review.py prompt          # LLM에 보낼 프롬프트를 out/에 쓰기만 (외부 호출 없음)
  uv run --no-project --python 3.12 --with anthropic python scripts/blind_review/blind_review.py run
  python scripts/blind_review/blind_review.py page            # out/items.json으로 page.html만 다시 만듦
  python scripts/blind_review/blind_review.py tally out/ratings.json [파일 더]

입력
- 조건: fixtures/cards/D1.json의 request에서 demo_seed만 뺀 것(= 실서비스 모드) + themes=[테마 하나]
- 후보: 서버와 같은 TourAPI SQLite(CHAGOK_DB, 기본 server/data/chagok.sqlite3)
- 키: .env의 KAKAO_REST_API_KEY(실경로), ANTHROPIC_API_KEY(LLM). 키는 출력·기록에 남기지 않는다

순위 규칙은 서버 코드(live.candidates_for → cards._ranked)를 그대로 부른다. 여기서 따로 만들지 않는다.
LLM에는 규칙과 같은 후보(추정 범위 안 전체)를 가나다순으로 준다. 원시 차량값은 보내지 않는다(반경은 budget 파생값).

출력 scripts/blind_review/out/ (gitignore). **점수를 다 매기기 전에는 key.json·run-*.json을 열지 않는다.**
- items.json·page.html  섞은 목록(출처 없음). page.html을 아티팩트로 게시해 팀이 점수를 매긴다
- key.json              코드 → 출처(규칙 순위·LLM 순위)
- run-<시각>.json        재현 기록: 조건·후보 수·프롬프트 해시·모델·요청 id·원문 응답·사용량·버린 후보와 이유
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import random
import re
import secrets
import sqlite3
import sys
from datetime import datetime, timedelta, timezone
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
OUT = HERE / "out"
sys.path.insert(0, str(ROOT / "server"))

from chagok import cards as C  # noqa: E402
from chagok import engine as E  # noqa: E402
from chagok import live  # noqa: E402
from chagok import routes as R  # noqa: E402
from chagok import sources as S  # noqa: E402

MODEL = "claude-opus-5-5"
EFFORT = "high"
PICK_N = 5            # PRD 7-4: 규칙 5곳 vs LLM 5곳
SPARE_N = 2           # LLM 예비: 실경로로 다시 재서 범위를 벗어나면 다음 것을 쓴다
MIN_POOL = 30         # 자동 선택할 테마의 최소 범위 안 후보 수(5곳을 고를 때 6배 이상 고를 거리가 있게)
THRESHOLD = Fraction(1, 2)   # PRD 7-4: 평균 차이 0.5점 미만이면 규칙 확정
SCORES = range(1, 6)
ORIGIN_TEXT = "경기 성남시 판교 부근"   # D1 출발 좌표(37.4127, 127.0947) — 가까운 후보 주소로 확인
KST = timezone(timedelta(hours=9))

# 테마 자동 선택: 성격이 다른 묶음마다 하나씩(후보가 가장 많은 테마)
THEME_GROUPS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("물가", ("sea", "lake", "river", "island")),
    ("녹지", ("mountain", "park")),
    ("도시", ("city", "drive")),
)

# TourAPI 신분류 소분류 이름(lclsSystmCode2, 11/6 조회). 코드 목록은 sources.THEME_LCLS
LCLS_KO = {
    "NA010100": "산·고개·봉우리", "NA010200": "숲", "NA010300": "폭포", "NA010400": "계곡",
    "NA020100": "강", "NA020200": "호수", "NA020300": "저수지", "NA020500": "섬",
    "NA020700": "항구·포구", "NA020800": "해안절경", "NA020900": "해변·해수욕장",
    "NA040600": "자연휴양림", "NA040700": "수목원·정원",
    "VE010200": "타워·전망대", "VE010300": "다리·대교",
    "VE030100": "시민공원", "VE030400": "근린공원", "VE030500": "주제공원",
    "VE040100": "골목길·문화거리",
}
SIDO_SHORT = {"서울특별시": "서울", "인천광역시": "인천", "경기도": "경기", "강원특별자치도": "강원", "강원도": "강원",
              "충청북도": "충북", "충청남도": "충남"}

# LLM 이유 한 줄 점검(버리지 않고 기록만): 프롬프트 3번 규칙 + 화면 문구 규칙(lint_copy와 같은 취지)
REASON_FLAGS = [
    (re.compile(r"\d[\d,]*\s*원|무료|0원|비용|요금|주차"), "금액·비용·주차 언급"),
    (re.compile(r"\d+\s*(분|km|킬로)"), "시간·거리 숫자"),
    (re.compile(r"글레오|예상\s*(vs|대비|과|와)\s*실제"), "금지 문구(AC-C-8)"),
    (re.compile("[\U0001F300-\U0001FAFF☀-➿]"), "이모지"),
]
REASON_MAX = 40

SCHEMA = {
    "type": "object",
    "properties": {
        "criteria": {"type": "string"},
        "picks": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"id": {"type": "string"}, "reason": {"type": "string"}},
                "required": ["id", "reason"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["criteria", "picks"],
    "additionalProperties": False,
}


# ------------------------------------------------------------ 조건·후보
def d1_request(theme: str | None = None) -> dict:
    """D1 요청에서 demo_seed만 뺀 실서비스 요청. 테마 하나를 필터로."""
    d1 = json.loads((ROOT / "fixtures/cards/D1.json").read_text(encoding="utf-8"))
    req = copy.deepcopy(d1["request"])
    req.pop("demo_seed", None)
    req["themes"] = [theme] if theme else []
    return req


def load_candidates(con: sqlite3.Connection) -> tuple[list[S.Row], dict[str, tuple[str, str]], str | None]:
    """(서버가 쓰는 후보 행, id → (주소, 소분류 코드), 갱신일)."""
    rows, refreshed = S.load_rows(con)
    meta = {r[0]: (r[1] or "", r[2] or "") for r in con.execute("SELECT id, addr, lcls3 FROM candidate")}
    return rows, meta, refreshed


def addr_short(addr: str, n: int) -> str:
    parts = addr.split()
    if not parts:
        return ""
    return " ".join([SIDO_SHORT.get(parts[0], parts[0]), *parts[1:n]])


def theme_counts(base_req: dict, est: list[E.Candidate]) -> dict[str, int]:
    """테마마다 D1 반경·추가 0원 안 후보 수(추정, 서버 응답의 candidates_in_range와 같은 셈)."""
    return {t: len(C._ranked(dict(base_req, themes=[t]), est)[1]) for t in S.THEME_LCLS}


def pick_themes(counts: dict[str, int], k: int = 3, min_pool: int = MIN_POOL) -> list[tuple[str, str]]:
    """묶음(물가·녹지·도시)마다 범위 안 후보가 가장 많은 테마 하나. min_pool 미만인 묶음은 건너뛰고,
    남은 자리는 전체에서 후보가 많은 순으로 채운다. 같은 수면 sources.THEME_LCLS 순서. → [(테마, 고른 이유)]"""
    order = list(S.THEME_LCLS)
    chosen: list[tuple[str, str]] = []
    for label, themes in THEME_GROUPS:
        best = max(themes, key=lambda t: (counts.get(t, 0), -order.index(t)))
        if counts.get(best, 0) >= min_pool:
            chosen.append((best, f"{label} 묶음에서 범위 안 후보가 가장 많음({counts[best]}곳)"))
    for t in sorted(order, key=lambda t: (-counts.get(t, 0), order.index(t))):
        if len(chosen) >= k:
            break
        if counts.get(t, 0) >= min_pool and t not in {c[0] for c in chosen}:
            chosen.append((t, f"남은 자리: 전체에서 후보가 많은 순({counts[t]}곳)"))
    return chosen[:k]


def one_way_km(req: dict) -> int:
    """추가 0원 범위의 편도 도로 거리. 엔진 판정(compute_badge)이 '왕복 도로 ≤ usable_km'이라 usable_km ÷ 2.
    radius_km(= 이 값 ÷ 1.3)는 지도에 그리는 직선 반경이라 도로 거리로 말하면 틀린다."""
    return E.round_half_up(C.budget_from(req).usable_km / 2)


def condition_text(req: dict) -> str:
    t = req["themes"][0]
    return (f"{ORIGIN_TEXT} 출발 · 당일 · {req.get('seats_occupied', 1)}명 · "
            f"추가 비용 0원 범위(편도 도로 약 {one_way_km(req)}km) · 테마 {E.THEME_KO[t]}")


def fmt_num(x: float) -> str:
    return str(int(x)) if float(x).is_integer() else str(x)


# ------------------------------------------------------------ 규칙 쪽
def rule_top(req: dict, rows: list[S.Row], *, fetch, cache: R.RouteCache, k: int = PICK_N
             ) -> tuple[list[dict], live.LiveStats]:
    """서버 실서비스 모드와 같은 순위(추정 → 상위 12곳 실경로 → 실경로 후보끼리 순위)의 상위 k곳."""
    cands, _, st = live.candidates_for(req, rows, fetch=fetch, cache=cache)
    _, pool, _ = C._ranked(req, cands)
    return pool[:k], st


# ------------------------------------------------------------ LLM 쪽
def load_prompt(path: Path = HERE / "prompt.md") -> tuple[str, str, str]:
    """(system, user 틀, 파일 해시 앞 12자)."""
    text = path.read_text(encoding="utf-8")
    sha = hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]
    rest = text.split("<!-- system -->", 1)[1]
    system, user = rest.split("<!-- user -->", 1)
    return system.strip(), user.strip(), sha


def candidate_table(pool_est: list[dict], meta: dict[str, tuple[str, str]]) -> str:
    """가나다순(규칙 순위가 새지 않게). 열: id | 이름 | 주소 | 분류 | 편도 km | 편도 분."""
    lines = []
    for c in sorted(pool_est, key=lambda c: (c["name"], c["id"])):
        addr, lcls3 = meta.get(c["id"], ("", ""))
        cells = [c["id"], c["name"], addr_short(addr, 3), LCLS_KO.get(lcls3, lcls3),
                 fmt_num(c["route_km"]), str(c["minutes"])]
        lines.append(" | ".join(x.replace("|", "/") for x in cells))
    return "\n".join(lines)


def render_user(template: str, req: dict, radius: float, pool_est: list[dict], meta) -> str:
    return template.format(
        origin=ORIGIN_TEXT, seats=req.get("seats_occupied", 1), one_way_km=one_way_km(req),
        radius_km=E.round_half_up(radius),
        theme_ko=E.THEME_KO[req["themes"][0]], n=len(pool_est), n_pick=PICK_N + SPARE_N,
        candidates=candidate_table(pool_est, meta))


def call_llm(system: str, user: str, api_key: str | None) -> dict:
    """한 번 호출. 구조화 출력(JSON 스키마)으로 받는다. 거절·잘림은 실패로 멈춘다(조용히 넘어가지 않음)."""
    import anthropic

    client = anthropic.Anthropic(api_key=api_key) if api_key else anthropic.Anthropic()
    resp = client.beta.messages.create(
        model=MODEL,
        max_tokens=16000,
        system=system,
        messages=[{"role": "user", "content": user}],
        output_config={"effort": EFFORT, "format": {"type": "json_schema", "schema": SCHEMA}},
        betas=["server-side-fallback-2026-07-01"],
        fallbacks="default",
    )
    record = {"request_id": resp._request_id, "model_requested": MODEL, "model_served": resp.model,
              "effort": EFFORT, "stop_reason": resp.stop_reason, "usage": resp.usage.to_dict()}
    if resp.stop_reason == "refusal":
        raise RuntimeError(f"LLM 거절: {resp.stop_details} (request_id {resp._request_id})")
    if resp.stop_reason == "max_tokens":
        raise RuntimeError(f"출력이 max_tokens에서 잘림 (request_id {resp._request_id})")
    text = next(b.text for b in resp.content if b.type == "text")
    record["raw"] = text
    record["parsed"] = json.loads(text)
    return record


def reason_flags(reason: str) -> list[str]:
    flags = [msg for rx, msg in REASON_FLAGS if rx.search(reason)]
    if len(reason) > REASON_MAX:
        flags.append(f"{REASON_MAX}자 넘음({len(reason)}자)")
    return flags


def validate_picks(picks: list[dict], pool_by_id: dict[str, dict]) -> tuple[list[dict], list[dict]]:
    """목록에 없는 id·중복·같은 장소의 다른 항목·빈 이유는 버린다(버린 이유를 기록). 순서 = LLM 순위."""
    kept: list[dict] = []
    dropped: list[dict] = []
    for rank, p in enumerate(picks, 1):
        pid = str(p.get("id", "")).strip()
        reason = " ".join(str(p.get("reason", "")).split())
        c = pool_by_id.get(pid)
        why = None
        if c is None:
            why = "후보 목록에 없는 id"
        elif any(k["id"] == pid for k in kept):
            why = "중복"
        elif any(live.same_place(c, pool_by_id[k["id"]]) for k in kept):
            why = "앞에서 고른 곳과 같은 장소"
        elif not reason:
            why = "이유 없음"
        if why:
            dropped.append({"llm_rank": rank, "id": pid, "why": why})
        else:
            kept.append({"llm_rank": rank, "id": pid, "reason": reason, "flags": reason_flags(reason)})
    return kept, dropped


def refine_picks(req: dict, kept: list[dict], est_by_id: dict[str, E.Candidate], *, fetch,
                 cache: R.RouteCache, k: int = PICK_N) -> tuple[list[dict], list[dict]]:
    """LLM이 고른 곳도 규칙과 같은 실경로·같은 카드 계산을 거친다. 범위·90분을 벗어나면 다음 예비로.
    → ([{llm_rank, id, reason, flags, card, route}], 버린 것)"""
    o = req["origin"]
    cands = [est_by_id[x["id"]] for x in kept]
    routed: set[str] = set()
    if fetch is not None and cands:
        cands, _ = R.refine(o["lat"], o["lng"], cands, fetch=fetch, cache=cache, max_workers=len(cands))
        routed = {c.id for c in cands if cache.get(R.grid_key(o["lat"], o["lng"], c.id)) is not None}
    b = C.budget_from(req)
    cards = {c["id"]: c for c in C.evaluate(cands, b, req.get("seats_occupied", 1), req["themes"],
                                            req.get("recent_visits", []))}
    out: list[dict] = []
    dropped: list[dict] = []
    for x in kept:
        card = cards.get(x["id"])
        if card is None or not C.eligible(card, req.get("extra_krw", 0), req["themes"]):
            dropped.append({"llm_rank": x["llm_rank"], "id": x["id"], "why": "실경로로 다시 재니 범위·90분 밖"})
            continue
        if len(out) < k:
            out.append(x | {"card": card, "route": "kakao" if x["id"] in routed else "estimate"})
    return out, dropped


# ------------------------------------------------------------ 섞기·페이지
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def _code(rng: random.Random, used: set[str]) -> str:
    while True:
        c = "".join(rng.choice(CODE_ALPHABET) for _ in range(4))
        if c not in used:
            used.add(c)
            return c


def place_view(card: dict, region: str) -> dict:
    return {"name": card["name"], "region": region,
            "time_text": f"{card['time_label']} · 편도 {card['minutes']}분 · {fmt_num(card['route_km'])}km",
            "cost_text": card["display"]["total_text"]}


def build_items(conditions: list[dict], meta: dict[str, tuple[str, str]], rng: random.Random
                ) -> tuple[dict, dict]:
    """conditions = [{theme, text, rule: [card], llm: [{llm_rank, reason, card}]}] → (페이지 데이터, 정답 키).
    페이지 데이터에는 출처·순위·장소 id를 넣지 않는다. 두 쪽이 같은 곳을 고르면 장소는 한 번만(점수는 양쪽에 셈),
    이유는 쪽마다 하나씩. 순서는 섞는다(페이지가 평가자마다 다시 섞는다)."""
    used: set[str] = set()
    places, reasons = [], []
    key = {"places": {}, "reasons": {}}
    for cond in conditions:
        by_id: dict[str, dict] = {}
        for rank, card in enumerate(cond["rule"], 1):
            by_id.setdefault(card["id"], {"card": card, "rule_rank": None, "llm_rank": None, "reasons": []})
            by_id[card["id"]]["rule_rank"] = rank
            by_id[card["id"]]["reasons"].append(("rule", card["reason"]))
        for rank, pick in enumerate(cond["llm"], 1):
            by_id.setdefault(pick["card"]["id"], {"card": pick["card"], "rule_rank": None, "llm_rank": None,
                                                  "reasons": []})
            by_id[pick["card"]["id"]]["llm_rank"] = rank
            by_id[pick["card"]["id"]]["reasons"].append(("llm", pick["reason"]))
        for pid, item in by_id.items():
            code = _code(rng, used)
            addr = meta.get(pid, ("", ""))[0]
            places.append({"code": code, "theme": cond["theme"], **place_view(item["card"], addr_short(addr, 2))})
            key["places"][code] = {"id": pid, "name": item["card"]["name"], "theme": cond["theme"],
                                   "rule_rank": item["rule_rank"], "llm_rank": item["llm_rank"]}
            for source, text in item["reasons"]:
                rcode = _code(rng, used)
                reasons.append({"code": rcode, "place": code, "text": text})
                key["reasons"][rcode] = {"source": source, "place": code}
    rng.shuffle(places)
    rng.shuffle(reasons)
    data = {
        "version": 1,
        "scale": {"place": "가고 싶다", "reason": "납득된다"},
        "conditions": [{"theme": c["theme"], "theme_ko": E.THEME_KO[c["theme"]], "text": c["text"]}
                       for c in conditions],
        "places": places,
        "reasons": reasons,
    }
    return data, key


def render_page(data: dict, template: Path = HERE / "page.html") -> str:
    blob = json.dumps(data, ensure_ascii=False).replace("</", "<\\/")
    html = template.read_text(encoding="utf-8")
    if "/*__DATA__*/null" not in html:
        raise ValueError("page.html에 /*__DATA__*/null 자리가 없다")
    return html.replace("/*__DATA__*/null", blob)


# ------------------------------------------------------------ 집계
def _norm_raters(blobs: list) -> list[dict]:
    """ArtifactData list 결과·페이지의 '점수 복사' JSON·그 목록을 모두 [{rater, place, reason}]로."""
    out: list[dict] = []

    def one(x: dict) -> dict | None:
        body = x.get("data") if isinstance(x.get("data"), dict) else x
        if not isinstance(body, dict) or not ("place" in body or "reason" in body):
            return None
        rater = str(body.get("rater") or x.get("id") or x.get("doc_id") or "").strip()
        return {"rater": rater or f"anon{len(out) + 1}", "place": body.get("place") or {},
                "reason": body.get("reason") or {}}

    def walk(x) -> None:
        if isinstance(x, list):
            for y in x:
                walk(y)
        elif isinstance(x, dict):
            r = one(x)
            if r is not None:
                out.append(r)
            else:
                for k in ("docs", "documents", "items", "results"):
                    if k in x:
                        walk(x[k])
    walk(blobs)
    return out


def _mean(xs: list[int]) -> Fraction | None:
    return Fraction(sum(xs), len(xs)) if xs else None


def _r2(x: Fraction | None) -> str:
    if x is None:
        return "—"
    v = math.floor(x * 100 + Fraction(1, 2)) / 100   # 표시용 반올림(내장 round 금지)
    return f"{v:.2f}"


def tally(key: dict, raters: list[dict]) -> dict:
    """출처별 평균. 두 쪽이 같이 고른 장소는 양쪽 평균에 다 들어간다. 판정은 분수로 정확히 비교한다."""
    seen: set[str] = set()
    uniq, dup = [], []
    for r in raters:
        (dup if r["rater"] in seen else uniq).append(r)
        seen.add(r["rater"])

    def collect(kind: str):
        allv = {"rule": [], "llm": []}
        theme_v: dict[str, dict[str, list[int]]] = {}
        per_rater: dict[str, dict[str, list[int]]] = {}
        for r in uniq:
            for code, s in r[kind].items():
                if not isinstance(s, int) or s not in SCORES:
                    continue
                if kind == "place":
                    k = key["places"].get(code)
                    if not k:
                        continue
                    sources = [src for src in ("rule", "llm") if k[f"{src}_rank"]]
                    theme = k["theme"]
                else:
                    k = key["reasons"].get(code)
                    if not k:
                        continue
                    sources = [k["source"]]
                    theme = key["places"][k["place"]]["theme"]
                for src in sources:
                    allv[src].append(s)
                    theme_v.setdefault(theme, {"rule": [], "llm": []})[src].append(s)
                    per_rater.setdefault(r["rater"], {"rule": [], "llm": []})[src].append(s)
        m = {src: _mean(v) for src, v in allv.items()}
        diff = m["llm"] - m["rule"] if None not in m.values() else None
        return {"n": {src: len(v) for src, v in allv.items()}, "mean": m, "diff": diff,
                "by_theme": {t: {src: _mean(v) for src, v in tv.items()} for t, tv in theme_v.items()},
                "by_rater": {rid: {src: _mean(v) for src, v in rv.items()} for rid, rv in per_rater.items()}}

    place = collect("place")
    reason = collect("reason")
    verdict = None if place["diff"] is None else ("규칙 확정" if place["diff"] < THRESHOLD else "규칙 재검토")
    total_items = len(key["places"])
    coverage = {r["rater"]: sum(1 for c in r["place"] if c in key["places"]) for r in uniq}
    return {"place": place, "reason": reason, "verdict": verdict, "raters": len(uniq),
            "duplicates": [r["rater"] for r in dup], "coverage": coverage, "total_items": total_items}


def result_markdown(res: dict, key: dict) -> str:
    p, r = res["place"], res["reason"]
    lines = [
        "# 블라인드 검수 결과",
        "",
        f"- 평가자 {res['raters']}명 · 장소 {res['total_items']}곳 · 판정 기준: '가고 싶다' 평균 차이(LLM − 규칙) "
        f"{_r2(THRESHOLD)} 미만이면 규칙 확정(PRD 7-4)",
        f"- **판정: {res['verdict'] or '점수 없음'}** (차이 {_r2(p['diff'])})",
        "",
        "| 점수 | 규칙 평균 | LLM 평균 | 차이(LLM − 규칙) | 점수 수(규칙/LLM) |",
        "|---|---|---|---|---|",
        f"| 가고 싶다 | {_r2(p['mean']['rule'])} | {_r2(p['mean']['llm'])} | {_r2(p['diff'])} | "
        f"{p['n']['rule']}/{p['n']['llm']} |",
        f"| 납득된다(이유 한 줄) | {_r2(r['mean']['rule'])} | {_r2(r['mean']['llm'])} | {_r2(r['diff'])} | "
        f"{r['n']['rule']}/{r['n']['llm']} |",
        "",
        "## 테마별 '가고 싶다'",
        "",
        "| 테마 | 규칙 | LLM | 차이 |",
        "|---|---|---|---|",
    ]
    for t, v in p["by_theme"].items():
        d = v["llm"] - v["rule"] if v["llm"] is not None and v["rule"] is not None else None
        lines.append(f"| {E.THEME_KO.get(t, t)} | {_r2(v['rule'])} | {_r2(v['llm'])} | {_r2(d)} |")
    lines += ["", "## 평가자별 '가고 싶다' 차이(LLM − 규칙)", "", "| 평가자 | 매긴 장소 | 차이 |", "|---|---|---|"]
    for i, (rid, v) in enumerate(p["by_rater"].items(), 1):
        d = v["llm"] - v["rule"] if v.get("llm") is not None and v.get("rule") is not None else None
        lines.append(f"| {i} | {res['coverage'].get(rid, 0)}/{res['total_items']} | {_r2(d)} |")
    both = sum(1 for k in key["places"].values() if k["rule_rank"] and k["llm_rank"])
    lines += ["", f"- 두 쪽이 같이 고른 장소 {both}곳(양쪽 평균에 다 들어감)."]
    if res["raters"] < 5:
        lines.append(f"- 평가자가 PRD의 5명보다 적다({res['raters']}명).")
    if res["duplicates"]:
        lines.append(f"- 같은 평가자 기록이 두 번 들어와 뒤의 것을 버렸다: {len(res['duplicates'])}건.")
    return "\n".join(lines) + "\n"


# ------------------------------------------------------------ 명령
def _setup(args):
    con = sqlite3.connect(S.db_path())
    rows, meta, refreshed = load_candidates(con)
    con.close()
    if not rows:
        raise SystemExit(f"후보가 없다: {S.db_path()} (서버 캐시를 복사하거나 python -m chagok.sources refresh)")
    base = d1_request()
    est = live.estimates(base, rows)
    counts = theme_counts(base, est)
    if args.themes:
        themes = [(t, "직접 지정") for t in args.themes.split(",")]
        bad = [t for t, _ in themes if t not in S.THEME_LCLS]
        if bad:
            raise SystemExit(f"모르는 테마: {bad} (가능: {', '.join(S.THEME_LCLS)})")
    else:
        themes = pick_themes(counts)
    return rows, meta, refreshed, est, counts, themes


def cmd_themes(args) -> int:
    _, _, refreshed, _, counts, themes = _setup(args)
    print(f"후보 갱신일 {refreshed} · D1 반경 안(추가 0원) 테마별 후보 수")
    for t, n in sorted(counts.items(), key=lambda x: -x[1]):
        print(f"  {E.THEME_KO[t]:<5} {n:>4}")
    print("선택:")
    for t, why in themes:
        print(f"  {E.THEME_KO[t]} — {why}")
    return 0


def cmd_prompt(args) -> int:
    _, meta, _, est, _, themes = _setup(args)
    system, user_t, sha = load_prompt()
    OUT.mkdir(exist_ok=True)
    for t, _ in themes:
        req = d1_request(t)
        _, pool_est, radius = C._ranked(req, est)
        user = render_user(user_t, req, radius, pool_est, meta)
        p = OUT / f"prompt-{t}.md"
        p.write_text(f"<!-- prompt_sha {sha} -->\n# system\n{system}\n\n# user\n{user}\n", encoding="utf-8")
        print(f"{E.THEME_KO[t]}: 후보 {len(pool_est)}곳 · {len(system) + len(user):,}자 → {p.relative_to(ROOT)}")
    return 0


def cmd_run(args) -> int:
    rows, meta, refreshed, est, counts, themes = _setup(args)
    env = S.load_env()
    kakao_key = env.get("KAKAO_REST_API_KEY")
    if not kakao_key and not args.no_kakao:
        raise SystemExit("KAKAO_REST_API_KEY가 .env에 없다. 서버와 같은 순위를 내려면 필요하다(정말 추정만 쓰려면 --no-kakao)")
    fetch = None if args.no_kakao else R.kakao_fetcher(kakao_key, timeout=3.0)
    cache = R.RouteCache()
    system, user_t, sha = load_prompt()
    started = datetime.now(KST)
    record = {"created_at": started.isoformat(timespec="seconds"), "prompt_sha": sha, "model": MODEL,
              "effort": EFFORT, "candidates_refreshed": refreshed, "theme_counts": counts,
              "themes": [{"theme": t, "why": why} for t, why in themes], "conditions": []}
    conditions = []
    for t, _ in themes:
        req = d1_request(t)
        _, pool_est, radius = C._ranked(req, est)
        rule, st = rule_top(req, rows, fetch=fetch, cache=cache)
        user = render_user(user_t, req, radius, pool_est, meta)
        print(f"{E.THEME_KO[t]}: 후보 {len(pool_est)}곳 · 규칙 {len(rule)}곳(순위 기준 {st.ranked_on}) · LLM 호출 중…",
              flush=True)
        llm = call_llm(system, user, env.get("ANTHROPIC_API_KEY"))
        pool_by_id = {c["id"]: c for c in pool_est}
        kept, dropped = validate_picks(llm["parsed"].get("picks", []), pool_by_id)
        est_by_id = {c.id: c for c in est}
        picks, dropped2 = refine_picks(req, kept, est_by_id, fetch=fetch, cache=cache)
        if len(picks) < PICK_N:
            print(f"  주의: LLM 유효 {len(picks)}곳(< {PICK_N}). run 기록의 dropped 참고")
        text = condition_text(req)
        conditions.append({"theme": t, "text": text, "rule": rule, "llm": picks})
        record["conditions"].append({
            "theme": t, "text": text, "radius_km": radius, "pool": len(pool_est),
            "rule": {"ranked_on": st.ranked_on, "kakao_calls": st.calls, "kakao_failures": st.failures,
                     "picks": [{"rank": i, "id": c["id"], "name": c["name"], "minutes": c["minutes"],
                                "route_km": c["route_km"], "reason": c["reason"]} for i, c in enumerate(rule, 1)]},
            "llm": {**{k: v for k, v in llm.items() if k != "parsed"},
                    "criteria": llm["parsed"].get("criteria"),
                    "picks": [{"rank": i, "llm_rank": p["llm_rank"], "id": p["id"], "name": p["card"]["name"],
                               "minutes": p["card"]["minutes"], "route_km": p["card"]["route_km"],
                               "route": p["route"], "reason": p["reason"], "flags": p["flags"]}
                              for i, p in enumerate(picks, 1)],
                    "dropped": dropped + dropped2},
        })
    data, key = build_items(conditions, meta, secrets.SystemRandom())
    data["created"] = started.date().isoformat()
    OUT.mkdir(exist_ok=True)
    stamp = started.strftime("%Y%m%d-%H%M")
    (OUT / "items.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    (OUT / "key.json").write_text(json.dumps(key, ensure_ascii=False, indent=1), encoding="utf-8")
    (OUT / f"run-{stamp}.json").write_text(json.dumps(record, ensure_ascii=False, indent=1), encoding="utf-8")
    (OUT / "page.html").write_text(render_page(data), encoding="utf-8")
    flags = sum(len(p["flags"]) for c in conditions for p in c["llm"])
    usage = [c["llm"]["usage"] for c in record["conditions"]]
    print(f"장소 {len(data['places'])}곳 · 이유 {len(data['reasons'])}개 · 이유 점검 걸림 {flags}건 · "
          f"입력 토큰 {sum(u.get('input_tokens', 0) for u in usage):,} · 출력 토큰 {sum(u.get('output_tokens', 0) for u in usage):,}")
    print(f"→ {(OUT / 'page.html').relative_to(ROOT)} (게시용) · key.json·run-{stamp}.json은 검수 끝날 때까지 열지 않기")
    return 0


def cmd_page(args) -> int:
    data = json.loads((OUT / "items.json").read_text(encoding="utf-8"))
    (OUT / "page.html").write_text(render_page(data), encoding="utf-8")
    print(f"→ {(OUT / 'page.html').relative_to(ROOT)}")
    return 0


def cmd_tally(args) -> int:
    key = json.loads((OUT / "key.json").read_text(encoding="utf-8"))
    blobs = [json.loads(Path(f).read_text(encoding="utf-8")) for f in args.files]
    res = tally(key, _norm_raters(blobs))
    md = result_markdown(res, key)
    (OUT / "result.md").write_text(md, encoding="utf-8")
    print(md)
    return 0


def main(argv: list[str] | None = None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("themes", "prompt", "run"):
        sp = sub.add_parser(name)
        sp.add_argument("--themes", help="자동 선택 대신 쉼표로 지정(예: sea,park,city)")
        if name == "run":
            sp.add_argument("--no-kakao", action="store_true", help="실경로 없이 추정값만(서버와 순위가 달라질 수 있음)")
    sub.add_parser("page")
    sp = sub.add_parser("tally")
    sp.add_argument("files", nargs="+")
    args = ap.parse_args(argv)
    return {"themes": cmd_themes, "prompt": cmd_prompt, "run": cmd_run, "page": cmd_page,
            "tally": cmd_tally}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
