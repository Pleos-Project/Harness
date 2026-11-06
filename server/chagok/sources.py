"""실서비스 모드 데이터 소스 — TourAPI 후보 캐시(SQLite).

PRD v1.3 7-1·7-4, 엔진 PRD 6절: 후보 풀은 TourAPI를 하루 1회 받아 서버에 캐시한다
(위치기반 조회는 반경 20km 한계라 지역·분류 단위로 미리 적재).
키는 .env(TOURAPI_SERVICE_KEY)에만 둔다. 화면·로그·DB에 키를 남기지 않는다.

    python -m chagok.sources refresh          # 수도권·근교 6개 시도 × 테마 분류 + 행사(축제) 적재 (server/ 에서)
    python -m chagok.sources stats            # 테마·지역별 건수

후보 = 관광지(contentTypeId 12) + 실내 문화시설(박물관·미술관 등, 분류로만 거름) + 행사(searchFestival2, 테마 festival).
점수 규칙(목적지성·실내·제외·행사 날짜)은 여기서 거르지 않고 엔진(engine.py RankingConfig)이 정한다 — 값은 fixtures/constants.json.
"""

from __future__ import annotations

import json
import math
import os
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from . import engine as E

REPO = Path(__file__).resolve().parents[2]
DEFAULT_DB = REPO / "server" / "data" / "chagok.sqlite3"

# 테마 8개 ↔ TourAPI 신분류체계 소분류(lclsSystm3). 출처: 한국관광공사 「신분류체계정보 관광타입정보 연계 정의서」
THEME_LCLS: dict[str, list[str]] = {
    "sea": ["NA020900", "NA020800", "NA020700"],                          # 해변·해수욕장, 해안절경, 항구·포구
    "lake": ["NA020200", "NA020300"],                                    # 호수, 저수지
    "river": ["NA020100"],                                               # 강
    "mountain": ["NA010100", "NA010200", "NA010300", "NA010400", "NA040600"],  # 산, 숲, 폭포, 계곡, 자연휴양림
    "park": ["VE030100", "VE030400", "VE030500", "NA040700"],            # 시민·근린·주제공원, 수목원·정원
    "city": ["VE040100", "VE010200"],                                    # 골목길·문화거리, 타워·전망대
    "island": ["NA020500"],                                              # 섬
    "drive": ["VE010300"],                                               # [제안] 맞는 분류가 없어 다리·대교. 명준 확인 대기
    # [제안] 비 오는 날 후보. 박물관·기념관·전시관·과학관·미술관/화랑, 수족관/아쿠아리움. 문화시설(14)·관광지(12)에 섞여 있어 타입을 안 붙인다
    "indoor": ["VE070100", "VE070200", "VE070300", "VE070500", "VE070600", "VE020400"],
}
NO_CONTENT_TYPE = set(THEME_LCLS["indoor"])
FESTIVAL_DAYS = 35   # 행사는 오늘부터 이 기간 안에 열리는 것만 적재(나들이 날 걸러내기는 엔진 event_active)

# 수도권·근교 시도(법정동 시도 코드 → 이름, 좌표 상자). 좌표가 상자 밖이면 버린다(TourAPI 좌표 오류 대비, 예: 고삼호수 경도 128.0).
# 상자는 시도 경계를 넉넉히 감싼 근사값(섬 포함) [가정].
REGIONS: dict[str, tuple[str, tuple[float, float, float, float]]] = {
    "11": ("서울", (37.40, 37.72, 126.75, 127.20)),
    "28": ("인천", (36.95, 37.85, 124.55, 126.85)),
    "41": ("경기", (36.88, 38.32, 126.35, 127.88)),
    "51": ("강원", (37.00, 38.65, 127.05, 129.40)),
    "43": ("충북", (35.98, 37.28, 127.25, 128.68)),
    "44": ("충남", (35.95, 37.10, 125.50, 127.66)),
}

# 직선거리 → 편도 시간 추정 [가정]: 분 = 19 + 1.0 × 직선km. 카카오 실측 데모 12곳(직선 11~64km) 회귀, 오차 ±12분 안.
# 고정 평균 속도(55km/h)는 가까운 곳은 빠르게, 고속도로로 가는 먼 곳은 느리게 잡아 '조금 먼 곳'을 놓친다.
EST_BASE_MIN = 19.0
EST_MIN_PER_KM = 1.0
MAX_STRAIGHT_KM = 100.0  # 추정 90분 = 직선 71km. 여유를 둬 이보다 먼 후보는 계산하지 않는다


# ------------------------------------------------------------ 설정
def load_env() -> dict[str, str]:
    env = dict(os.environ)
    f = REPO / ".env"
    if f.exists():
        for line in f.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env.setdefault(k.strip(), v.strip().strip('"').strip("'"))
    return env


def db_path() -> Path:
    return Path(os.environ.get("CHAGOK_DB") or DEFAULT_DB)


# ------------------------------------------------------------ SQLite
SCHEMA = """
CREATE TABLE IF NOT EXISTS candidate (
  id TEXT PRIMARY KEY,          -- ta_<contentid>
  contentid TEXT NOT NULL,
  name TEXT NOT NULL,
  theme TEXT NOT NULL,
  lcls3 TEXT NOT NULL,
  region TEXT NOT NULL,
  addr TEXT,
  lat REAL NOT NULL,
  lng REAL NOT NULL,
  image INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL,
  sigungu TEXT,                 -- 법정동 시도+시군구 코드(날씨 대표 지점·A2 묶음)
  event_start TEXT,             -- 행사만: YYYY-MM-DD
  event_end TEXT
);
CREATE INDEX IF NOT EXISTS candidate_theme ON candidate(theme);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS curation (   -- A2 사전 큐레이션(chagok/curate.py). B 순위만 쓴다
  id TEXT PRIMARY KEY,
  score REAL,                   -- 평판 1~5, 근거 URL이 없으면 NULL
  intro TEXT,                   -- 한 줄 소개(문구 점검 통과한 것만), 없으면 NULL
  urls TEXT NOT NULL,           -- 근거 URL JSON 배열(검색 결과에 실제로 있던 것만)
  months TEXT NOT NULL,         -- 좋은 달 JSON 배열(연중이면 [])
  flags TEXT NOT NULL,          -- 버린 값과 이유 JSON
  group_key TEXT NOT NULL,
  model TEXT NOT NULL,
  made_at TEXT NOT NULL,
  request_id TEXT
);
"""
_ADDED_COLUMNS = (("sigungu", "TEXT"), ("event_start", "TEXT"), ("event_end", "TEXT"))
_COLUMNS = ("id", "contentid", "name", "theme", "lcls3", "region", "addr", "lat", "lng", "image", "updated_at",
            "sigungu", "event_start", "event_end")


def connect(path: Path | None = None) -> sqlite3.Connection:
    p = path or db_path()
    p.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(p)
    con.executescript(SCHEMA)
    have = {r[1] for r in con.execute("PRAGMA table_info(candidate)")}
    for col, typ in _ADDED_COLUMNS:   # 11/6 이전에 만든 DB(운영 서버)에 열을 더한다
        if col not in have:
            con.execute(f"ALTER TABLE candidate ADD COLUMN {col} {typ}")
    con.commit()
    return con


# ------------------------------------------------------------ TourAPI
class TourApiError(RuntimeError):
    pass


def in_region(region: str, lat: float, lng: float) -> bool:
    lat0, lat1, lng0, lng1 = REGIONS[region][1]
    return lat0 <= lat <= lat1 and lng0 <= lng <= lng1


def _get(key: str, base: str, op: str, q: dict) -> tuple[int, list[dict]]:
    q = {"serviceKey": urllib.parse.unquote(key) if "%" in key else key, "MobileOS": "AND", "MobileApp": "Chagok",
         "_type": "json", **q}
    url = f"{base}/{op}?{urllib.parse.urlencode(q)}"
    try:
        raw = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Chagok"}), timeout=20).read()
    except (urllib.error.URLError, TimeoutError) as e:
        raise TourApiError(str(e).replace(key, "<KEY>").replace(q["serviceKey"], "<KEY>")) from None
    return parse_area_based(raw.decode("utf-8", "replace"))


def fetch_area_based(key: str, base: str, region: str, lcls3: str, page: int, rows: int = 100) -> tuple[int, list[dict]]:
    """areaBasedList2 한 페이지. (totalCount, items). 오류 메시지에서 키는 지운다.
    실내 분류는 관광지(12)·문화시설(14)에 섞여 있어 contentTypeId를 붙이지 않는다."""
    q = {"numOfRows": rows, "pageNo": page, "arrange": "A",
         "lDongRegnCd": region, "lclsSystm1": lcls3[:2], "lclsSystm2": lcls3[:4], "lclsSystm3": lcls3}
    if lcls3 not in NO_CONTENT_TYPE:
        q["contentTypeId"] = 12
    return _get(key, base, "areaBasedList2", q)


def fetch_festival(key: str, base: str, start: str, end: str, page: int, rows: int = 100) -> tuple[int, list[dict]]:
    """searchFestival2 한 페이지: start~end(YYYYMMDD)와 기간이 겹치는 행사 전국."""
    return _get(key, base, "searchFestival2", {"numOfRows": rows, "pageNo": page, "arrange": "A",
                                                "eventStartDate": start, "eventEndDate": end})


def _iso(yyyymmdd: str | None) -> str | None:
    s = (yyyymmdd or "").strip()
    return f"{s[:4]}-{s[4:6]}-{s[6:8]}" if len(s) == 8 and s.isdigit() else None


def parse_area_based(text: str) -> tuple[int, list[dict]]:
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        raise TourApiError(f"JSON 아님: {text[:120]}") from None
    head = data.get("response", {}).get("header", {})
    if head.get("resultCode") != "0000":
        raise TourApiError(f"TourAPI 오류 {head}")
    body = data["response"]["body"]
    items = body.get("items") or {}
    items = items.get("item", []) if isinstance(items, dict) else []
    return int(body.get("totalCount") or 0), (items if isinstance(items, list) else [items])


@dataclass
class RefreshResult:
    calls: int = 0
    saved: int = 0
    dropped_coords: int = 0
    festivals: int = 0


def _sigungu(it: dict) -> str | None:
    r, s = (it.get("lDongRegnCd") or "").strip(), (it.get("lDongSignguCd") or "").strip()
    return f"{r}{s}" if r and s else None


def refresh(con: sqlite3.Connection, fetch, regions: list[str] | None = None, today: str | None = None,
            fetch_festivals=None) -> RefreshResult:
    """지역 × 테마 분류마다 전 페이지를 받아 후보 테이블을 갈아 끼운다. fetch(region, lcls3, page) → (total, items).
    fetch_festivals(page) → (total, items)가 있으면 행사(theme festival)도 같은 갱신에 넣는다(시도·좌표 상자 밖은 버림)."""
    today = today or time.strftime("%Y-%m-%d")
    res = RefreshResult()
    rows: dict[str, tuple] = {}
    for region in regions or list(REGIONS):
        for theme, codes in THEME_LCLS.items():
            for lcls3 in codes:
                page = 1
                while True:
                    total, items = fetch(region, lcls3, page)
                    res.calls += 1
                    for it in items:
                        try:
                            lat, lng = float(it.get("mapy") or 0), float(it.get("mapx") or 0)
                        except ValueError:
                            lat = lng = 0.0
                        if not in_region(region, lat, lng):
                            res.dropped_coords += 1
                            continue
                        cid = str(it["contentid"])
                        # 같은 장소가 여러 분류에 걸리면 먼저 나온 테마를 쓴다(THEME_LCLS 순서)
                        rows.setdefault(cid, (f"ta_{cid}", cid, it.get("title", "").strip(), theme, lcls3, region,
                                              (it.get("addr1") or "").strip(), lat, lng,
                                              1 if it.get("firstimage") else 0, today, _sigungu(it), None, None))
                    if page * 100 >= total or not items:
                        break
                    page += 1
    if fetch_festivals is not None:
        allowed = set(regions or REGIONS)
        page = 1
        while True:
            total, items = fetch_festivals(page)
            res.calls += 1
            for it in items:
                region = (it.get("lDongRegnCd") or "").strip()
                try:
                    lat, lng = float(it.get("mapy") or 0), float(it.get("mapx") or 0)
                except ValueError:
                    lat = lng = 0.0
                start, end = _iso(it.get("eventstartdate")), _iso(it.get("eventenddate"))
                if region not in allowed or not start or not end:
                    continue
                if not in_region(region, lat, lng):
                    res.dropped_coords += 1
                    continue
                cid = str(it["contentid"])
                if cid not in rows:
                    res.festivals += 1
                rows.setdefault(cid, (f"ta_{cid}", cid, it.get("title", "").strip(), "festival",
                                      (it.get("lclsSystm3") or "").strip(), region, (it.get("addr1") or "").strip(),
                                      lat, lng, 1 if it.get("firstimage") else 0, today, _sigungu(it), start, end))
            if page * 100 >= total or not items:
                break
            page += 1
    with con:
        con.execute("DELETE FROM candidate")
        con.executemany(f"INSERT INTO candidate ({', '.join(_COLUMNS)}) VALUES ({', '.join('?' * len(_COLUMNS))})",
                        list(rows.values()))
        con.execute("INSERT OR REPLACE INTO meta VALUES ('tourapi_refreshed', ?)", (today,))
    res.saved = len(rows)
    return res


# ------------------------------------------------------------ 실서비스 후보
def haversine_km(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = math.radians
    h = math.sin(r(lat2 - lat1) / 2) ** 2 + math.cos(r(lat1)) * math.cos(r(lat2)) * math.sin(r(lng2 - lng1) / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(h))


Row = tuple[str, str, str, float, float]   # id, name, theme, lat, lng


def load_rows(con: sqlite3.Connection) -> tuple[list[Row], str | None]:
    """후보 전체(메모리에 올려 둘 것)와 갱신일."""
    rows = [tuple(r) for r in con.execute("SELECT id, name, theme, lat, lng FROM candidate ORDER BY id")]
    when = con.execute("SELECT value FROM meta WHERE key='tourapi_refreshed'").fetchone()
    return rows, (when[0] if when else None)


@dataclass(frozen=True)
class Meta:
    """순위에 쓰는 후보 속성(엔진 Candidate로 옮겨 담는다)."""
    lcls3: str | None
    sigungu: str | None
    event_start: str | None
    event_end: str | None


@dataclass(frozen=True)
class Curation:
    """A2 사전 큐레이션 한 곳(B 순위)."""
    score: float | None
    intro: str | None
    months: tuple[int, ...] | None


def load_meta(con: sqlite3.Connection) -> dict[str, Meta]:
    return {r[0]: Meta(r[1] or None, r[2], r[3], r[4])
            for r in con.execute("SELECT id, lcls3, sigungu, event_start, event_end FROM candidate")}


def load_curation(con: sqlite3.Connection) -> dict[str, Curation]:
    out = {}
    for cid, score, intro, months in con.execute("SELECT id, score, intro, months FROM curation"):
        m = tuple(json.loads(months or "[]"))
        out[cid] = Curation(score, intro or None, m or None)
    return out


def estimate(lat: float, lng: float, rows: list[Row], meta: dict[str, Meta] | None = None,
             cur: dict[str, Curation] | None = None) -> list[E.Candidate]:
    """출발지 기준 후보. 거리·시간은 직선 × 굴곡 계수, 평균 속도로 추정(정밀값은 상위 후보만 경로 API로 덮어씀).
    통행료는 모름(0) → 상위 후보는 live.refine 이 실경로로 바꾼다.
    meta가 있으면 소분류·행사 날짜(A1 점수), cur가 있으면 A2 평판·소개·좋은 시기(B 점수)를 실어 보낸다."""
    out = []
    for cid, name, theme, clat, clng in rows:
        straight = haversine_km(lat, lng, clat, clng)
        if straight > MAX_STRAIGHT_KM:
            continue
        route = E.round1(straight * E.ROAD_FACTOR)
        m = (meta or {}).get(cid)
        a2 = (cur or {}).get(cid)
        out.append(E.Candidate(id=cid, name=name, theme=theme, lat=clat, lng=clng, route_km=route,
                               minutes=E.round_half_up(EST_BASE_MIN + EST_MIN_PER_KM * straight), toll_krw=0,
                               parking_krw=None, has_parking_info=False,
                               lcls3=m.lcls3 if m else None,
                               event_start=m.event_start if m else None, event_end=m.event_end if m else None,
                               reputation=a2.score if a2 else None, intro=a2.intro if a2 else None,
                               months=a2.months if a2 else None))
    return out


def real_candidates(lat: float, lng: float, con: sqlite3.Connection | None = None) -> list[E.Candidate]:
    own = con is None
    con = con or connect()
    try:
        return estimate(lat, lng, load_rows(con)[0])
    finally:
        if own:
            con.close()


# ------------------------------------------------------------ CLI
def main(argv: list[str]) -> int:
    cmd = argv[1] if len(argv) > 1 else "stats"
    con = connect()
    if cmd == "refresh":
        env = load_env()
        key = env.get("TOURAPI_SERVICE_KEY", "")
        base = env.get("TOURAPI_BASE_URL") or "https://apis.data.go.kr/B551011/KorService2"
        if not key:
            print("TOURAPI_SERVICE_KEY 가 .env 에 없다 (.env.example 참고)")
            return 2
        regions = argv[2].split(",") if len(argv) > 2 else None
        day0 = time.strftime("%Y%m%d")
        day1 = time.strftime("%Y%m%d", time.localtime(time.time() + FESTIVAL_DAYS * 86400))
        res = refresh(con, lambda region, lcls3, page: fetch_area_based(key, base, region, lcls3, page), regions,
                      fetch_festivals=lambda page: fetch_festival(key, base, day0, day1, page))
        print(f"TourAPI 호출 {res.calls}회 · 저장 {res.saved}곳(행사 {res.festivals}) · 좌표 오류로 버림 {res.dropped_coords}곳 → {db_path()}")
    rows = con.execute("SELECT theme, region, COUNT(*) FROM candidate GROUP BY theme, region").fetchall()
    when = con.execute("SELECT value FROM meta WHERE key='tourapi_refreshed'").fetchone()
    print(f"후보 {sum(r[2] for r in rows)}곳 (갱신 {when[0] if when else '없음'})")
    by_theme: dict[str, dict[str, int]] = {}
    for theme, region, n in rows:
        by_theme.setdefault(theme, {})[REGIONS.get(region, (region,))[0]] = n
    for theme in [*THEME_LCLS, "festival"]:
        cells = by_theme.get(theme, {})
        print(f"  {E.THEME_KO[theme]:<6} {sum(cells.values()):>4}  " + " ".join(f"{k} {v}" for k, v in cells.items()))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
