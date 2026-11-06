"""블라인드 검수(PRD v1.6 7-4) — A = 규칙(A1), B = A1 + A2 사전 큐레이션. 같은 조건에서 상위 5곳씩 섞어 팀이 1~5점.

판정(제안, DECISIONS '블라인드 검수 방식'):
- '가고 싶다' 평균 차이(B − A)가 0.5 이상이면 A2 도입(CHAGOK_RANKER=b), 미만이면 A1만(PRD 7-4 '0.5 미만이면 규칙 확정')
- '납득된다'(이유 한 줄) 차이가 0.5 이상이면 AI 한 줄 소개(F14) 채택

A와 B는 고르는 틀(거리·시간·다양성·동점·날씨·목적지성)이 같고 A2 평판·소개만 다르다. 둘 다 서버 실서비스 순서 그대로
(live.candidates_for → cards._ranked, 카카오 실경로)라 어느 쪽이 이기든 CHAGOK_RANKER 하나로 바로 배포된다.

명령 (레포 루트에서)
  python scripts/blind_review/blind_review.py conditions        # 조건별 범위 안 후보 수(외부 호출 없음)
  python scripts/blind_review/blind_review.py run [--date 2026-11-08]   # 카카오 실경로로 A·B 상위 5곳 → out/
  python scripts/blind_review/blind_review.py page             # out/items.json으로 page.html만 다시
  python scripts/blind_review/blind_review.py tally out/ratings.json [더]

준비: 후보 캐시(sources refresh), 날씨는 쓰지 않음(조건마다 고정), A2 큐레이션(python -m chagok.curate run --scope d1).
출력 scripts/blind_review/out/ (gitignore). **점수를 다 매기기 전에는 key.json·run-*.json을 열지 않는다.**
"""

from __future__ import annotations

import argparse
import copy
import json
import math
import random
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

PICK_N = 5
THRESHOLD = Fraction(1, 2)
SCORES = range(1, 6)
SIDES = ("a", "b")
SIDE_VARIANT = {"a": "a1", "b": "b"}
ORIGIN_TEXT = "경기 성남시 판교 부근"   # D1 출발 좌표(37.4127, 127.0947)
KST = timezone(timedelta(hours=9))

# 조건: D1 + 테마 4개(맑음 고정) + 테마 없음·비 예보 가정(모든 후보 비 1)
CONDITIONS = [
    {"key": "sea", "themes": ["sea"], "rain": False},
    {"key": "park", "themes": ["park"], "rain": False},
    {"key": "city", "themes": ["city"], "rain": False},
    {"key": "festival", "themes": ["festival"], "rain": False},
    {"key": "rain", "themes": [], "rain": True},
]
SIDO_SHORT = {"서울특별시": "서울", "인천광역시": "인천", "경기도": "경기", "강원특별자치도": "강원", "강원도": "강원",
              "충청북도": "충북", "충청남도": "충남"}


# ------------------------------------------------------------ 조건
def d1_request(themes: list[str]) -> dict:
    d1 = json.loads((ROOT / "fixtures/cards/D1.json").read_text(encoding="utf-8"))
    req = copy.deepcopy(d1["request"])
    req.pop("demo_seed", None)
    req["themes"] = list(themes)
    return req


def one_way_km(req: dict) -> int:
    """추가 0원 범위의 편도 도로 거리(엔진 판정 = 왕복 도로 ≤ usable_km). radius_km는 지도에 그리는 직선 반경."""
    return E.round_half_up(C.budget_from(req).usable_km / 2)


def condition_text(cond: dict, req: dict, date: str) -> str:
    theme = f"테마 {E.THEME_KO[cond['themes'][0]]}" if cond["themes"] else "테마 없음(오늘의 한 곳)"
    weather = "비 예보(가정)" if cond["rain"] else "맑음(가정)"
    return (f"{ORIGIN_TEXT} 출발 · {date} 당일 · {req.get('seats_occupied', 1)}명 · "
            f"추가 주유·충전 없이(편도 도로 약 {one_way_km(req)}km) · {theme} · {weather}")


def ctx_for(cond: dict, side: str, date: str, ids: list[str]) -> E.RankContext:
    rain = {i: 1.0 for i in ids} if cond["rain"] else {}
    return E.RankContext(cfg=C.RANKING, rain=rain, trip_date=date, variant=SIDE_VARIANT[side])


def default_date(now: datetime | None = None) -> str:
    """다음 토요일(오늘이 토요일이면 오늘) — 축제 후보가 그날 열리는 것만 남는다."""
    d = (now or datetime.now(KST)).date()
    return (d + timedelta(days=(5 - d.weekday()) % 7)).isoformat()


def top_for(cond: dict, side: str, date: str, rows, meta, cur, *, fetch, cache) -> tuple[list[dict], live.LiveStats]:
    """서버 실서비스 모드와 같은 순서(추정 → 상위 12곳 실경로 → 실경로 후보끼리 순위)의 상위 PICK_N."""
    req = d1_request(cond["themes"])
    ids = [r[0] for r in rows]
    ctx = ctx_for(cond, side, date, ids)
    cands, _, st = live.candidates_for(req, rows, fetch=fetch, cache=cache, ctx=ctx, meta=meta, cur=cur)
    return C._ranked(req, cands, ctx)[1][:PICK_N], st


# ------------------------------------------------------------ 섞기·페이지
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


def _code(rng: random.Random, used: set[str]) -> str:
    while True:
        c = "".join(rng.choice(CODE_ALPHABET) for _ in range(4))
        if c not in used:
            used.add(c)
            return c


def addr_short(addr: str, n: int) -> str:
    parts = addr.split()
    return " ".join([SIDO_SHORT.get(parts[0], parts[0]), *parts[1:n]]) if parts else ""


def fmt_num(x: float) -> str:
    return str(int(x)) if float(x).is_integer() else str(x)


def place_view(card: dict, region: str) -> dict:
    """두 쪽에 같은 정보만: 이름·지역·소분류·시간·비용(·행사 기간). 이유 한 줄은 2단계에서."""
    sub = card.get("subcategory")
    period = (f"{card['event_start'][5:].replace('-', '/')}~{card['event_end'][5:].replace('-', '/')}"
              if card.get("event_start") else None)
    return {"name": card["name"], "region": " · ".join(x for x in (region, sub, period) if x),
            "time_text": f"{card['time_label']} · 편도 {card['minutes']}분 · {fmt_num(card['route_km'])}km",
            "cost_text": card["display"]["total_text"]}


def build_items(conditions: list[dict], addrs: dict[str, str], rng: random.Random) -> tuple[dict, dict]:
    """conditions = [{key, text, a: [card], b: [card]}] → (페이지 데이터, 정답 키).
    페이지에는 출처·순위·장소 id를 넣지 않는다. 같은 곳을 두 쪽이 고르면 장소는 한 번(점수는 양쪽), 이유는 서로 다르면 쪽마다."""
    used: set[str] = set()
    places, reasons = [], []
    key = {"places": {}, "reasons": {}}
    for cond in conditions:
        by_id: dict[str, dict] = {}
        for side in SIDES:
            for rank, card in enumerate(cond[side], 1):
                item = by_id.setdefault(card["id"], {"card": card, "a_rank": None, "b_rank": None, "reasons": []})
                item[f"{side}_rank"] = rank
                item["reasons"].append((side, card["reason"]))
        for pid, item in by_id.items():
            code = _code(rng, used)
            places.append({"code": code, "theme": cond["key"],
                           **place_view(item["card"], addr_short(addrs.get(pid, ""), 2))})
            key["places"][code] = {"id": pid, "name": item["card"]["name"], "theme": cond["key"],
                                   "a_rank": item["a_rank"], "b_rank": item["b_rank"]}
            texts: dict[str, list[str]] = {}
            for side, text in item["reasons"]:
                texts.setdefault(text, []).append(side)
            for text, sides in texts.items():   # 같은 문장이면 한 번만 묻고 양쪽에 센다
                rcode = _code(rng, used)
                reasons.append({"code": rcode, "place": code, "text": text})
                key["reasons"][rcode] = {"sides": sides, "place": code}
    rng.shuffle(places)
    rng.shuffle(reasons)
    data = {"version": 2, "scale": {"place": "가고 싶다", "reason": "납득된다"},
            "conditions": [{"theme": c["key"], "theme_ko": c["label"], "text": c["text"]} for c in conditions],
            "places": places, "reasons": reasons}
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


def _diff(m: dict) -> Fraction | None:
    return m["b"] - m["a"] if m.get("a") is not None and m.get("b") is not None else None


def tally(key: dict, raters: list[dict]) -> dict:
    """쪽별 평균. 두 쪽이 같이 고른 장소·같은 이유 문장은 양쪽 평균에 다 들어간다. 판정은 분수로 정확히 비교."""
    seen: set[str] = set()
    uniq, dup = [], []
    for r in raters:
        (dup if r["rater"] in seen else uniq).append(r)
        seen.add(r["rater"])

    def collect(kind: str):
        allv = {s: [] for s in SIDES}
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
                    sides = [x for x in SIDES if k[f"{x}_rank"]]
                    theme = k["theme"]
                else:
                    k = key["reasons"].get(code)
                    if not k:
                        continue
                    sides = k["sides"]
                    theme = key["places"][k["place"]]["theme"]
                for side in sides:
                    allv[side].append(s)
                    theme_v.setdefault(theme, {x: [] for x in SIDES})[side].append(s)
                    per_rater.setdefault(r["rater"], {x: [] for x in SIDES})[side].append(s)
        m = {x: _mean(v) for x, v in allv.items()}
        return {"n": {x: len(v) for x, v in allv.items()}, "mean": m, "diff": _diff(m),
                "by_theme": {t: {x: _mean(v) for x, v in tv.items()} for t, tv in theme_v.items()},
                "by_rater": {rid: {x: _mean(v) for x, v in rv.items()} for rid, rv in per_rater.items()}}

    place, reason = collect("place"), collect("reason")
    verdict = None if place["diff"] is None else ("A2 도입(B)" if place["diff"] >= THRESHOLD else "A1만(규칙 확정)")
    intro = None if reason["diff"] is None else ("AI 한 줄 소개 채택" if reason["diff"] >= THRESHOLD else "규칙 이유 유지")
    coverage = {r["rater"]: sum(1 for c in r["place"] if c in key["places"]) for r in uniq}
    return {"place": place, "reason": reason, "verdict": verdict, "intro_verdict": intro, "raters": len(uniq),
            "duplicates": [r["rater"] for r in dup], "coverage": coverage, "total_items": len(key["places"])}


def result_markdown(res: dict, key: dict, labels: dict[str, str] | None = None) -> str:
    p, r = res["place"], res["reason"]
    labels = labels or {}
    lines = [
        "# 블라인드 검수 결과 (A = 규칙 A1, B = A1 + A2 사전 큐레이션)",
        "",
        f"- 평가자 {res['raters']}명 · 장소 {res['total_items']}곳",
        f"- **장소 판정: {res['verdict'] or '점수 없음'}** — '가고 싶다' 차이(B − A) {_r2(p['diff'])}, 기준 {_r2(THRESHOLD)} 이상이면 B",
        f"- **이유 판정: {res['intro_verdict'] or '점수 없음'}** — '납득된다' 차이(B − A) {_r2(r['diff'])}",
        "",
        "| 점수 | A 평균 | B 평균 | 차이(B − A) | 점수 수(A/B) |",
        "|---|---|---|---|---|",
        f"| 가고 싶다 | {_r2(p['mean']['a'])} | {_r2(p['mean']['b'])} | {_r2(p['diff'])} | {p['n']['a']}/{p['n']['b']} |",
        f"| 납득된다 | {_r2(r['mean']['a'])} | {_r2(r['mean']['b'])} | {_r2(r['diff'])} | {r['n']['a']}/{r['n']['b']} |",
        "",
        "## 조건별 '가고 싶다'",
        "",
        "| 조건 | A | B | 차이 |",
        "|---|---|---|---|",
    ]
    for t, v in p["by_theme"].items():
        lines.append(f"| {labels.get(t, t)} | {_r2(v['a'])} | {_r2(v['b'])} | {_r2(_diff(v))} |")
    lines += ["", "## 평가자별 '가고 싶다' 차이(B − A)", "", "| 평가자 | 매긴 장소 | 차이 |", "|---|---|---|"]
    for i, (rid, v) in enumerate(p["by_rater"].items(), 1):
        lines.append(f"| {i} | {res['coverage'].get(rid, 0)}/{res['total_items']} | {_r2(_diff(v))} |")
    both = sum(1 for k in key["places"].values() if k["a_rank"] and k["b_rank"])
    lines += ["", f"- 두 쪽이 같이 고른 장소 {both}곳(양쪽 평균에 다 들어감)."]
    if res["raters"] < 5:
        lines.append(f"- 평가자가 PRD의 5명보다 적다({res['raters']}명).")
    if res["duplicates"]:
        lines.append(f"- 같은 평가자 기록이 두 번 들어와 뒤의 것을 버렸다: {len(res['duplicates'])}건.")
    return "\n".join(lines) + "\n"


# ------------------------------------------------------------ 명령
def _load():
    con = sqlite3.connect(S.db_path())
    rows, refreshed = S.load_rows(con)
    meta, cur = S.load_meta(con), S.load_curation(con)
    addrs = dict(con.execute("SELECT id, addr FROM candidate"))
    con.close()
    if not rows:
        raise SystemExit(f"후보가 없다: {S.db_path()} (python -m chagok.sources refresh)")
    return rows, meta, cur, addrs, refreshed


def cmd_conditions(args) -> int:
    rows, meta, cur, _, refreshed = _load()
    print(f"후보 갱신 {refreshed} · A2 큐레이션 {len(cur)}곳(근거 {sum(1 for c in cur.values() if c.score)}) · 나들이 날 {args.date}")
    for cond in CONDITIONS:
        req = d1_request(cond["themes"])
        est = live.estimates(req, rows, meta, cur)
        ctx = ctx_for(cond, "a", args.date, [r[0] for r in rows])
        print(f"  {cond['key']:<9} 범위 안 {len(C._ranked(req, est, ctx)[1]):>4}곳")
    return 0


def cmd_run(args) -> int:
    rows, meta, cur, addrs, refreshed = _load()
    env = S.load_env()
    key = env.get("KAKAO_REST_API_KEY")
    if not key and not args.no_kakao:
        raise SystemExit("KAKAO_REST_API_KEY가 .env에 없다(서버와 같은 순위를 내려면 필요. 추정만 쓰려면 --no-kakao)")
    fetch = None if args.no_kakao else R.kakao_fetcher(key, timeout=3.0)
    cache = R.RouteCache()
    now = datetime.now(KST)
    record = {"created_at": now.isoformat(timespec="seconds"), "trip_date": args.date, "candidates_refreshed": refreshed,
              "curated": len(cur), "ranking": json.loads((C.FIXTURES / "constants.json").read_text(encoding="utf-8"))["ranking"],
              "conditions": []}
    conds = []
    for cond in CONDITIONS:
        req = d1_request(cond["themes"])
        c = {"key": cond["key"], "text": condition_text(cond, req, args.date),
             "label": E.THEME_KO[cond["themes"][0]] if cond["themes"] else "비 오는 날"}
        rec = {"key": cond["key"], "text": c["text"]}
        for side in SIDES:
            top, st = top_for(cond, side, args.date, rows, meta, cur, fetch=fetch, cache=cache)
            c[side] = top
            rec[side] = {"ranked_on": st.ranked_on, "kakao_calls": st.calls, "kakao_failures": st.failures,
                         "picks": [{"rank": i, "id": x["id"], "name": x["name"], "minutes": x["minutes"],
                                    "route_km": x["route_km"], "reason": x["reason"]} for i, x in enumerate(top, 1)]}
            if len(top) < PICK_N:
                print(f"  주의: {cond['key']} {side.upper()} {len(top)}곳(< {PICK_N})")
        conds.append(c)
        record["conditions"].append(rec)
        print(f"{c['label']}: A {len(c['a'])}곳 · B {len(c['b'])}곳 · 같이 고른 곳 "
              f"{len({x['id'] for x in c['a']} & {x['id'] for x in c['b']})}", flush=True)
    data, key_ = build_items(conds, addrs, secrets.SystemRandom())
    data["created"] = now.date().isoformat()
    OUT.mkdir(exist_ok=True)
    stamp = now.strftime("%Y%m%d-%H%M")
    (OUT / "items.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")
    (OUT / "key.json").write_text(json.dumps(key_ | {"labels": {c["key"]: c["label"] for c in conds}},
                                             ensure_ascii=False, indent=1), encoding="utf-8")
    (OUT / f"run-{stamp}.json").write_text(json.dumps(record, ensure_ascii=False, indent=1), encoding="utf-8")
    (OUT / "page.html").write_text(render_page(data), encoding="utf-8")
    print(f"장소 {len(data['places'])}곳 · 이유 {len(data['reasons'])}개 → {(OUT / 'page.html').relative_to(ROOT)}")
    print("key.json·run-*.json은 점수를 다 매길 때까지 열지 않기")
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
    md = result_markdown(res, key, key.get("labels"))
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
    for name in ("conditions", "run"):
        sp = sub.add_parser(name)
        sp.add_argument("--date", default=default_date(), help="나들이 날 YYYY-MM-DD(기본: 다음 토요일)")
        if name == "run":
            sp.add_argument("--no-kakao", action="store_true", help="실경로 없이 추정값만(서버와 순서가 달라질 수 있음)")
    sub.add_parser("page")
    sp = sub.add_parser("tally")
    sp.add_argument("files", nargs="+")
    args = ap.parse_args(argv)
    return {"conditions": cmd_conditions, "run": cmd_run, "page": cmd_page, "tally": cmd_tally}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
