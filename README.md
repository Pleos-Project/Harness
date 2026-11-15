# NOGO(노고)

함께 탄 여행이 끝나면 차가 운전자의 운전 노고를 세어 보여 주고, 동승자가 운전자를 "챙기겠다"고 선택하면 얼마쯤이 그 노고에 알맞은지에 대한 기준을 제안하는 차량용 앱입니다.

현대자동차그룹 Pleos Connect 차량용 앱 공모전 출품작 · 팀 NOGO · 차 앱(AAOS · Kotlin · Jetpack Compose) + 보조 서버

## APK

- 위치: [`apk/NOGO_NOGO_release.apk`](apk/NOGO_NOGO_release.apk) (서명된 release 빌드, 약 28MB)
- 패키지: `com.mist.pleos_app` · 버전 0.1.0

## 개발·테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | **Pleos Connect v2.1.0** 시스템 이미지(`android-34/pleos_car_emulator/x86_64`, Android 14 · API 34, Automotive) |
| 화면 규격 | **2560 × 1440**, 160dpi (AVD 기기 `Pleos_Connect_v2_0`) |
| Android Emulator | 36.6.11.0 |
| Pleos SDK | Vehicle 2.3.2 · NaviHelper 3.0.5 |
| 빌드 | AGP 9.3.3 · Gradle 9.5.0 · JDK 21 · compileSdk 37 · targetSdk 36 · minSdk 28 |
| 차량값 조작 | Pleos Virtual Console(기어 · 좌석 착석) |

## 실행 방법

### 1. 설치
Pleos Connect v2.1.0 에뮬레이터를 켠 뒤:
```bash
adb install -r -g apk/NOGO_NOGO_release.apk
```
`-g`는 권한(위치 · 차량 정보 · 알림 등)을 설치할 때 미리 줍니다. 앱 목록에서 **NOGO** 아이콘을 눌러 엽니다.

### 2. 시연 고정 모드 (서버 · 네트워크 없이 한 바퀴)
설치하면 **시연 고정 모드가 켜진 채로** 시작합니다. 이 모드에서는 서버를 부르지 않고, 경로 · 날씨 · 출발 · 도착 시각을 앱에 들어 있는 시연용 값(태안 꽃지해수욕장, 16:00 → 18:10, 비, 정체 경로의 36%, 4명)으로 고정합니다. 그래서 인터넷이나 서버 없이도 처음부터 끝까지 돌릴 수 있습니다.
- 켜고 끄기: 앱 오른쪽 위 **설정** → **시연 고정 모드** 스위치. 스위치 설명에 "태안 꽃지해수욕장 · 1.57배"가 보이면 켜진 상태입니다.
- 끄면 실제 내비 경로와 날씨 서버(`https://api.rangeleft.com`)를 씁니다.

### 3. 한 바퀴 돌려 보기 (시연 고정 모드)
1. 홈 **[출발하기]** → 인원 확인. 인원은 좌석 착석 센서로 셉니다. Virtual Console 없이 열면 1명으로 나오니 **[+]로 4명**을 맞추고 **[확인]**(1명이면 제안 없이 영수증만 보여 줍니다).
2. 최근 목적지 **태안 꽃지해수욕장** → 미리보기(1시간 45분 · 104km · 정체 경로의 36%) → **[안내 시작]**.
3. 홈이 "태안 꽃지해수욕장으로 가는 중"으로 바뀝니다. (선택) Virtual Console에서 기어 D로 바꾸면 주행 중에는 앱이 화면을 쓰지 않습니다. 다시 P로.
4. **[도착했어요]** → 도착 화면(오늘 운전 2시간 10분 · 4명, 날씨 · 정체 구간 · 예상보다 더 · 밤길 · 장시간을 하나씩 셈) → **[운전자 챙겨주기]** → "중간에 쉰 시간이 있었나요?" **[없음]** → 영수증(줄별 +40% · +10% · +5% · +2% · +1%, 합계 약 3만 6천 원)과 **한 사람 약 9천 원** → **[좋아요]** → 기록.

위 순서는 이 저장소의 `apk/NOGO_NOGO_release.apk`를 Pleos Connect v2.1.0 에뮬레이터에 설치해 확인했습니다(11/15).

### 4. 소스에서 빌드
```bash
cd app/android
./gradlew assembleDebug test      # 윈도우는 gradlew.bat. JDK 21 + Android SDK 필요
```
release 서명은 `app/android/keystore.properties`(커밋하지 않음)가 있을 때만 붙습니다. 보조 서버(날씨 · 목적지 검색)는 아래 개발 안내의 `make dev`와 `deploy/README.md`를 봅니다.

---

# 팀 개발 안내

아래는 팀 내부 작업용 안내입니다. 기여 규칙은 `CONTRIBUTING.md`에 있습니다.

**11/7 운전 노고 정산으로 전면 피봇했다(#46).**
- 동승 주행이 끝나면 도착 확인 알림으로 열고, "오늘 운전 돌아보기" 화면에서 오늘 운전의 조건을 하나씩 세어 보여 준다.
- 같이 탄 사람이 [운전자 챙겨주기]를 누르면 영수증(운전 시간과 "평소보다 ○배 힘든 길")과 금액("한 사람 약 ○천 원", 방식 A)을 보여 준다. 한 번 빼 주기(방식 B)는 1차 화면에서 숨겼다.
- 기준 문서는 `docs/PRD_노고정산_v2.0.md`·`docs/기능명세서_v0.6.md`이고, 수식 근거는 #47에 있다. 결정이 나면 이 두 문서를 같이 고친다(DECISIONS 11/14).
- 아래 설명 중 잔고·카드·장부(`engine.py`·`cards.py`·fixture D·L·T)는 피봇 전 흐름이고, 정리하기 전까지 남아 있다.
제품 이름은 노고다(11/13 확정). 코드 안의 `chagok`·`cg-` 같은 식별자는 변수명처럼 그대로 둔다(DECISIONS 11/14 '제품 이름').
일정: 11/7 개발 핸드오프 · 11/10 원티드 4차 시연 · 11/15 1차 제출 · 12/3~4 2차 PT.

## 실행
```
pip install -e "server[dev]"          # 처음 한 번 (Python 3.12+)
cp .env.example .env                  # 외부 API 키 자리 (TourAPI 등). .env 는 커밋되지 않음
make check                            # PR 전에: 서버 pytest + 문구 검사 (빠름, JDK 없어도 됨)
make check-app                        # 앱·엔진을 바꿨으면: 빌드 + Kotlin 엔진 fixture 대조 (JDK 21)
make dev                              # 서버 http://localhost:8000 (에뮬에서 http://10.0.2.2:8000). 배포는 deploy/README.md
adb forward tcp:8088 tcp:8088 && make replay   # Virtual Console로 PT 6단계 주입(피봇 전 흐름. 노고 시연용은 앱 작업 뒤 갱신)
```
`make`가 없으면(윈도우): `cd server && python -m pytest -q`, `python scripts/lint_copy.py`, 앱은 `cd app/android && gradlew.bat assembleDebug test`.

## 디렉토리
주인 = 그 폴더 변경을 책임지고 PR을 리뷰하는 사람. 사람별 전체 역할은 맨 아래 [역할](#역할).

| 경로 | 내용 | 주인 |
|---|---|---|
| `server/` | FastAPI. `chagok/engine.py`(수식)·`cards.py`(순위·세션)가 기준 구현. `tests/`는 fixture 대조 + 표기 규칙 불변식 | 효성 |
| `deploy/` | 서버 배포(Lightsail + Docker + Caddy HTTPS + 크론). `deploy/README.md` | 효성 |
| `fixtures/` | **기대값의 단일 출처.** `constants.json`(단가·차종·좌표·seed), `candidates_demo.json`, `cards/D*`, `settle/T*`, `ledger/L*`. 생성물이라 손으로 안 고침 | 효성 (June 리뷰) |
| `contract/openapi.yaml`, `samples/` | 앱 ↔ 서버 계약. `samples/`는 생성물 | 효성 (June 리뷰) |
| `contract/permissions.md` | 권한 신청 목록 = 매니페스트(AC-N-7) | June |
| `app/android/` | 안드로이드 프로젝트(Kotlin). 앱 화면·SDK 연동은 여기에 | June |
| `app/android/app/src/main/java/com/mist/pleos_app/engine/` | Kotlin 엔진(Python 엔진 포팅: 잔고·범위·카드·교체·장부) + `src/test/.../FixturesTest` | 효성(수식) · June(앱 연동) |
| `design/tokens.css`, `components/`, `preview.html`, `RULES.md` | 화면 토큰·컴포넌트 조각·미리보기·디자인 규칙 | 명준 (June 리뷰) |
| `design/illustrations/`, `icons/`, `brand/` | 테마 일러스트 `<theme>-<n>.svg`, 아이콘, 로고·앱 아이콘. 앱이 쓰는 내보낸 파일(SVG·PNG)만, 원본은 레포 밖 | BX 디자이너(제작) · 명준(레포 반영) |
| `scripts/gen_fixtures.py`, `lint_copy.py`, `check_permissions.py` | fixture 생성 · 문구 검사 · 권한 대조 | 효성 |
| `scripts/replay.sh` | Virtual Console PT 6단계 주입 | June |
| `docs/` | 기준 문서: 노고 정산 PRD v2.0(명준·효성), 기능 명세서 v0.6(효성). 결정이 나면 같이 고친다(DECISIONS 11/14). 이전 판은 `docs/archive/` | — |
| `DECISIONS.md` | 한 줄 결정 로그 + **열린 결정**(담당·기한) | 모두 적고, 명준(PM)이 정리 |

## 규칙
1. 브랜치는 `develop`(기본)·`main`(릴리스)·`feature/*`·`fix/*`, 둘 다 PR로만 머지(`CONTRIBUTING.md`). `fixtures/`·`contract/`를 건드리면 효성·June 둘 다 리뷰.
2. 수식·순위 규칙은 **두 벌**(`server/chagok/*.py`, Kotlin `engine/`)이고 같은 PR에서 같이 바꾼다. 바꾸면 `make fixtures`로 재생성해 같은 PR에 넣는다. CI가 재생성 diff와 Kotlin 대조(`FixturesTest`)를 검사한다.
3. 앱은 잔량·주행거리·VIN·위치 이력을 서버로 보내지 않는다. 날씨 조회 지점 좌표만 보내고, 서버는 저장·로그하지 않는다(명세 v0.5 AC-N-4).
4. 화면 문구는 명세 v0.5 2절을 따른다(`make lint`).
   - 금액 화면 문구는 노고 PRD v2.0 7-7(큰 숫자 "한 사람 약 ○천 원" + 근거, 머리 줄 없음 · DECISIONS 11/14)을 따른다. "고마움의 기준"·"돈으로 치면"·"적당해요"도 쓰지 않는다.
   - 운송 대가 표현(운임·대가·요금·청구), "○시간어치", 순위, 운전 실력 평가, "아꼈어요", 재촉, "0 원", 쉼표 금액은 쓰지 않는다.
5. 결정은 `DECISIONS.md` 한 줄. 문서 버전은 더 올리지 않는다. 문서와 다르게 가면 이유를 거기 적는다.

## 역할
5명 고정(PRD 12장). 레포 밖 산출물까지 같이 적는다.

| # | 역할 | 사람 | GitHub | 레포에서 맡는 것 | 레포 밖 산출물 |
|---|---|---|---|---|---|
| 1 | 백엔드 · AI · 서버 | 효성 | @hyos0415 | `server/`, `fixtures/`, `contract/` 계약, Kotlin `engine/` 수식, `scripts/` 생성·검사 도구 | 점수 함수·이유 한 줄, 어림 추정기, 정산 수식, 블라인드 검수(11/11), PT 기준 데모 데이터, 기술지원 질문 |
| 2 | 안드로이드 | June(준) | @june0103 | `app/android/`(화면·SDK 연동·엔진 연결), `contract/permissions.md`, `scripts/replay.sh` | 시연 빌드, Virtual Console 시나리오, 글레오 캡처(11/7) |
| 3 | UI·UX 디자인 · 기획(PM) | 명준 | @thvlou | `design/` 토큰·컴포넌트·규칙, BX 파일 레포 반영, `DECISIONS.md` 정리, 열린 결정 중 기획 항목 | 화면 확정본, PT 6단계 스크립트, 인터뷰, 기획서 |
| 4 | 영상 디자인 | — | @EditBin | 레포 작업은 거의 없음. 시연 장면은 명세 v0.6 12절 순서를 따름(`scripts/replay.sh pt`는 피봇 전 흐름이라 쓰지 않음, 노고 화면이 나오기 전까지는 목업) | 시연 영상, 숏폼 3편, 주행 중·폰 공유 목업 |
| 5 | BX 디자인 | — | 없음(가입하면 추가) | 직접 작업 없음. 내보낸 파일을 피그마·드라이브로 넘기면 명준이 `design/illustrations/`·`icons/`·`brand/`에 올리고, 커밋에 `Co-authored-by:`로 BX를 함께 적는다 | 로고, 테마 일러스트(8 × 3~4), 아이콘, 앞면·스택 카드 비주얼, 기록 지도, 키 컬러 |

**시작점**
- **효성**: `docs/기능명세서_v0.6.md` 7~9절 → 노고 엔진 `server/chagok/effort.py` ↔ Kotlin `engine/Effort.kt`(#52) → 12절 날씨 서버(#53) → `DECISIONS.md` 열린 결정 24
- **June**: `app/README.md` → `docs/기능명세서_v0.6.md` 4~6절(F20~F22·F29)·11절 신호 표 → 열린 결정 1·20·21
- **명준**: `docs/PRD_노고정산_v2.0.md` 7장 → `DECISIONS.md` 열린 결정 19 → `design/RULES.md`(Claude Code에 읽히고 `design/`에 PR, `cd design && python -m http.server`로 `preview.html` 확인)
- **영상**: `docs/기능명세서_v0.6.md` 12절 시연 순서(노고 화면이 나오기 전까지는 목업). 시연 고정 모드는 설정에서 켠다(AC-C-7)
- **BX**: `design/RULES.md` 3절의 파일 이름·크기 규칙대로 내보내 명준에게 전달 → 열린 결정 12·13
