"""실서비스 모드 데이터 소스 — TourAPI 후보 캐시(SQLite).

PRD v1.3 7-1·7-4, 엔진 PRD 6절: 후보 풀은 TourAPI를 하루 1회 받아 서버에 캐시한다
(위치기반 조회는 반경 20km 한계라 지역·분류 단위로 미리 적재).
키는 .env(TOURAPI_SERVICE_KEY)에만 둔다. 화면·로그·DB에 키를 남기지 않는다.

    python -m chagok.sources refresh          # 수도권·근교 6개 시도 × 테마 분류 적재 (server/ 에서)
    python -m chagok.sources stats            # 테마·지역별 건수
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
}

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
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS candidate_theme ON candidate(theme);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
"""


def connect(path: Path | None = None) -> sqlite3.Connection:
    p = path or db_path()
    p.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(p)
    con.executescript(SCHEMA)
    return con


# ------------------------------------------------------------ TourAPI
class TourApiError(RuntimeError):
    pass


def in_region(region: str, lat: float, lng: float) -> bool:
    lat0, lat1, lng0, lng1 = REGIONS[region][1]
    return lat0 <= lat <= lat1 and lng0 <= lng <= lng1


def fetch_area_based(key: str, base: str, region: str, lcls3: str, page: int, rows: int = 100) -> tuple[int, list[dict]]:
    """areaBasedList2 한 페이지. (totalCount, items). 오류 메시지에서 키는 지운다."""
    q = {"serviceKey": urllib.parse.unquote(key) if "%" in key else key, "MobileOS": "AND", "MobileApp": "Chagok",
         "_type": "json", "numOfRows": rows, "pageNo": page, "arrange": "A", "contentTypeId": 12,
         "lDongRegnCd": region, "lclsSystm1": lcls3[:2], "lclsSystm2": lcls3[:4], "lclsSystm3": lcls3}
    url = f"{base}/areaBasedList2?{urllib.parse.urlencode(q)}"
    try:
        raw = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Chagok"}), timeout=20).read()
    except (urllib.error.URLError, TimeoutError) as e:
        raise TourApiError(str(e).replace(key, "<KEY>")) from None
    return parse_area_based(raw.decode("utf-8", "replace"))


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


def refresh(con: sqlite3.Connection, fetch, regions: list[str] | None = None, today: str | None = None) -> RefreshResult:
    """지역 × 테마 분류마다 전 페이지를 받아 후보 테이블을 갈아 끼운다. fetch(region, lcls3, page) → (total, items)."""
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
                                              1 if it.get("firstimage") else 0, today))
                    if page * 100 >= total or not items:
                        break
                    page += 1
    with con:
        con.execute("DELETE FROM candidate")
        con.executemany("INSERT INTO candidate VALUES (?,?,?,?,?,?,?,?,?,?,?)", list(rows.values()))
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


def estimate(lat: float, lng: float, rows: list[Row]) -> list[E.Candidate]:
    """출발지 기준 후보. 거리·시간은 직선 × 굴곡 계수, 평균 속도로 추정(정밀값은 상위 후보만 경로 API로 덮어씀).
    통행료는 모름(0) → 상위 후보는 live.refine 이 실경로로 바꾼다."""
    out = []
    for cid, name, theme, clat, clng in rows:
        straight = haversine_km(lat, lng, clat, clng)
        if straight > MAX_STRAIGHT_KM:
            continue
        route = E.round1(straight * E.ROAD_FACTOR)
        out.append(E.Candidate(id=cid, name=name, theme=theme, lat=clat, lng=clng, route_km=route,
                               minutes=E.round_half_up(EST_BASE_MIN + EST_MIN_PER_KM * straight), toll_krw=0,
                               parking_krw=None, has_parking_info=False))
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
        res = refresh(con, lambda region, lcls3, page: fetch_area_based(key, base, region, lcls3, page), regions)
        print(f"TourAPI 호출 {res.calls}회 · 저장 {res.saved}곳 · 좌표 오류로 버림 {res.dropped_coords}곳 → {db_path()}")
    rows = con.execute("SELECT theme, region, COUNT(*) FROM candidate GROUP BY theme, region").fetchall()
    when = con.execute("SELECT value FROM meta WHERE key='tourapi_refreshed'").fetchone()
    print(f"후보 {sum(r[2] for r in rows)}곳 (갱신 {when[0] if when else '없음'})")
    by_theme: dict[str, dict[str, int]] = {}
    for theme, region, n in rows:
        by_theme.setdefault(theme, {})[REGIONS.get(region, (region,))[0]] = n
    for theme in THEME_LCLS:
        cells = by_theme.get(theme, {})
        print(f"  {E.THEME_KO[theme]:<6} {sum(cells.values()):>4}  " + " ".join(f"{k} {v}" for k, v in cells.items()))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
