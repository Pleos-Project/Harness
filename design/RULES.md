# 차곡 디자인 규칙 (코드로 넘길 때)

디자이너가 Claude Code(아티팩트)로 HTML·CSS·SVG 시안을 만들어 `design/`에 PR 한다. 이 파일을 Claude Code에 먼저 읽힌다.
앱은 Jetpack Compose다. 여기 시안은 앱에 그대로 들어가지 않고, 머지되면 자동 반영 PR(5절)이 Compose 화면을 시안대로 고친다.

| 영역 | 담당 |
|---|---|
| 화면 시안(`screens/`)·토큰·컴포넌트 조각(`components/`)·미리보기·이 규칙 | UI·UX 디자이너 명준 |
| 테마 일러스트(`illustrations/`)·아이콘(`icons/`)·로고·앱 아이콘(`brand/`)·키 컬러 | 제작 BX 디자이너, 레포 반영 명준(3절 "BX 파일 올리기") |
| 반영 PR 리뷰(Compose 연결·동작) | June |

## 0. 화면의 기준은 design/ (11/6 명준 결정)
- 화면에 보이는 것(배치·부품·문구 자리·상태별 모양·인터랙션)의 기준은 `design/`이다. 개발이 PRD를 보고 먼저 만든 화면은 임시 구현이고, 디자인 PR이 머지되면 자동 반영 PR이 그 화면을 시안대로 고친다.
- 개발 쪽이 정하는 것: 상태(ViewModel)·화면 스택 이동·엔진 연결·주행 중 가림·리스너 수명·권한. 반영 PR은 이 연결을 그대로 두고 보이는 부분만 시안에 맞춘다.
- 시안에 없는 앱 요소가 기능(AC 번호가 있는 것)이면 반영 PR이 지우지 않고 "확인 필요"로 남긴다. 디자이너가 다음 디자인 PR에서 자리를 정한다.
- 화면 시안은 `design/screens/<화면>.html`(+ 같은 이름 `.css`), 상태가 여럿이면 한 파일 안에 `data-state="기본|정산 닫음|…"` 블록으로 나눈다. 부품은 `design/components/`.

### 시안 ↔ 앱 파일 대응표 (반영 PR이 고칠 곳) — Design v2(운전 노고 정산, 11/8 · 11/14 v170에서 앱 파일 이름 최신화)
| 시안 | 앱(Compose) | 비고 |
|---|---|---|
| `screens/home.html` | `ui/EffortHomeV2.kt` | 시작 화면 · 기본 · 방금 끝난 기록 · 알림 펼침. 위 바 = 기록 · 워드마크 · 알림 · 설정. 로고 · 워드마크 v2(11/14) |
| `screens/midway.html` | `ui/EffortMidway.kt` + `ui/EffortHomeV2.kt` 가는 중 상태 | 휴게소에서 다시 연 홈(시동 버튼 · [도착했어요] · [멈추기]) · 멈추기 확인 · 쉰 시간 묻기 · 지금까지의 노고(방식 A) · 정산 뒤 |
| `screens/depart.html` | `ui/EffortDepartV2.kt`, `ui/PayerToast.kt` | 목적지 카드 4장 + 미리보기 칸([안내 시작]). 인원 확인은 위 바 줄 알림 바 |
| `screens/search.html` | `ui/EffortDepartV2.kt` 검색 상태 | 결과 · 검색 중 · 결과 없음 · 안 됨 · 위치 못 받음 |
| `screens/drive.html` | 그리지 않음(순정 내비 · 시스템 차단 `DrivingCover`) | 참고용 |
| `screens/review.html` | `ui/EffortReview.kt` · 주행 중 서비스의 도착 확인 알림 | 도착 확인 알림(시스템 알림 목업, 두 안) → 오늘 운전 돌아보기(캐릭터 + 기준 5줄, 약 10초, 건너뛰기) → [운전자 챙겨주기] · [다음에]. 움직임은 `HANDOFF_v4.md` 6절. 옛 `calc.html`(계산 연출)은 11/14에 지움 |
| `screens/arrive.html` | `ui/EffortArrive.kt` | 왼쪽 영수증 · 오른쪽 금액 카드(가로) / 위·아래(분할). 쉰 시간은 짧은 하단 시트 |
| `screens/record.html` | `ui/EffortRecord.kt`. `ui/RecordScreen.kt`는 피봇 전 장부 화면이라 대응 안 함 | 주행 기록 목록 → 기록 상세 |
| `screens/settings.html` | `ui/SettingsScreen.kt` `[June 확인]` | 시연 고정 · 단가 출처 · 기록 초기화(S20). 제안 방식 줄은 11/14에 뺌 |
| `components/<부품>.html` | `ui/components/CgParts.kt` `Cg*` | 버튼·칩·카드·시트 등. 화면마다 따로 만들지 않는다 |
| 모든 시안의 화면 문구 | `res/values/strings.xml` | 문구는 여기서만 고친다. 임시 문구는 디자인이 확정 전(PR 본문에 목록) |
- 피봇 전 시안 대응(`home`·`card`·`range` 등 옛 이름)은 쓰지 않는다. `components/badge`·`card-front`는 피봇 전 부품으로 이력만 남긴다.
- 대응표에 없는 시안이 오면 반영 PR이 가장 가까운 파일을 고르고 PR 본문에 적는다. 화면 파일 이름이 바뀌면 이 표를 같이 고친다(개발 PR에서).

## 1. 값은 전부 tokens.css 변수로 — 기준은 Pleos Connect UI Design Guide
- 색·글자·간격은 `design/tokens.css`의 `--cg-*` 변수만. 하드코딩 금지.
- 기준은 [Pleos Connect UI Design Guide](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro)의 Foundations(Colors·Typography)와 Basic button. 지금 값은 H_Mate(June)가 가이드에서 옮긴 것이고, 확정은 명준이 한다(키 컬러는 BX와 함께).
- **Design v2 화면 크기:** `screens/`·`components/`의 배치 크기(폭·높이·간격·모서리)는 Connect-S 실제 픽셀(2560 × 1440, 1dp = 1px)로 적는다. 색과 글자는 반드시 `--cg-*` 토큰. 앱 창은 가로 1700 × 1184, 분할 840 × 1184.
- **크기 단위:** 가이드 크기는 디스플레이 실제 픽셀이다 → `calc(가이드값 * var(--u))`. 글자는 `font: var(--cg-body-s)`처럼 타이포 토큰을 통째로 쓴다(Headline 60/56/48 · Title 40/36/32 · Body 30/28/26 · Label 24/20). 터치 영역(48)처럼 dp 기준 값만 그냥 px. CSS에는 `sp`가 없다.
- **주간/야간:** 기본은 Dark, 시스템이 Light면 Light(`prefers-color-scheme`). 앱이 따로 할 일은 없다. `preview.html`의 버튼만 `data-theme`로 강제한다.
- **대비 4.5:1**은 주간·야간 둘 다 계산해서 확인한다. 가이드 informative 색(초록·파랑·빨강)은 주간 바탕 위 글자색으로 쓰면 미달이라 배지는 색 바탕 + `--cg-badge-*-text`로 쓴다.
- 글꼴은 Asta Sans(Pleos 시스템 UI 폰트). 폰트 파일은 넣지 않고 글꼴 목록 맨 앞에만 둔다(없으면 시스템 sans). 가장 작은 Label S(20)도 화면에서 12sp 이상.

## 2. 컴포넌트 = HTML 조각 1개 + 같은 이름 CSS 1개
- `design/components/<name>.html` + `<name>.css`. JS 없음, 외부 리소스 없음.
- 클래스 접두사 `cg-`, 변형은 `cg-badge--zero`, `cg-badge--tight`, `cg-badge--extra`.
- 문구는 데이터가 채운다. 조각 안의 글자는 샘플이고 출처를 단다:
  - `data-field="display.badge_text"` — 서버 응답 Card 필드(contract/openapi.yaml) 경로
  - `data-derived="theme_ko"` — 앱이 만드는 값: `theme_ko`(THEME_KO), `minutes_text`("1시간 10분"), `swaps_left`, `stack_left`, `illustration`, `balance_text`·정산·월 합계 문구(앱 로컬 계산)
- 샘플 문구도 PRD 5-3 표기를 지킨다(`python scripts/lint_copy.py`가 검사): "0 원"·"7,920원" 금지, "빠듯"과 "추가 … 없이"를 같이 쓰지 않기, 글레오 비교 금지.

### 조각 목록 (Design v2, 11/8)
| 조각 | 쓰는 곳 | Pleos 대응 |
|---|---|---|
| `button` · `icon-button` | 모든 화면 | Basic Button M 80 · L 100 · S 64 / Icon Button 80 · 64 |
| `cta` | 홈 출발하기 · 미리보기 출발 | Basic Button L 채움 변형(알약 + 화살표 원) |
| `text-button` | 인원 알림 확인 · 닫기 · 다시 시도 | 디자인 제안(면 없음) |
| `chip` | 쉰 시간 4택 | Toggle Button M 80 |
| `stepper` | 인원 | Stepper 420 × 100 |
| `title-bar` | 모든 화면 위 | Title Main · Depth 80 |
| `pill` | 인원 · 진행 중 | 디자인 제안 |
| `search-field` | 출발 · 검색 | Text Field 100 알약 변형 |
| `dest-card` · `record-card` · `preview-panel` | 출발 · 홈 | Card |
| `people-alert` | 출발 화면 인원 확인 | System Notification 한 줄 변형 |
| `bottom-sheet` | 쉰 시간 · 진행 중 출발 정보 | Bottom Sheet(딤 · 끌기 없음) |
| `receipt` · `offer-card` | 도착 · 중간 정산(금액 카드 = 방식 A만, 머리 줄 없음 · 11/14) | 디자인 제안 · Card + Basic Button L |
| `loading` · `empty` · `notice` | 로딩 · 빈 상태 · 에러 · 시연 표시 | — |
| `mid-button` | 가는 중 홈 시동 버튼(중간 정산) | 디자인 제안 |
| `logo` · `nogo-car` | 시작 화면 · 위 바 · 홈 심볼(로고 v2 · 11/14) · 노고 카(옛 계산 연출, 지금은 디자인 시스템에만) | 디자인 제안(`brand/`) |
| `noti-panel` | 홈 위 바 알림 종 · 종 아래로 내려오는 알림 창(11/9) | 디자인 제안(페이지 아님, 확인만) |
| `sys-noti` | 도착 확인 알림(11/14) · 앱 창 밖 화면 맨 위, OS가 그림 · 두 안(본문 두 줄 · 한 줄) | Pleos System Notification Image+Text 700 |
| ~~`segmented`~~ | 설정 제안 방식(A/B) 줄 삭제(11/14) · 이력만 | — |
| ~~`record-card`~~ | 홈에서 삭제(11/9 v87) · 이력만 | — |
- 움직임: 등장 움직임 규칙은 `.cg-play` 아래에 있다(시안 정지 화면은 끝 상태). 계산 연출처럼 JS로 차례를 짜는 움직임은 `prototype/`과 `HANDOFF_v2.md`가 기준.

## 3. 일러스트·아이콘·브랜드
- 테마 8개 × 변형 3~4장(PRD 7-5). 파일명 `design/illustrations/<theme>-<n>.svg`, `<theme>` = 서버 `theme` 값:
  `sea lake river mountain park city island drive`, `<n>` = 1부터.
- 같은 장소는 늘 같은 그림: 앱이 장소 ID로 변형을 고른다(Kotlin `illustrationName`). 변형 수를 바꾸면 PR에 적는다.
- 실제 장소 사진인 척하지 않는다. 아이콘은 48dp 그리드 SVG, `design/icons/<name>.svg`(Pleos 에셋 = `icons/pleos/`, 그 밖 = Phosphor Bold `icons/phosphor/`, MIT). 로고·앱 아이콘(512px PNG)은 `design/brand/`. 장소 사진(출발 미리보기 배경, 임시)은 `design/places/<key>.jpg` + `CREDITS.md`.
- 노고 캐릭터(오늘 운전 돌아보기, 11/14)는 `design/illustrations/character/nogo_v_<표정>.webp`(700 × 754, 표정 focus · shy · relief · proud · joy). BX 'NOGO 캐릭터 모션' 시안에서 내보낸 것이고, BX가 상황별 모션 에셋으로 다시 넘기면 같은 이름 규칙으로 바꾼다.
- 레포에는 **앱이 쓰는 내보낸 파일(SVG·PNG)만** 둔다. 원본(피그마·AI·PSD)은 피그마·드라이브에.

### BX 파일 올리기
BX 디자이너는 GitHub 작업을 하지 않아도 된다. 내보낸 파일을 피그마·드라이브로 넘기면 명준이 위 이름 규칙대로 바꿔 PR로 올린다.
그 커밋에는 BX 디자이너를 공동 작성자로 적는다 — 깃 이력에 참여가 남고, 나중에 그 이메일로 GitHub에 가입하면 지난 커밋까지 BX 기여로 연결된다.

```
design: 테마 일러스트 1차(바다·강·산)

Co-authored-by: <BX 디자이너 이름> <GitHub에 쓸 이메일>
```

## 4. 차량 제약 (심사 기준)
- 터치 영역 48px 이상, 글자 최소 12sp, 대비 4.5:1(주간·야간 둘 다).
- Window Policy: 900px 이하(1:1 분할·세로 창)에서는 한 단으로 쌓는다(H_Mate 실측 기준).
- 정차 화면만 있다(1차). 카드 뒤집기 전환은 1초 안. 자동 스크롤 없음.
- 레이아웃은 앱 1개(1700 × 1184)와 분할(840 × 1184) 두 가지를 상태마다 함께 그린다(`screens/*.html`). `design/preview.html`에서 확인하고 PR.

## 5. 넘기는 방법
- PR 제목 `design: <무엇>`. 리뷰는 June(연동). 시안 자체는 디자이너가 정하므로 리뷰는 Compose로 옮길 수 있는지·빠진 기능이 없는지를 본다.
- PR 본문의 **"앱 반영 요청"** 절을 채운다(바뀐 조각·토큰, 새 `data-field`·`data-derived`, 앱에서 할 일).
- **첫 시안은 개발단이 직접 입힌다(11/6 명준 결정).** 디자이너가 미드파이 이상으로 전체 화면을 만든 첫 PR(`design/screens/` 전체)에는 **`수동 반영` 라벨**을 단다. 이 라벨이 있으면 자동 반영이 돌지 않고, June이 시안을 보고 앱 화면에 직접 입힌다(0절 대응표 기준). 그 뒤 작은 수정 PR부터 아래 자동 반영을 쓴다. 화면 전체를 다시 그리는 큰 개편도 같은 라벨로 수동 처리할 수 있다.
- **자동 반영:** `design/`을 바꾼 PR이 `develop`에 머지되면 `.github/workflows/design-handoff.yml`이 돈다. Claude Code가 그 변경과 "앱 반영 요청" 절을 읽고 `app/android`(Compose)에 반영해 `feature/design-apply-pr-<번호>` PR을 연다. 리뷰어는 June 한 명이고(11/6 명준 결정, 앱만 바꾼 PR이지만 리뷰어 규칙의 예외), 본문 `## @june0103 확인 부탁` 절에 확인할 것을 적는다. 자동 머지는 하지 않는다.
  - **화면 구조만** 맡는다. `design/screens/`·`design/components/`·`design/preview.html`이 바뀐 PR에서만 돈다(`*.md`·토큰만 바뀐 PR은 돌지 않음, 일러스트는 방식이 정해지면 추가). 토큰(`tokens.json`·`tokens.css`)과 앱 테마(`ui/theme/`)는 토큰 생성 스크립트가 맡고, 반영 PR은 테마를 고치지 않는다. 생성 스크립트가 생기기 전까지 테마 반영은 June이 한다.
  - **반영 PR이 건드리지 않는 것:** 엔진(`engine/` 패키지, Python과 두 벌로 같은 fixtures 통과)·`src/main/assets/`·`AndroidManifest.xml`(권한)·앱 테마(`ui/theme/`), 엔진 호출 결과 문구(잔고·카드 비용·정산·월 합계 — 엔진 값을 그대로 쓰고 화면에서 따로 계산하지 않음), 주행 중 가림 화면(`DrivingCover`). CI 금지 경로 검사에도 같은 경로가 들어 있다.
  - Claude가 쓸 수 있는 명령은 좁혀 두었다: 머지(`gh pr merge`)·develop 푸시는 못 하고, 푸시는 그 반영 브랜치 하나만.
  - 금지 경로(design/·테마 등) 검사는 CI `copy`(문구 검사) 잡이 반영 PR의 체크로 돌린다. 반영 PR에 빨간 X로 뜨고 커밋마다 다시 검사한다. 브랜치 보호가 없어 머지를 강제로 막지는 못하니, 리뷰 때 그 표시를 본다.
  - 디자인 PR마다 따로 실행한다. 여러 개가 연달아 머지되면 반영 PR도 각각 생긴다(서로의 반영은 들어 있지 않으니 차례로 머지하며 충돌을 푼다).
  - 머지된 디자인만 반영한다. 리뷰 중인 시안은 반영하지 않는다.
  - 한 방향이다. 반영 PR은 `design/`을 고치지 않는다(워크플로가 검사). 디자인 문제는 디자인 PR 코멘트로 넘긴다.
  - 다시 돌리려면 Actions → design-handoff → Run workflow에서 디자인 PR 번호를 넣는다(라벨과 무관하게 돈다).
  - 모델은 Sonnet 5.5(effort high), 한 번에 최대 80턴. 사용량은 레포 시크릿 `CLAUDE_CODE_OAUTH_TOKEN` 주인(명준)의 Claude 구독 한도에서 나가고, 따로 청구되지 않는다.
- 피그마를 쓴다면 Variables·Component·프레임 이름(테마 키) 세 가지만 지키고, PR 또는 팀 채널에 "토큰 갱신"을 남기면 개발이 가져간다.

## 6. 프로토타입 (`design/prototype/`)
- 디자인 아티팩트(Design v2) 원본 사본이다. JS로 화면을 그리고 눌러 볼 수 있으며, 계산 연출 · 영수증 출력 같은 움직임을 실제로 재생한다. **예시·참고용**이며 자동 반영 대상이 아니다(2절 "JS 없음" 규칙의 예외).
- 화면 생김새의 기준은 `screens/`, 흐름·움직임의 기준은 `HANDOFF_v2.md` → `HANDOFF_v3.md` → `HANDOFF_v4.md`(뒤 판이 앞 판 위에 바뀐 것). 셋이 어긋나면 디자이너가 맞춘다.
- 최신 아티팩트: https://claude.ai/artifact/MnKCLMKHXYrZqX72A2rgS9 (링크가 레포보다 앞설 수 있음 — 레포에 들어온 판이 넘긴 판, 11/14 기준 v170)

