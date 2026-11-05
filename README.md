# 차곡 (Chagok) — "지금 차에 든 돈으로, 어디까지"

현대자동차그룹 Pleos Connect 차량용 앱 공모전 출품작. 차 앱(AAOS·WebView) + 추천 서버 모노레포.
일정: 11/7 개발 핸드오프 · 11/10 원티드 4차 시연 · 11/15 1차 제출 · 12/3~4 2차 PT.

## 실행
```
pip install -e "server[dev]"          # 처음 한 번 (Python 3.12+)
make check                            # PR 전에: 서버 pytest + 앱 엔진 node 테스트 + 문구 검사 (= CI)
make dev                              # 서버 http://localhost:8000 (에뮬에서 http://10.0.2.2:8000)
adb forward tcp:8088 tcp:8088 && make replay   # Virtual Console로 PT 6단계 주입
```
`make`가 없으면(윈도우): `cd server && python -m pytest -q`, `node --test "app/shared/test/*.test.js"`, `python scripts/lint_copy.py`.

## 디렉토리
| 경로 | 내용 | 주인 |
|---|---|---|
| `fixtures/` | **기대값의 단일 출처.** `constants.json`(단가·차종·좌표·seed), `candidates_demo.json`, `cards/D*`, `settle/T*`, `ledger/L*`. 생성물이라 손으로 안 고침 | 효성 |
| `contract/` | `openapi.yaml`, `samples/`(생성물), `permissions.md`(권한 신청 목록 = 매니페스트) | 효성·June |
| `server/` | FastAPI. `chagok/engine.py`(수식)·`cards.py`(순위·세션)가 기준 구현. `tests/`는 fixture 대조 + 표기 규칙 불변식 | 효성 |
| `app/` | `android/` = 안드로이드 프로젝트(빌드 환경 + 빈 화면, 앱 기능은 여기에), `shared/chagok-engine.js` = 엔진의 JS 포팅(앱 로컬 계산·오프라인 데모) | June |
| `design/` | `RULES.md`, `tokens.css`, `components/`, `illustrations/<theme>-<n>.svg`, `preview.html` | BX 디자이너 |
| `scripts/` | `gen_fixtures.py`, `replay.sh`, `lint_copy.py`, `check_permissions.py` | 효성 |
| `docs/` | 동결된 PRD·명세서(11/5) | — |
| `DECISIONS.md` | 한 줄 결정 로그 + **열린 결정**(담당·기한) | 모두 |

## 규칙
1. 브랜치는 `develop`(기본)·`main`(릴리스)·`feature/*`·`fix/*`, 둘 다 PR로만 머지(`CONTRIBUTING.md`). `fixtures/`·`contract/`를 건드리면 서버·앱 담당 둘 다 리뷰.
2. 수식·순위 규칙은 **두 벌**(`server/chagok/*.py`, `app/shared/chagok-engine.js`)이고 같은 PR에서 같이 바꾼다. 바꾸면 `make fixtures`로 재생성해 같은 PR에 넣는다. CI가 재생성 diff와 JS 대조를 검사한다.
3. 앱은 잔량·주행가능거리·주행거리·VIN을 서버로 보내지 않는다. 서버엔 `budget` 파생값만(PRD 5-2, 계약이 422로 막음).
4. 화면 문구는 PRD 5-3 표기를 따른다(`make lint`): "0 원" 금지, "빠듯"과 "추가 … 없이"를 같이 쓰지 않기, 글레오 비교·"예상 vs 실제" 금지.
5. 결정은 `DECISIONS.md` 한 줄. 문서 버전은 더 올리지 않는다. 문서와 다르게 가면 이유를 거기 적는다.

## 사람별 시작점
- **안드로이드(June)**: `app/README.md` → 빌드 환경(1절), 서버 호출 방식(3절, 열린 결정 2)
- **서버·엔진(효성)**: `server/chagok/engine.py` 맨 위 포팅 규칙 → `scripts/gen_fixtures.py`
- **디자이너**: `design/RULES.md`를 Claude Code에 읽히고 `design/`에 PR. `design/preview.html`로 세 레이아웃 확인(`cd design && python -m http.server`)
- **PM(명준)**: `DECISIONS.md` 열린 결정 5~11·19
