"""나들이 날 목적지 날씨 — 기상청 단기예보 캐시(SQLite). A1 점수의 '날씨 적합도'(engine.weather_fit)에 들어간다.

요청 때는 부르지 않는다(앱 타임아웃 3초, 기능명세서 v0.4 '서버 실패'). 크론이 하루 몇 번 받아 두고 요청은 읽기만 한다.
키는 공공데이터포털 인증키(TourAPI 키와 같은 계정이면 같은 키, 「기상청_단기예보 조회서비스」 활용신청 필요).
.env의 KMA_SERVICE_KEY가 있으면 그것, 없으면 TOURAPI_SERVICE_KEY. 키는 화면·로그·DB에 남기지 않는다.

    python -m chagok.weather refresh     # 후보가 있는 시군구 대표 지점 예보 적재 (server/ 에서)
    python -m chagok.weather stats       # 날짜별 비 정도 분포

비 정도 [가정, 실제 예보를 보고 조정]: 나들이 날 9~18시(1시간 단위) 중
  비·눈 예보(PTY≠0) 또는 강수확률 60% 이상인 시간이 3시간 이상 → 1
  그런 시간이 1~2시간이거나, 강수확률 30~59%인 시간이 3시간 이상 → 0.5
  그 외 → 0
대표 지점: 시군구마다 그 시군구 후보 좌표의 평균(시군구를 모르는 후보는 자기 좌표). 출발지 좌표는 쓰지 않는다(PRD 7-1).
"""

from __future__ import annotations

import json
import math
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

from . import sources as S

KST = timezone(timedelta(hours=9))
URL = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst"
BASE_HOURS = (2, 5, 8, 11, 14, 17, 20, 23)   # 발표 시각(발표 뒤 약 10분부터 제공)
DAY_FROM, DAY_TO = "0900", "1800"            # 나들이 시간대
RAIN_POP, MAYBE_POP = 60, 30
EVENING_HOUR = 18                            # [가정] 이 시각 이후 요청은 '내일' 나들이로 본다

SCHEMA = """
CREATE TABLE IF NOT EXISTS weather_day (
  grid TEXT NOT NULL,           -- 'nx,ny'
  date TEXT NOT NULL,           -- YYYY-MM-DD
  rain REAL NOT NULL,           -- 0 · 0.5 · 1
  rain_hours INTEGER NOT NULL,
  maybe_hours INTEGER NOT NULL,
  pop_max INTEGER NOT NULL,
  PRIMARY KEY (grid, date)
);
CREATE TABLE IF NOT EXISTS candidate_grid (id TEXT PRIMARY KEY, grid TEXT NOT NULL);
"""


class WeatherError(RuntimeError):
    pass


# ------------------------------------------------------------ 격자 변환(기상청 LCC, 동네예보 격자 5km)
def to_grid(lat: float, lng: float) -> tuple[int, int]:
    re_, grid = 6371.00877, 5.0
    slat1, slat2, olon, olat = (math.radians(v) for v in (30.0, 60.0, 126.0, 38.0))
    xo, yo = 43, 136
    r = re_ / grid
    sn = math.log(math.cos(slat1) / math.cos(slat2)) / math.log(
        math.tan(math.pi * 0.25 + slat2 * 0.5) / math.tan(math.pi * 0.25 + slat1 * 0.5))
    sf = math.tan(math.pi * 0.25 + slat1 * 0.5) ** sn * math.cos(slat1) / sn
    ro = r * sf / math.tan(math.pi * 0.25 + olat * 0.5) ** sn
    ra = r * sf / math.tan(math.pi * 0.25 + math.radians(lat) * 0.5) ** sn
    theta = math.radians(lng) - olon
    if theta > math.pi:
        theta -= 2.0 * math.pi
    if theta < -math.pi:
        theta += 2.0 * math.pi
    theta *= sn
    return int(math.floor(ra * math.sin(theta) + xo + 0.5)), int(math.floor(ro - ra * math.cos(theta) + yo + 0.5))


# ------------------------------------------------------------ 시각
def now_kst() -> datetime:
    return datetime.now(KST)


def trip_date(now: datetime | None = None) -> str:
    """나들이 날: 오늘, 18시 이후면 내일 [가정]."""
    now = now or now_kst()
    d = now.date() + (timedelta(days=1) if now.hour >= EVENING_HOUR else timedelta())
    return d.isoformat()


def latest_base(now: datetime | None = None) -> tuple[str, str]:
    """가장 최근 발표(base_date, base_time). 발표 뒤 15분은 기다린다."""
    t = (now or now_kst()) - timedelta(minutes=15)
    hours = [h for h in BASE_HOURS if h <= t.hour]
    if not hours:
        t = t - timedelta(days=1)
        hours = [23]
    return t.strftime("%Y%m%d"), f"{hours[-1]:02d}00"


# ------------------------------------------------------------ 비 정도
def rain_level(hours: dict[str, dict[str, str]]) -> tuple[float, int, int, int]:
    """hours: 'HHMM' → {'POP': '60', 'PTY': '1', ...}(그날 예보). 9~18시만 본다. → (비 정도, 비 시간, 애매한 시간, 최고 강수확률)."""
    rainy = maybe = pop_max = 0
    for hhmm, v in hours.items():
        if not (DAY_FROM <= hhmm <= DAY_TO):
            continue
        try:
            pop = int(v.get("POP", "0"))
        except ValueError:
            pop = 0
        pty = (v.get("PTY") or "0").strip()
        pop_max = max(pop_max, pop)
        if pty != "0" or pop >= RAIN_POP:
            rainy += 1
        elif pop >= MAYBE_POP:
            maybe += 1
    level = 1.0 if rainy >= 3 else (0.5 if rainy >= 1 or maybe >= 3 else 0.0)
    return level, rainy, maybe, pop_max


def parse_forecast(text: str) -> dict[str, dict[str, dict[str, str]]]:
    """getVilageFcst JSON → 'YYYY-MM-DD' → 'HHMM' → {category: value}."""
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        raise WeatherError(f"JSON 아님: {text[:120]}") from None
    head = data.get("response", {}).get("header", {})
    if head.get("resultCode") != "00":
        raise WeatherError(f"기상청 오류 {head}")
    items = (data["response"]["body"].get("items") or {}).get("item", [])
    out: dict[str, dict[str, dict[str, str]]] = defaultdict(lambda: defaultdict(dict))
    for it in items:
        d = it["fcstDate"]
        out[f"{d[:4]}-{d[4:6]}-{d[6:8]}"][it["fcstTime"]][it["category"]] = str(it["fcstValue"])
    return out


def fetch_forecast(key: str, nx: int, ny: int, base: tuple[str, str], timeout: float = 20.0
                   ) -> dict[str, dict[str, dict[str, str]]]:
    q = {"serviceKey": urllib.parse.unquote(key) if "%" in key else key, "pageNo": 1, "numOfRows": 1000,
         "dataType": "JSON", "base_date": base[0], "base_time": base[1], "nx": nx, "ny": ny}
    try:
        raw = urllib.request.urlopen(urllib.request.Request(f"{URL}?{urllib.parse.urlencode(q)}",
                                                            headers={"User-Agent": "Chagok"}), timeout=timeout).read()
    except (urllib.error.URLError, TimeoutError) as e:
        raise WeatherError(str(e).replace(key, "<KEY>").replace(q["serviceKey"], "<KEY>")) from None
    return parse_forecast(raw.decode("utf-8", "replace"))


# ------------------------------------------------------------ 적재
def connect(path: Path | None = None) -> sqlite3.Connection:
    con = S.connect(path)
    con.executescript(SCHEMA)
    return con


def candidate_grids(con: sqlite3.Connection) -> dict[str, str]:
    """후보 id → 'nx,ny'. 시군구가 있으면 그 시군구 후보 좌표 평균의 격자(대표 지점)."""
    rows = con.execute("SELECT id, sigungu, lat, lng FROM candidate").fetchall()
    sums: dict[str, list[float]] = {}
    for _, sg, lat, lng in rows:
        if sg:
            s = sums.setdefault(sg, [0.0, 0.0, 0])
            s[0] += lat
            s[1] += lng
            s[2] += 1
    rep = {sg: to_grid(s[0] / s[2], s[1] / s[2]) for sg, s in sums.items()}
    out = {}
    for cid, sg, lat, lng in rows:
        nx, ny = rep[sg] if sg in rep else to_grid(lat, lng)
        out[cid] = f"{nx},{ny}"
    return out


@dataclass
class RefreshResult:
    grids: int = 0
    calls: int = 0
    failures: int = 0
    days: int = 0


def refresh(con: sqlite3.Connection, fetch, now: datetime | None = None) -> RefreshResult:
    """fetch(nx, ny, base) → parse_forecast 결과. 격자마다 받아 날짜별 비 정도를 덮어쓴다(실패한 격자는 직전 값 유지)."""
    now = now or now_kst()
    base = latest_base(now)
    grids = candidate_grids(con)
    res = RefreshResult(grids=len(set(grids.values())))
    rows = []
    for g in sorted(set(grids.values())):
        nx, ny = (int(v) for v in g.split(","))
        res.calls += 1
        try:
            fc = fetch(nx, ny, base)
        except WeatherError:
            res.failures += 1
            continue
        for d, hours in fc.items():
            level, rainy, maybe, pop_max = rain_level(hours)
            if any(DAY_FROM <= h <= DAY_TO for h in hours):
                rows.append((g, d, level, rainy, maybe, pop_max))
    today = now.date().isoformat()
    with con:
        con.execute("DELETE FROM candidate_grid")
        con.executemany("INSERT INTO candidate_grid VALUES (?, ?)", list(grids.items()))
        con.executemany("INSERT OR REPLACE INTO weather_day VALUES (?, ?, ?, ?, ?, ?)", rows)
        con.execute("DELETE FROM weather_day WHERE date < ?", (today,))
        con.execute("INSERT OR REPLACE INTO meta VALUES ('weather_refreshed', ?)", (now.isoformat(timespec="minutes"),))
        con.execute("INSERT OR REPLACE INTO meta VALUES ('weather_base', ?)", (" ".join(base),))
    res.days = len({r[1] for r in rows})
    return res


# ------------------------------------------------------------ 요청 때 읽기
class WeatherIndex:
    """candidate_grid·weather_day를 메모리에 올려 두고 크론 갱신(meta.weather_refreshed)을 check_s마다 확인한다.
    DB나 표가 없으면 빈 값 → 모든 후보 비 0(맑음 취급)."""

    def __init__(self, path: Path | None = None, check_s: float = 60.0, clock=time.monotonic) -> None:
        self._path = path
        self._check_s = check_s
        self._clock = clock
        self._grid: dict[str, str] = {}
        self._day: dict[tuple[str, str], float] = {}
        self._refreshed: str | None = None
        self._checked: float | None = None
        self._cache: dict[str, dict[str, float]] = {}
        self._lock = threading.Lock()

    def _load(self) -> None:
        p = Path(self._path) if self._path else S.db_path()
        if not p.exists():
            return
        con = sqlite3.connect(p)
        try:
            when = con.execute("SELECT value FROM meta WHERE key='weather_refreshed'").fetchone()
            when = when[0] if when else None
            if when != self._refreshed:
                self._grid = dict(con.execute("SELECT id, grid FROM candidate_grid"))
                self._day = {(g, d): r for g, d, r in con.execute("SELECT grid, date, rain FROM weather_day")}
                self._refreshed = when
                self._cache = {}
        except sqlite3.Error:
            pass   # 표가 아직 없거나 갱신 중 → 지난 값
        finally:
            con.close()

    def rain_map(self, date: str) -> dict[str, float]:
        """후보 id → 그날 비 정도(0이면 넣지 않는다)."""
        with self._lock:
            now = self._clock()
            if self._checked is None or now - self._checked >= self._check_s:
                self._checked = now
                self._load()
            if date not in self._cache:
                self._cache = {date: {cid: r for cid, g in self._grid.items()
                                      if (r := self._day.get((g, date), 0.0)) > 0}}
            return self._cache[date]

    @property
    def refreshed(self) -> str | None:
        return self._refreshed


# ------------------------------------------------------------ CLI
def main(argv: list[str]) -> int:
    cmd = argv[1] if len(argv) > 1 else "stats"
    con = connect()
    if cmd == "refresh":
        env = S.load_env()
        key = env.get("KMA_SERVICE_KEY") or env.get("TOURAPI_SERVICE_KEY", "")
        if not key:
            print("KMA_SERVICE_KEY(또는 TOURAPI_SERVICE_KEY)가 .env에 없다")
            return 2
        res = refresh(con, lambda nx, ny, base: fetch_forecast(key, nx, ny, base))
        print(f"기상청 호출 {res.calls}회(격자 {res.grids}) · 실패 {res.failures} · 날짜 {res.days}일 → {S.db_path()}")
        if res.calls and res.failures == res.calls:
            return 1
    when = con.execute("SELECT value FROM meta WHERE key='weather_refreshed'").fetchone()
    print(f"날씨 갱신 {when[0] if when else '없음'}")
    for d, lv, n in con.execute("SELECT date, rain, COUNT(*) FROM weather_day GROUP BY date, rain ORDER BY date, rain"):
        print(f"  {d} 비 {lv:g}: 격자 {n}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
