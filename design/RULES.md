# 차곡 디자인 규칙 (코드로 넘길 때)

디자이너가 Claude Code로 HTML·CSS·SVG를 만들어 `design/`에 PR 한다. 이 파일을 Claude Code에 먼저 읽힌다.
앱은 WebView라 여기 있는 조각이 거의 그대로 들어간다. 동작(JS)은 개발이 붙인다.
피그마로만 작업한다면 4절만 지키고, 코드 변환은 개발(June)이 한다 — 어느 쪽으로 할지는 디자이너가 정한다(DECISIONS 열린 결정 12).

| 영역 | 담당 |
|---|---|
| 토큰(`tokens.css`)·컴포넌트 조각(`components/`)·미리보기·이 규칙 | UI·UX 디자이너 명준 |
| 테마 일러스트(`illustrations/`)·아이콘(`icons/`)·앱 아이콘(`brand/`)·키 컬러 | BX 디자이너 (GitHub 가입 전까지 명준이 대신 올림) |
| 연동 리뷰(WebView에 그대로 들어가는지) | June |

## 1. 값은 전부 tokens.css 변수로 — 기준은 Pleos Connect UI Design Guide
- 색·글자·간격은 `design/tokens.css`의 `--cg-*` 변수만. 하드코딩 금지.
- 기준은 [Pleos Connect UI Design Guide](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro)의 Foundations(Colors·Typography)와 Basic button. 지금 값은 H_Mate(June)가 가이드에서 옮긴 것이고, 확정은 명준이 한다(키 컬러는 BX와 함께).
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

### 만들어야 할 조각 (명세서 v0.4 화면 기준)
| 조각 | 화면·기능 | 상태 |
|---|---|---|
| `card-front` | 한 장 앞면(F5) | 초안 있음 |
| `badge` | 배지 3변형(F5) | 초안 있음 |
| `card-stack` | 뒤집힌 스택 모서리 + "다른 후보 n"(F5) | |
| `cost-sheet` | 비용 분해 바텀시트: 연료·통행료·주차("정보 없음")·합계·1인 몫·경유지 추가(F5·F8) | |
| `per-person` | "1인 7천 원 안팎" 배지(F11) | |
| `balance` | 홈 잔고 "배터리에 1만 9천 원어치 · 약 326km" + 단가 출처 작게("평균 단가")(F1) | |
| `chip` | 프리셋·당일/1박·인원·테마 칩, 비활성 상태(추가 0원 불가)(F2·F3) | |
| `charge-card` | "방금 약 7천 원어치 충전 → 갈 곳 한 장 볼까요?"(F9) | |
| `seat-banner` | "3명 · 1인 기준으로 볼까요?"(F11) | |
| `settle-card` | "지난번 이후 132km · 약 8천 원 · 마지막 목적지 양평"(F10) | |
| `record-summary` | "이번 달 이동비 약 7만 원 · 다녀온 곳 3곳" + 지도 핀·핀 상세(F17) | |
| `demo-mark` | 화면 구석 "시연용 값"(AC-C-7) | |
| `notice` | X2 안내 문구 9종 상태(잠시 후 다시·위치 확인 중·차량 정보를 읽는 중·범위 안 후보가 적어요 등) | |
| `empty` | "첫 드라이브를 기다리는 중"(F17) | |

## 3. 일러스트·아이콘
- 테마 8개 × 변형 3~4장(PRD 7-5). 파일명 `design/illustrations/<theme>-<n>.svg`, `<theme>` = 서버 `theme` 값:
  `sea lake river mountain park city island drive`, `<n>` = 1부터.
- 같은 장소는 늘 같은 그림: 앱이 장소 ID로 변형을 고른다(`ChagokEngine.illustrationPath`). 변형 수를 바꾸면 PR에 적는다.
- 실제 장소 사진인 척하지 않는다. 아이콘은 48dp 그리드 SVG, `design/icons/<name>.svg`. 앱 아이콘 512px PNG는 `design/brand/`.

## 4. 차량 제약 (심사 기준)
- 터치 영역 48px 이상, 글자 최소 12sp, 대비 4.5:1(주간·야간 둘 다).
- Window Policy: 900px 이하(1:1 분할·세로 창)에서는 한 단으로 쌓는다(H_Mate 실측 기준).
- 정차 화면만 있다(1차). 카드 뒤집기 전환은 1초 안. 자동 스크롤 없음.
- 가로·세로·멀티윈도우(한 장만) 세 레이아웃. `design/preview.html`에서 셋 다 확인하고 PR.

## 5. 넘기는 방법
- PR 제목 `design: <무엇>`. 리뷰는 June(연동) 1명.
- 피그마를 쓴다면 Variables·Component·프레임 이름(테마 키) 세 가지만 지키고, PR 또는 팀 채널에 "토큰 갱신"을 남기면 개발이 가져간다.
