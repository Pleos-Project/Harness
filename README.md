# 차곡 (Chagok, 가제) — "지금 차에 든 돈으로, 어디까지"

현대자동차그룹 Pleos Connect 차량용 앱 공모전 출품작. 차 앱(AAOS·Kotlin·Jetpack Compose) + 추천 서버 모노레포.
"차곡"은 가제다. 이름이 바뀌어도 코드 안의 `chagok`·`cg-` 같은 식별자는 변수명처럼 그대로 둔다(DECISIONS 열린 결정 22).
일정: 11/7 개발 핸드오프 · 11/10 원티드 4차 시연 · 11/15 1차 제출 · 12/3~4 2차 PT.

## 실행
```
pip install -e "server[dev]"          # 처음 한 번 (Python 3.12+)
cp .env.example .env                  # 외부 API 키 자리 (TourAPI 등). .env 는 커밋되지 않음
make check                            # PR 전에: 서버 pytest + 문구 검사 (빠름, JDK 없어도 됨)
make check-app                        # 앱·엔진을 바꿨으면: 빌드 + Kotlin 엔진 fixture 대조 (JDK 21)
make dev                              # 서버 http://localhost:8000 (에뮬에서 http://10.0.2.2:8000)
adb forward tcp:8088 tcp:8088 && make replay   # Virtual Console로 PT 6단계 주입
```
`make`가 없으면(윈도우): `cd server && python -m pytest -q`, `python scripts/lint_copy.py`, 앱은 `cd app/android && gradlew.bat assembleDebug test`.

## 디렉토리
주인 = 그 폴더 변경을 책임지고 PR을 리뷰하는 사람. 사람별 전체 역할은 맨 아래 [역할](#역할).

| 경로 | 내용 | 주인 |
|---|---|---|
| `server/` | FastAPI. `chagok/engine.py`(수식)·`cards.py`(순위·세션)가 기준 구현. `tests/`는 fixture 대조 + 표기 규칙 불변식 | 효성 |
| `fixtures/` | **기대값의 단일 출처.** `constants.json`(단가·차종·좌표·seed), `candidates_demo.json`, `cards/D*`, `settle/T*`, `ledger/L*`. 생성물이라 손으로 안 고침 | 효성 (June 리뷰) |
| `contract/openapi.yaml`, `samples/` | 앱 ↔ 서버 계약. `samples/`는 생성물 | 효성 (June 리뷰) |
| `contract/permissions.md` | 권한 신청 목록 = 매니페스트(AC-N-7) | June |
| `app/android/` | 안드로이드 프로젝트(Kotlin). 앱 화면·SDK 연동은 여기에 | June |
| `app/android/app/src/main/java/com/mist/pleos_app/engine/` | Kotlin 엔진(Python 엔진 포팅: 잔고·범위·카드·교체·장부) + `src/test/.../FixturesTest` | 효성(수식) · June(앱 연동) |
| `design/tokens.css`, `components/`, `preview.html`, `RULES.md` | 화면 토큰·컴포넌트 조각·미리보기·디자인 규칙 | 명준 (June 리뷰) |
| `design/illustrations/`, `icons/`, `brand/` | 테마 일러스트 `<theme>-<n>.svg`, 아이콘, 로고·앱 아이콘. 앱이 쓰는 내보낸 파일(SVG·PNG)만, 원본은 레포 밖 | BX 디자이너(제작) · 명준(레포 반영) |
| `scripts/gen_fixtures.py`, `lint_copy.py`, `check_permissions.py` | fixture 생성 · 문구 검사 · 권한 대조 | 효성 |
| `scripts/replay.sh` | Virtual Console PT 6단계 주입 | June |
| `docs/` | 동결된 PRD(명준)·기능 명세서(June), 11/5 기준. 고치지 않음 | — |
| `DECISIONS.md` | 한 줄 결정 로그 + **열린 결정**(담당·기한) | 모두 적고, 명준(PM)이 정리 |

## 규칙
1. 브랜치는 `develop`(기본)·`main`(릴리스)·`feature/*`·`fix/*`, 둘 다 PR로만 머지(`CONTRIBUTING.md`). `fixtures/`·`contract/`를 건드리면 효성·June 둘 다 리뷰.
2. 수식·순위 규칙은 **두 벌**(`server/chagok/*.py`, Kotlin `engine/`)이고 같은 PR에서 같이 바꾼다. 바꾸면 `make fixtures`로 재생성해 같은 PR에 넣는다. CI가 재생성 diff와 Kotlin 대조(`FixturesTest`)를 검사한다.
3. 앱은 잔량·주행가능거리·주행거리·VIN을 서버로 보내지 않는다. 서버엔 `budget` 파생값만(PRD 5-2, 계약이 422로 막음).
4. 화면 문구는 PRD 5-3 표기를 따른다(`make lint`): "0 원" 금지, "빠듯"과 "추가 … 없이"를 같이 쓰지 않기, 글레오 비교·"예상 vs 실제" 금지.
5. 결정은 `DECISIONS.md` 한 줄. 문서 버전은 더 올리지 않는다. 문서와 다르게 가면 이유를 거기 적는다.

## 역할
5명 고정(PRD 12장). 레포 밖 산출물까지 같이 적는다.

| # | 역할 | 사람 | GitHub | 레포에서 맡는 것 | 레포 밖 산출물 |
|---|---|---|---|---|---|
| 1 | 백엔드 · AI · 서버 | 효성 | @hyos0415 | `server/`, `fixtures/`, `contract/` 계약, Kotlin `engine/` 수식, `scripts/` 생성·검사 도구 | 점수 함수·이유 한 줄, 어림 추정기, 정산 수식, 블라인드 검수(11/11), PT 기준 데모 데이터, 기술지원 질문 |
| 2 | 안드로이드 | June(준) | @june0103 | `app/android/`(화면·SDK 연동·엔진 연결), `contract/permissions.md`, `scripts/replay.sh` | 시연 빌드, Virtual Console 시나리오, 글레오 캡처(11/7) |
| 3 | UI·UX 디자인 · 기획(PM) | 명준 | @thvlou | `design/` 토큰·컴포넌트·규칙, BX 파일 레포 반영, `DECISIONS.md` 정리, 열린 결정 중 기획 항목 | 화면 확정본, PT 6단계 스크립트, 인터뷰, 기획서 |
| 4 | 영상 디자인 | — | @EditBin | 레포 작업은 거의 없음. PT 장면은 `scripts/replay.sh pt`로 똑같이 재현해 촬영 | 시연 영상, 숏폼 3편, 주행 중·폰 공유 목업 |
| 5 | BX 디자인 | — | 없음(가입하면 추가) | 직접 작업 없음. 내보낸 파일을 피그마·드라이브로 넘기면 명준이 `design/illustrations/`·`icons/`·`brand/`에 올리고, 커밋에 `Co-authored-by:`로 BX를 함께 적는다 | 로고, 테마 일러스트(8 × 3~4), 아이콘, 앞면·스택 카드 비주얼, 기록 지도, 키 컬러 |

**시작점**
- **효성**: `server/chagok/engine.py` 맨 위 포팅 규칙 → Kotlin `engine/Engine.kt` → `scripts/gen_fixtures.py` → `DECISIONS.md` 열린 결정 3·4·14·15·17
- **June**: `app/README.md` → 빌드 환경(1절), 서버 호출 방식(3절) → 열린 결정 1·2·4·16·20·21
- **명준**: `DECISIONS.md` 열린 결정 1·5~12·16·19 → `design/RULES.md`(Claude Code에 읽히고 `design/`에 PR, `cd design && python -m http.server`로 `preview.html` 확인)
- **영상**: `scripts/replay.sh pt` 단계별 기대 화면, 화면 구석 "시연용 값" 표기(AC-C-7) 유지
- **BX**: `design/RULES.md` 3절의 파일 이름·크기 규칙대로 내보내 명준에게 전달 → 열린 결정 12·13
