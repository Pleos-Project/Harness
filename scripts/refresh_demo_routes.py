"""데모 후보(fixtures/candidates_demo.json)의 편도 거리·시간·통행료를 실제 길찾기 값으로 갱신한다.

출발지는 fixtures/constants.json 의 origin(PT 에뮬레이터 위치). 카카오모빌리티 길찾기(자동차, RECOMMEND)를 쓴다.
키는 .env 의 KAKAO_REST_API_KEY (화면·파일에 찍지 않는다).

    python scripts/refresh_demo_routes.py          # 갱신 후 make fixtures 로 fixture 재생성
    python scripts/refresh_demo_routes.py --dry    # 바뀔 값만 보기

시간은 조회 시점 교통 상황이 반영된 값이라 다시 돌리면 몇 분씩 달라질 수 있다. 바뀌면 PT 콘솔 값(DECISIONS)도 확인한다.

길찾기 호출·변환은 서버 실서비스 모드와 같은 `chagok.routes.fetch_kakao` 를 쓴다
(route_km = round1(m/1000), minutes = round_half_up(s/60) — 내장 round() 금지, 데모 값과 실경로 값이 같은 규칙).
"""

from __future__ import annotations

import datetime as dt
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CANDS = ROOT / "fixtures/candidates_demo.json"
CONST = ROOT / "fixtures/constants.json"

sys.path.insert(0, str(ROOT / "server"))
from chagok import routes as R  # noqa: E402


def load_env() -> dict[str, str]:
    env = dict(os.environ)
    f = ROOT / ".env"
    if f.exists():
        for line in f.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env.setdefault(k.strip(), v.strip().strip('"').strip("'"))
    return env


def route(key: str, origin: dict, c: dict) -> tuple[float, int, int]:
    try:
        r = R.fetch_kakao(key, origin["lat"], origin["lng"], c["lat"], c["lng"], timeout=20)
    except R.RouteError as e:  # 메시지에서 키·출발지 좌표는 fetch_kakao 가 이미 지움
        raise SystemExit(f"{c['name']}: {e}") from None
    return r.route_km, r.minutes, r.toll_krw


def main() -> int:
    key = load_env().get("KAKAO_REST_API_KEY", "")
    if not key:
        print("KAKAO_REST_API_KEY 가 .env 에 없다 (.env.example 참고)")
        return 2
    dry = "--dry" in sys.argv
    origin = json.loads(CONST.read_text(encoding="utf-8"))["origin"]
    data = json.loads(CANDS.read_text(encoding="utf-8"))
    print(f"{'후보':<14} {'km':>11} {'분':>9} {'통행료(편도)':>14}")
    for c in data["candidates"]:
        km, minutes, toll = route(key, origin, c)
        print(f"{c['name'][:12]:<14} {c['route_km']:>5}→{km:<5} {c['minutes']:>4}→{minutes:<4} {c['toll_krw']:>6}→{toll:<6}")
        c.update(route_km=km, minutes=minutes, toll_krw=toll)
    today = dt.date.today().isoformat()
    data["_doc"] = ("데모 고정 모드 후보 풀. 편도 도로 거리(route_km)·시간(minutes)·통행료(toll_krw, 편도)는 "
                    f"constants.origin(성남 수정구 시흥동) 출발 카카오모빌리티 길찾기(자동차·추천 경로) {today} 조회값 — "
                    "scripts/refresh_demo_routes.py. 주차비는 근사값. 서버 TourAPI 캐시가 붙으면 이 파일은 fallback(앱 내장 후보)으로만 쓴다.")
    if dry:
        print("\n--dry: 파일은 바꾸지 않음")
        return 0
    CANDS.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"\n갱신: {CANDS.relative_to(ROOT).as_posix()} → 이어서 make fixtures")
    return 0


if __name__ == "__main__":
    sys.exit(main())
