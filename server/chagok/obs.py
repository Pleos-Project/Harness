"""한 줄 JSON 로그(stdout → docker json-file).

남기지 않는 것(PRD 7-1·CLAUDE.md): 좌표·출발지·장소 id 목록·세션 id·접속 IP·API 키·요청 본문.
남기는 것: 이벤트 이름, 모드, 처리 시간, 개수, 상태 코드, 오류 종류.
"""

from __future__ import annotations

import json
import sys
import time

_ALLOWED = (str, int, float, bool, type(None))


def log(ev: str, **fields) -> None:
    """값은 스칼라만 받는다(리스트·딕셔너리가 실수로 좌표를 싣지 않게)."""
    rec = {"t": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "ev": ev}
    for k, v in fields.items():
        rec[k] = v if isinstance(v, _ALLOWED) else type(v).__name__
    sys.stdout.write(json.dumps(rec, ensure_ascii=False) + "\n")
    sys.stdout.flush()
