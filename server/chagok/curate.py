"""A2 사전 큐레이션 — 웹 검색으로 후보의 나들이 평판·한 줄 소개·좋은 시기를 미리 매겨 SQLite(curation)에 둔다.

요청(/v1/cards) 때는 부르지 않는다: 웹 검색 1곳 실측 Haiku 5.5 9.9초(11/6) → 앱 타임아웃 3초에 못 맞춘다.
B 순위(CHAGOK_RANKER=b)만 이 값을 쓴다(engine.reputation_fit, 이유 한 줄 = intro). A1은 쓰지 않는다.

비용을 줄이려고 후보마다가 아니라 시군구 묶음마다 부른다(장소는 테마를 섞고, 축제는 따로. 묶음당 검색 최대 2회, 20곳씩).
근거 규칙: 검색 결과에 실제로 있던 URL만 인정(코드로 확인). 근거가 없으면 점수·소개를 비운다(B는 '중간보다 낮게').
키: .env의 ANTHROPIC_API_KEY. 후보 이름·주소·분류만 보낸다(사용자·차량 정보 없음).

    python -m chagok.curate plan --scope d1             # 묶음 수·예상 비용(호출 없음)
    python -m chagok.curate run --scope d1 --max-usd 9  # 실행(이미 한 곳은 건너뜀, --redo로 다시)
    python -m chagok.curate stats

scope: d1 = D1 출발지(fixtures/cards/D1.json) 추가 0원 범위 안(블라인드 검수용), all = 후보 전체.
anthropic SDK가 필요하다: pip install "server[curate]" 또는 uv run --with anthropic.
"""

from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
import time
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path

from . import cards as C
from . import engine as E
from . import sources as S

MODEL = "claude-haiku-5-5"
MAX_PER_CALL = 20                 # 한 번에 평가할 후보 수
MAX_SEARCH = {"place": 3, "festival": 2}   # 묶음당 웹 검색 상한(장소 20곳은 검색 2번이면 절반 넘게 근거를 못 찾음, 11/6 시험)
PRICE_IN, PRICE_OUT = 0.10 / 1e6, 0.50 / 1e6   # Haiku 5.5, 입력 10만 토큰 이하(달러/토큰)
PRICE_SEARCH = 0.01               # 웹 검색 1회
EST_CALL_USD = 0.04               # 묶음 1회 어림(검색 3회 + 토큰). plan·예산 확인용 [가정, 11/6 실측 0.026(검색 2회)]
INTRO_MAX = 40
KST = timezone(timedelta(hours=9))

SYSTEM = """너는 수도권 사람들의 당일 나들이 장소를 고르는 큐레이터다.
답을 쓰기 전에 반드시 웹 검색을 한다(최소 1번). 검색으로 찾은 근거로만 판단하고,
근거를 못 찾으면 모른다고 답한다(0점). 이름만 보고 추측으로 점수를 주지 않는다."""

USER = """## 지역
- {region} ({kind})

## 후보 {n}곳
id | 이름 | 테마 | 분류 | 주소{period_head}
{rows}

## 할 일
먼저 웹 검색을 한다({max_search}번까지). 예: {search_hint}
후보마다 아래를 채운다.
- score: 당일 나들이 목적지로서의 평판 1~5. 검색 결과 글에서 그 장소가 어떻게 소개되는지로 정한다
  - 5 = 여러 글에서 이 지역(또는 수도권) 대표 명소로 꼽힘
  - 4 = '가볼만한곳'·추천 글 목록에 이름이 올라 있음
  - 3 = 소개 글이 있고, 나들이로 갈 만하다는 내용
  - 2 = 언급은 있지만 일부러 찾아갈 목적지로는 약함
  - 1 = 나들이에 맞지 않음(폐쇄·공사·논란 포함)
  - 0 = 검색 결과에서 그 장소를 다룬 글을 못 찾음
- evidence_urls: score의 근거가 된 검색 결과 URL 최대 2개. 검색 결과에 실제로 나온 URL만. 0점이면 빈 배열
- intro: 카드에 붙는 한 줄 소개. 한국어, 공백 포함 30자 안팎(40자를 넘지 않게). 0점이면 빈 문자열
  - 금액·무료·주차·시간·거리 숫자·운영 시간·이모지를 쓰지 않는다
  - 근거 글에 없는 사실을 쓰지 않고, 다른 앱·서비스와 비교하지 않는다
- good_months: 가기 좋은 달(1~12). 계절을 타지 않으면 빈 배열. 해수욕장·꽃·단풍처럼 철이 있으면 그 달만"""

SCHEMA = {
    "type": "object",
    "properties": {"places": {"type": "array", "items": {
        "type": "object",
        "properties": {"id": {"type": "string"}, "score": {"type": "integer"},
                       "evidence_urls": {"type": "array", "items": {"type": "string"}},
                       "intro": {"type": "string"},
                       "good_months": {"type": "array", "items": {"type": "integer"}}},
        "required": ["id", "score", "evidence_urls", "intro", "good_months"],
        "additionalProperties": False}}},
    "required": ["places"],
    "additionalProperties": False,
}

# 소개 문구 점검(버리면 flags에 남긴다): 프롬프트 규칙 + 화면 문구 규칙(scripts/lint_copy.py와 같은 취지)
INTRO_FLAGS = [
    (re.compile(r"\d[\d,]*\s*원|무료|0원|비용|요금|입장료|주차"), "금액·주차"),
    (re.compile(r"\d+\s*(분|km|킬로|시간|시)"), "시간·거리 숫자"),
    (re.compile(r"글레오|예상\s*(vs|대비|과|와)\s*실제"), "금지 문구(AC-C-8)"),
    (re.compile("[\U0001F300-\U0001FAFF☀-➿]"), "이모지"),
]


# ------------------------------------------------------------ 묶음
@dataclass
class Group:
    key: str                      # place|festival:sigungu#n
    theme: str                    # place(장소, 테마 섞음) | festival
    region: str                   # 주소 앞 두 마디(예: 경기도 화성시)
    items: list[dict] = field(default_factory=list)   # id, name, lcls3, addr, event_start, event_end


def _region(addrs: list[str]) -> str:
    heads = Counter(" ".join(a.split()[:2]) for a in addrs if a.strip())
    return heads.most_common(1)[0][0] if heads else "수도권"


def d1_in_range(con: sqlite3.Connection) -> set[str]:
    """D1 출발지에서 추가 0원 범위 안(추정, 90분 안) 후보 id."""
    d1 = json.loads((C.FIXTURES / "cards" / "D1.json").read_text(encoding="utf-8"))
    req = {k: v for k, v in d1["request"].items() if k != "demo_seed"} | {"themes": []}
    rows, _ = S.load_rows(con)
    est = S.estimate(req["origin"]["lat"], req["origin"]["lng"], rows, S.load_meta(con))
    return {c["id"] for c in C._ranked(req, est)[1]}


def build_groups(con: sqlite3.Connection, scope: str = "d1", redo: bool = False) -> list[Group]:
    cfg = C.RANKING
    done = set() if redo else {r[0] for r in con.execute("SELECT id FROM curation")}
    keep = d1_in_range(con) if scope == "d1" else None
    by: dict[tuple[str, str], list[dict]] = defaultdict(list)
    q = "SELECT id, name, theme, lcls3, addr, sigungu, event_start, event_end FROM candidate ORDER BY id"
    for cid, name, theme, lcls3, addr, sg, es, ee in con.execute(q):
        cand = E.Candidate(id=cid, name=name, theme=theme, lat=0, lng=0, route_km=0, minutes=0, lcls3=lcls3 or None)
        if cid in done or E.excluded(cand, cfg) or (keep is not None and cid not in keep):
            continue
        kind = "festival" if theme == "festival" else "place"
        by[(kind, sg or "?")].append({"id": cid, "name": name, "theme": theme, "lcls3": lcls3, "addr": addr or "",
                                      "event_start": es, "event_end": ee})
    out = []
    for (theme, sg), items in sorted(by.items()):
        region = _region([x["addr"] for x in items])
        for i in range(0, len(items), MAX_PER_CALL):
            out.append(Group(f"{theme}:{sg}#{i // MAX_PER_CALL + 1}", theme, region, items[i:i + MAX_PER_CALL]))
    return out


def render(g: Group) -> str:
    fest = g.theme == "festival"
    lines = []
    for x in g.items:
        cells = [x["id"], x["name"], E.THEME_KO.get(x["theme"], x["theme"]),
                 C.RANKING.subcategory_ko.get(x["lcls3"], x["lcls3"] or "-"), " ".join(x["addr"].split()[:4])]
        if fest:
            cells.append(f"{x['event_start']}~{x['event_end']}")
        lines.append(" | ".join(c.replace("|", "/") for c in cells))
    hint = ('"축제 이름 후기", "축제 이름 2025"' if fest else
            f'"{g.region} 가볼만한곳", "{g.region} 실내 가볼만한곳", "{g.region} 나들이 추천"')
    return USER.format(region=g.region, kind="행사·축제" if fest else "나들이 장소", n=len(g.items),
                       period_head=" | 기간" if fest else "", rows="\n".join(lines), max_search=MAX_SEARCH[g.theme],
                       search_hint=hint)


# ------------------------------------------------------------ 호출
def _norm(u: str) -> str:
    return u.strip().rstrip("/").replace("http://", "https://")


def seen_urls(content) -> set[str]:
    """응답 블록에서 웹 검색·가져오기 결과 URL(근거로 인정할 수 있는 것)."""
    out: set[str] = set()
    for b in content:
        t = getattr(b, "type", "")
        if t == "web_search_tool_result" and isinstance(getattr(b, "content", None), list):
            out |= {_norm(r.url) for r in b.content if getattr(r, "url", None)}
        elif t == "web_fetch_tool_result":
            url = getattr(getattr(b, "content", None), "url", None)
            if url:
                out.add(_norm(url))
    return out


@dataclass
class CallResult:
    parsed: dict | None
    urls: set[str]
    usage: dict
    request_id: str | None
    stop_reason: str | None


def call(client, g: Group) -> CallResult:
    """묶음 한 번. 서버 도구가 길어 pause_turn이면 이어서 부른다(최대 3번). 거절·잘림은 parsed=None."""
    messages = [{"role": "user", "content": render(g)}]
    urls: set[str] = set()
    usage = {"input_tokens": 0, "output_tokens": 0, "web_search_requests": 0}
    resp = None
    # 기본 검색 도구(20250305): 동적 필터판(20260209)은 11/6 시험에서 검색을 아예 안 하는 응답이 나왔고 강제(tool_choice)도 막혀 있다.
    # 검색을 한 번도 안 하고 끝나면 한 번 다시 부른다(근거 없이 점수를 매기지 않게).
    retried = False
    for _ in range(5):
        resp = client.messages.create(
            model=MODEL, max_tokens=8000, system=SYSTEM, messages=messages,
            tools=[{"type": "web_search_20250305", "name": "web_search", "max_uses": MAX_SEARCH[g.theme]}],
            output_config={"format": {"type": "json_schema", "schema": SCHEMA}},
        )
        urls |= seen_urls(resp.content)
        u = resp.usage
        usage["input_tokens"] += u.input_tokens or 0
        usage["output_tokens"] += u.output_tokens or 0
        stu = getattr(u, "server_tool_use", None)
        usage["web_search_requests"] += (getattr(stu, "web_search_requests", 0) or 0) if stu else 0
        if resp.stop_reason == "pause_turn":
            messages = [messages[0], {"role": "assistant", "content": resp.content}]
            continue
        if resp.stop_reason == "end_turn" and usage["web_search_requests"] == 0 and not retried:
            retried = True
            continue
        break
    parsed = None
    if resp is not None and resp.stop_reason == "end_turn":
        text = "".join(b.text for b in resp.content if getattr(b, "type", "") == "text")
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            parsed = None
    return CallResult(parsed, urls, usage, getattr(resp, "_request_id", None), getattr(resp, "stop_reason", None))


def cost(usage: dict) -> float:
    return (usage["input_tokens"] * PRICE_IN + usage["output_tokens"] * PRICE_OUT
            + usage["web_search_requests"] * PRICE_SEARCH)


def intro_flags(text: str) -> list[str]:
    flags = [msg for rx, msg in INTRO_FLAGS if rx.search(text)]
    if len(text) > INTRO_MAX:
        flags.append(f"{INTRO_MAX}자 넘음")
    return flags


def validate(g: Group, r: CallResult) -> list[tuple]:
    """DB 행(id, score, intro, urls, months, flags). 근거 URL이 검색 결과에 없으면 점수·소개를 비운다."""
    got = {p.get("id"): p for p in (r.parsed or {}).get("places", []) if isinstance(p, dict)}
    rows = []
    for x in g.items:
        p = got.get(x["id"])
        flags: list[str] = []
        score = intro = None
        urls: list[str] = []
        months: list[int] = []
        if p is None:
            flags.append("응답에 없음" if r.parsed is not None else f"응답 실패({r.stop_reason})")
        else:
            s = p.get("score")
            urls = [u for u in p.get("evidence_urls") or [] if isinstance(u, str) and _norm(u) in r.urls][:2]
            if len(urls) < len([u for u in p.get("evidence_urls") or [] if isinstance(u, str)][:2]):
                flags.append("검색 결과에 없는 URL 버림")
            if isinstance(s, int) and 1 <= s <= 5:
                if urls:
                    score = float(s)
                else:
                    flags.append("근거 URL 없음 → 점수 비움")
            text = " ".join(str(p.get("intro") or "").split())
            if score is not None and text:
                bad = intro_flags(text)
                if bad:
                    flags.append("소개 버림: " + ", ".join(bad))
                else:
                    intro = text
            months = sorted({m for m in p.get("good_months") or [] if isinstance(m, int) and 1 <= m <= 12})
            if len(months) == 12:
                months = []
        rows.append((x["id"], score, intro, json.dumps(urls, ensure_ascii=False), json.dumps(months),
                     json.dumps(flags, ensure_ascii=False)))
    return rows


def save(con: sqlite3.Connection, g: Group, rows: list[tuple], request_id: str | None) -> None:
    now = datetime.now(KST).isoformat(timespec="seconds")
    with con:
        con.executemany("INSERT OR REPLACE INTO curation (id, score, intro, urls, months, flags, group_key, model, made_at,"
                        " request_id) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        [(*r, g.key, MODEL, now, request_id) for r in rows])
        con.execute("INSERT OR REPLACE INTO meta VALUES ('curation_updated', ?)", (now,))


# ------------------------------------------------------------ 명령
def run(con: sqlite3.Connection, client, groups: list[Group], max_usd: float, log_path: Path | None = None,
        echo=print) -> dict:
    spent = 0.0
    done = calls = 0
    for g in groups:
        if spent + EST_CALL_USD > max_usd:
            echo(f"예산 {max_usd:.2f}달러에 닿아 멈춤(남은 묶음 {len(groups) - done})")
            break
        t0 = time.perf_counter()
        r = call(client, g)
        calls += 1
        c = cost(r.usage)
        spent += c
        rows = validate(g, r)
        save(con, g, rows, r.request_id)
        done += 1
        scored = sum(1 for x in rows if x[1] is not None)
        if log_path is not None:
            with log_path.open("a", encoding="utf-8") as f:
                f.write(json.dumps({"group": g.key, "n": len(g.items), "scored": scored, "usd": round(c, 4),
                                    "stop": r.stop_reason, "request_id": r.request_id, **r.usage,
                                    "sec": round(time.perf_counter() - t0, 1)}, ensure_ascii=False) + "\n")
        echo(f"[{done}/{len(groups)}] {g.key} {len(g.items)}곳 → 근거 {scored}곳 · {c:.3f}달러 (누적 {spent:.2f})")
    return {"groups": done, "calls": calls, "usd": spent}


def stats(con: sqlite3.Connection) -> str:
    total, scored, intro = con.execute(
        "SELECT COUNT(*), COUNT(score), COUNT(intro) FROM curation").fetchone()
    dist = dict(con.execute("SELECT score, COUNT(*) FROM curation WHERE score IS NOT NULL GROUP BY score"))
    when = con.execute("SELECT value FROM meta WHERE key='curation_updated'").fetchone()
    return (f"큐레이션 {total}곳 · 근거 있음 {scored} · 소개 {intro} · 점수 분포 {dist} · "
            f"갱신 {when[0] if when else '없음'}")


def main(argv: list[str] | None = None) -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass
    ap = argparse.ArgumentParser(description="A2 사전 큐레이션")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("plan", "run"):
        sp = sub.add_parser(name)
        sp.add_argument("--scope", choices=["d1", "all"], default="d1")
        sp.add_argument("--redo", action="store_true", help="이미 매긴 곳도 다시")
        sp.add_argument("--limit", type=int, default=0, help="묶음 수 상한(시험용)")
        sp.add_argument("--themes", default="", help="쉼표로 테마만(예: sea,festival)")
        if name == "run":
            sp.add_argument("--max-usd", type=float, required=True, help="이번 실행 비용 상한(달러)")
    sub.add_parser("stats")
    args = ap.parse_args(argv)
    con = S.connect()
    if args.cmd == "stats":
        print(stats(con))
        return 0
    groups = build_groups(con, args.scope, args.redo)
    if args.themes:
        want = set(args.themes.split(","))
        groups = [g for g in groups if g.theme in want]
    if args.limit:
        groups = groups[:args.limit]
    n = sum(len(g.items) for g in groups)
    print(f"묶음 {len(groups)}개 · 후보 {n}곳 · 예상 약 {len(groups) * EST_CALL_USD:.1f}달러(묶음당 {EST_CALL_USD}달러 가정)")
    if args.cmd == "plan":
        return 0
    key = S.load_env().get("ANTHROPIC_API_KEY")
    if not key:
        print("ANTHROPIC_API_KEY가 .env에 없다")
        return 2
    import anthropic
    res = run(con, anthropic.Anthropic(api_key=key), groups, args.max_usd,
              log_path=S.db_path().parent / "curation_log.jsonl")
    print(f"끝: 묶음 {res['groups']}개 · {res['usd']:.2f}달러 · {stats(con)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
