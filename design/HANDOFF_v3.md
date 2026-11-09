# Design v3 핸드오프 가이드 — Nogo 브랜드 · 인터랙션 (June용)

- 기준: 디자인 아티팩트 v86(11/9). 노고 PRD v1.3 · 유저 플로우(11/8)는 그대로다.
- 작성: 명준(디자인), 2026-11-09
- 받는 사람: June(앱 · 에뮬레이터)
- 앞 가이드: `HANDOFF_v2.md`(화면 구조 · 흐름 · 계산 연출 시간표). **이 문서는 v2 위에 바뀐 것만 다룬다.** 요약 표는 v2 8절에도 있다.
- 원칙은 v2와 같다. 화면은 시안 그대로 맞추고, 움직임도 시간 · 곡선 · 순서를 그대로 입힌다.
- **주간 라임 그라디언트 바탕은 넣지 않는다.** 아티팩트에 있던 시안 옵션이고, 핸드오프에서는 뺐다. 주간 바탕은 `--color-background-canvas`(#F7F5FC)다.

## 0. 작업 순서 (권장)

| 순서 | 할 일 | 앱 파일 | 이 문서 |
|---|---|---|---|
| 1 | 테마 색을 역할 토큰으로 바꾸기 | `ui/theme/ChagokTheme.kt` | 1절 |
| 2 | 로고 · 워드마크 · 앱 아이콘 넣기 | `res/drawable` · `res/mipmap-*` · 위 바 · 시작 화면 | 2절 |
| 3 | 아이콘 23종 넣기 | `res/drawable` · `ui/components/CgParts.kt` | 3절 |
| 4 | 장소 사진 넣고 출발 미리보기 배경 | `res/drawable-nodpi` · `EffortDepartV2.kt` | 4절 |
| 5 | 홈: 로고 심볼 · 기록 카드 | `EffortHomeV2.kt` | 5-1 · 5-2 |
| 6 | 가는 중 홈: 시동 버튼 | `EffortMidway.kt` | 5-3 |
| 7 | 계산 연출: 노고 카 | `EffortCalc.kt` | 5-4 |
| 8 | 도착: 영수증 키움 · 인원 줄 · 합계 마커 | `EffortArrive.kt` | 5-5 |
| 9 | 검색 지우기 · 날짜 등 작은 것 | `EffortDepartV2.kt` 등 | 5-6 |
| 10 | 주간 · 야간 · 840 분할에서 확인 | 에뮬레이터 | 7절 |

- 1번만 해도 화면 대부분의 색이 바뀐다. 화면 코드가 `ChagokColors`에서 색을 꺼내 쓰기 때문이다.
- 숫자 단위는 v2와 같다. CSS 숫자 = Connect-S 실제 픽셀 = Compose `.dp`.
- 정확한 수치는 `screens/*.css` · `components/*.css`가 기준이다. 이 문서의 숫자는 거기서 옮긴 요약이다.

## 1. 테마 (`tokens.css` 맨 아래 → `ChagokTheme.kt`)

### 1-1. 지금 필드를 역할 토큰으로 바꾸기
시안은 앱 창 안에서 예전 이름을 아래 역할 토큰으로 다시 연결한다(`screens/common.css`의 `.cg-a-land,.cg-a-p1`). 앱도 같은 표대로 `LightColors` · `DarkColors` 값을 바꾸면 된다.

| `ChagokColors` 필드 | 역할 토큰 | 주간 | 야간 |
|---|---|---|---|
| `bgPrimary` | `--color-background-canvas` | #F7F5FC | #242033 |
| `surfaceCard` | `--color-surface-default` | #FFFFFF | #312C40 |
| `textPrimary` | `--color-text-primary` | #242033 | #F7F5FC |
| `textSecondary` | `--color-text-secondary` | #51495F | #DEDAE8 |
| `textTertiary` | `--color-text-tertiary` | #6B637B | #AFA8BD |
| (quaternary) | `--color-text-disabled` | #8B849C | #797187 |
| `lineWeak` · `lineStrong` | `--color-border-subtle` | #DEDAE8 | #464055 |
| `line` | `--color-divider-default` | #DEDAE8 | #464055 |
| `btnFilled` | `--color-button-primary-fill` | #D9F77A | #CDEA6D |
| `btnFilledPressed` | `--color-button-primary-pressed` | #BFD95F | #BFD95F |
| `btnFilledText` | `--color-button-primary-foreground` | #242033 | #242033 |
| `btnBasic` | `--color-button-secondary-fill` | #E6E3EF | #464055 |
| `btnBasicPressed` | `--color-button-secondary-pressed` | #D1CCDD | #5C546B |
| `btnDisabled` | `--color-button-disabled-fill` | #EFEDF5 | #3A354A |
| `placeholder` | `--color-background-subtle` | #EFEDF5 | #292538 |
| `placeholderStrong` | `--color-skeleton-highlight` | #DEDAE8 | #51495F |
| (dim) | `--color-dim-scrim` | #2420338F | #000000B8 |

- `bgSecondary` · `surfaceHigh` · `surfaceAccent` · `active` · `positive` · `negative` · 배지 색은 피봇 전 화면용이라 그대로 둔다.
- 상태 그린(`active`를 완료 표시로 쓰는 곳)은 Design v3에서 쓰지 않는다. 완료 글자는 `textPrimary`다.

### 1-2. 새로 넣을 토큰 묶음
값은 `tokens.css` 맨 아래에 주간(`:root`) · 야간 두 벌로 있다. 이름은 CSS에서 `--color-` 뒤를 camelCase로 바꾸면 된다(예: `--color-selection-background` → `selectionBackground`).

| 묶음 | 쓰는 곳 | 핵심 값(주간 / 야간) |
|---|---|---|
| `selection-*` | 고른 목적지 카드 · 쉰 시간 칩 · 제안 항목 · 설정 방식 · 방금 기록 꼬리표 | 면 #D9F77A / #D9F77A, 글자 #242033 / #242033, 테두리 #242033(주간 2px) / #D9F77A |
| `progress-*` | 계산 연출 체크포인트 · 진행 선 | 표시 #D9F77A, 테두리 · 지나온 선 #242033 / #D9F77A, 트랙 #9A93AA / #6B637B |
| `switch-*` | 설정 스위치 | 켜짐 트랙 #D9F77A / #CDEA6D, 손잡이 #242033 |
| `field-*` · `focus-ring` | 검색 창 | 커서 · 포커스 #242033 / #D9F77A |
| `receipt-*` | 영수증 종이 · 출력구 · 합계 마커 | 종이 #FFFFFF / #312C40, 마커 #D9F77A / #445616 |
| `status-*` | 인터넷 없음 · 실패 · 안내 | 경고 · 오류 · 안내 면 + 글자 짝 |
| `illust-*` | 노고 카(5-4) · 홈 로고 바닥 그림자 | 33개. 차체 · 유리 · 휠 · 그림자 |
| `brand-wordmark` | 위 바 워드마크 | #242033 / #D9F77A |
| `button-destructive-*` | 그만두기 · 기록 초기화 확인 | #9D303D / #FFADAD |
| `driving-lock-*` | 주행 중 가림 | 바탕 #EFEDF5 / #242033 |

- 대비는 72쌍을 계산했다. 글자는 모두 4.5:1 이상이고, 면끼리는 1.15:1 이상 차이 난다.
- 주간 라임 원 · 스위치는 라임 자체 대비가 낮아서 경계를 잉크 테두리 · 손잡이가 맡는다. 테두리를 빼지 않는다.

## 2. 로고 · 워드마크 · 앱 아이콘 (`design/brand/`)

| 파일 | 앱에 넣을 곳(제안) | 쓰는 곳 |
|---|---|---|
| `nogo_app_icon_lime-gradient_512.png` | `res/mipmap-*` 런처 아이콘 | 앱 아이콘 |
| `nogo_icon_lime-gradient.svg` | `res/drawable/nogo_icon_lime_gradient.xml` | 시작 화면(주간) |
| `nogo_icon_ink-gradient.svg` | `res/drawable/nogo_icon_ink_gradient.xml` | 시작 화면(야간) |
| `nogo_wordmark_deepink.svg` | `res/drawable/nogo_wordmark.xml` | 홈 위 바 가운데(152 × 48) |
| 그 밖 배리언트 6종 | 넣지 않아도 됨 | 디자인 시스템 · 영상용 |

- 워드마크 색은 `brandWordmark` 토큰으로 칠한다(주간 딥 잉크, 야간 라임). 한 벡터에 `tint`를 주면 된다.
- 시작 화면 아이콘은 192 × 192, 그림자 `0 8 24 #24203324`다. `components/logo.html`에 8종 모습이 있다.
- 타일은 144 기준 모서리 44다. 512 PNG는 같은 비율이다.

## 3. 아이콘 (`design/icons/phosphor/`, Phosphor Bold · MIT)

- SVG 23종을 Android Studio Vector Asset으로 `res/drawable/ic_ph_<이름>.xml`로 옮긴다(하이픈은 밑줄로).
- 색은 쓰는 자리의 글자색(`LocalContentColor`)을 따른다. 시안도 마스크로 그려 글자색을 따른다.
- `LICENSE-phosphor.txt`는 앱 오픈소스 고지에 넣는다.
- Pleos 에셋 4종(뒤로 · + · − · 화살표, `icons/pleos/`)은 그대로 쓴다.

| 아이콘 | 쓰는 곳 |
|---|---|
| `receipt` | 홈 위 바 왼쪽 · 기록 전체 |
| `gear-six` | 홈 위 바 오른쪽 · 설정 |
| `users-three` | 출발 화면 인원 알약 |
| `magnifying-glass` · `microphone` | 목적지 검색 창 |
| `house` · `briefcase` · `clock-counter-clockwise` | 목적지 카드 꼬리표(집 · 회사 · 최근) |
| `wifi-slash` · `warning-circle` · `map-trifold` | 인터넷 없음 · 실패 · 지원 안 하는 지역 |
| `steering-wheel` | 주행 중 가림 |
| `arrow-bend-up-right` | 순정 내비 와이어(참고용) |
| `cloud-rain` · `snowflake` · `traffic-cone` · `clock-countdown` · `moon` · `hourglass-medium` · `sun` | 홈 기록 카드 길 조건 칩: 비 · 눈 · 정체 · 지연(예상보다 더) · 밤길 · 장시간 · 맑은 길 |
| `check-circle` · `info` · `star` | 예비(지금 화면에는 없음) |

## 4. 장소 사진 (`design/places/`, 임시)

시안과 똑같이 앱에도 같은 사진을 넣는다. 지금은 임시 사진이고, 출처가 정해지면 **파일만** 바꾼다.

### 4-1. 파일과 고르는 규칙
| key | 파일 | 목적지 이름에 들어 있으면 |
|---|---|---|
| `taean` | `taean.jpg`(760 × 507) | "태안" 또는 "꽃지" |
| `gapyeong` | `gapyeong.jpg`(760 × 507) | "아침고요" 또는 "가평" |
| `yangpyeong` | `yangpyeong.jpg`(760 × 507) | "두물머리" 또는 "양평" |
| `home` | `home.jpg`(960 × 611) | 이름이 정확히 "집" |
| `office` | `office.jpg`(960 × 635) | 이름이 정확히 "회사" |
| (없음) | — | 그 밖 = 사진 없이 딥 잉크 바탕(#242033) |

- 앱 경로 제안: `res/drawable-nodpi/place_<key>.jpg`
- 시안의 고르는 함수(`prototype/index.html`의 `PLACE_IMG`)와 같은 순서로 검사한다.
- 출처는 `places/CREDITS.md`다(Wikimedia Commons, CC BY-SA 3.0 · 4.0). CC BY-SA라 **앱 안 어딘가에 저작자 표기가 필요하다**(4-3).

### 4-2. 쓰는 곳 = 출발 화면 미리보기 칸 배경
- 홈 기록 카드에는 사진을 쓰지 않는다(11/9 명준).
- 미리보기 칸(`components/preview-panel`, `cg-pvimg`)
  - 사진: 칸을 꽉 채움(`ContentScale.Crop`), 세로 기준점 60%
  - 그늘(가로 창): 왼쪽 → 오른쪽 그라데이션 `#242033F2` 0% → `#242033D9` 34% → `#24203380` 62% → `#24203326` 100%
  - 그늘(분할 840): 위 → 아래 `#24203380` 0% → `#242033CC` 45% → `#242033F2` 100%
  - 글자: 흰색(제목 #FFFFFF · 보조 #FFFFFFE0 · 옅은 글자 #FFFFFFB8), 오류 글자 #FFADAD · 안내 글자 #A9CFFF
  - [출발]: 라임 면 #D9F77A + 딥 잉크 글자, 꺼짐은 `#FFFFFF2E` 면 + `#FFFFFFA6` 글자
  - 불러오는 중 막대: `#FFFFFF33`, 반짝임 없음
  - 그림자: `0 18 44 #24203333` + `0 4 12 #2420331F`
- 주간 · 야간 모두 같은 값이다(사진 위라서 모드와 상관없음).

### 4-3. 확인 필요
- 저작자 표기 위치: 제안은 설정 화면 "단가 출처" 아래에 "사진 출처" 펼침을 두는 것이다. 명준이 다음 디자인 PR에서 자리를 정한다. 그 전까지는 `CREDITS.md` 문구를 오픈소스 고지에 같이 넣는다.

## 5. 화면별 작업

### 5-1. 홈 인사 = 로고 심볼 (`EffortHomeV2.kt`, `screens/home.html`)
- 원 + 차 그림 자리를 로고 심볼(손 + 운전대, 평면)로 바꾼다. 원 바탕은 없다.
- 크기: 가로 창 300 × 300, 분할 180 × 180
- 색: 주간 #242033, 야간 #D9F77A. 손가락 사이 구분선은 주간 #F7F5FC · 야간 #242033
- 바닥 그림자: 심볼 아래 타원 그라데이션(주간 `#2420334D` → 투명, 야간 `#00000080` → 투명)
- 움직임: 심볼이 3.2초 주기로 4px 위로 떠올랐다 내려옴, 그림자는 같은 주기로 가로 1 → 0.9 · 투명도 1 → 0.75(6절)
- 고르기 전에 나간 도착: 심볼 오른쪽 위에 "도착" 깃발(딥 잉크 알약 + 흰 글자, 주간 기준)
- 위 바: 왼쪽 `receipt` 아이콘 버튼 · 가운데 워드마크 · 오른쪽 `gear-six` 아이콘 버튼(Icon Button 80)

### 5-2. 홈 기록 카드 (`components/record-card`)
| 자리 | 가로 창(3열, 높이 340) | 분할(1열) |
|---|---|---|
| 안쪽 여백 | 30 · 34 · 28 | 16 · 24 |
| 날짜(왼쪽 위) | 700 26/32 · `textTertiary` | 700 22/28 |
| 배수(오른쪽 위) | 숫자 800 44/52 + "배" 700 26/32. 숫자 아래 40%에 라임 마커(`receiptResultMarker`) | 숫자 800 30/36 |
| 평소 같은 길 | 700 26 · `textTertiary`(마커 없음) | 작게 |
| 길 조건 칩 | 높이 48 알약 · 아이콘 28 + 글자 700 24/30 · 면 `backgroundSubtle` · 간격 10 | 36 × 36 아이콘만, 오른쪽 아래 |
| 장소 | 800 44/56(아래 묶음 시작, 위는 비움) | 800 32/40 |
| 시간 · 인원 | 600 30/38 · `textSecondary` | 600 24/30 |

- 칩 데이터: 그날 영수증에서 **더해진 항목**(정보 없음 제외)을 더한 몫이 큰 순서로 최대 3개, 남으면 "+N". 하나도 없으면 `sun` "맑은 길"
  - 날씨 = 비(`cloud-rain`), 눈이면 `snowflake` "눈" · 정체 구간 = `traffic-cone` "정체" · 예상보다 더 = `clock-countdown` "지연" · 밤길 = `moon` · 장시간 = `hourglass-medium`
  - 값은 엔진 결과(영수증 항목)를 그대로 쓴다. 화면에서 따로 계산하지 않는다.
- 배수 = 영수증 배수와 같은 값. 1.00이면 "평소와 같은 길"(명세 표기)
- 방금 끝난 기록: 날짜 꼬리표만 라임(`selection-*`)

### 5-3. 가는 중 홈 = 시동 버튼 (`EffortMidway.kt`, `components/mid-button`)
- 크기: 지름 360(분할 200). 베젤 두께 22(분할 12)
- 베젤: 위 → 아래 Satin Silver `receiptPrinterHighlight` 0% → `Midtone` 18% → `Shade` 70% → `Midtone` 100%
- 면: 대각 135° `#F5FFD2` → `#D9F77A` → `#A9C640`(로고 lime-gradient와 같음)
- 안쪽: 로고 심볼 58%(면 #242033 · 선 #D9F77A 1.6)
- 움직임(6절)
  - 운전대가 2초마다 한 바퀴(0.9초 돌고 1.1초 쉼)
  - 고리 두 겹이 1.2초 차이로 퍼짐: 테두리 3, 크기 0.96 → 1.14, 투명도 0.85 → 0. 색은 주간 #8FA930 · 야간 #D9F77A
  - 누르면 0.985로 눌리고 면이 2 내려감
- 아래 "지금까지의 노고 보기" 글자와 "~까지 정산함" 표시는 없앴다. 접근성 이름은 "지금까지의 노고 보기"로 둔다.

### 5-4. 계산 연출 = 노고 카 v3.1 (`EffortCalc.kt`, `components/nogo-car`)
- 차 그림을 현대 코나 일렉트릭 옆모습(170 × 70 틀)으로 바꾼다. SVG 원본은 `components/nogo-car.html`이다.
- 색: 주간 = 딥 잉크 차체(`illustInkCar*`), 야간 = 라임 차체(`illustCar*`). 정체 장면의 다른 차는 `illustOther*`
- 크기: 가로 창은 1.5배(앞 범퍼 · 바닥 기준으로 키움), 분할은 그대로
- **연기(배기 · 타이어)는 모두 뺐다.** 전기차라서다. 앱에 연기가 있으면 지운다.
- 움직임 · 단계 시간은 v2 3-1 그대로다.

### 5-5. 도착 (`EffortArrive.kt`, `screens/arrive.html`)
- 위 제목 줄("도착했어요" 등)을 지웠다. 영수증 · 제안 카드가 위로 올라와 커진다.
- 영수증 글자를 1~2px 키우고 줄 간격을 넓혔다(`screens/arrive.css`).
- 영수증 머리 아래 한 줄: "장소 · 날짜 · n명"(혼자면 "혼자"). 위에 따로 있던 인원 표시는 없앴다.
- 항목 근거 펼침: 첫 문장만 보인다. 출처(법 조항 등)는 화면에 내지 않는다.
- 합계(A안): "합계"는 기본 글자, 결과 문장만 볼드 + 글자 아래 40%에 라임 마커(`receiptResultMarker`). 줄이 바뀌어도 마커가 글자를 따라간다.
- 출력구: Satin Silver(`receiptPrinter*`)
- 쉰 시간 줄 값을 누르면 쉰 시간 시트가 다시 열린다.
- 제안 칸 기다림 문구: "확인 중"

### 5-6. 작은 것
- 검색 창 "지우기": 면 없는 글자 버튼(인원 알림 "닫기"와 같은 규칙). 높이 72(분할 64), 글자 `textTertiary`
- 중간 정산 영수증 제목: "오늘 주행 영수증"
- 방식 B 제안 제목: 한 줄
- 시연 날짜: 11월 10일(바코드 `#2026 1110 …`)

## 6. 움직임 숫자 (v3 추가분)

| 움직임 | 시간 | 곡선 | 반복 |
|---|---|---|---|
| 홈 로고 떠오름 | 3.2초 | cubic-bezier(.45, 0, .55, 1) | 왕복 무한 |
| 시동 버튼 운전대 | 2초(돌기 0.9초 + 쉬기 1.1초) | cubic-bezier(.65, 0, .35, 1) | 무한 |
| 시동 버튼 고리 | 2.4초, 두 번째 고리 1.2초 늦게 | cubic-bezier(.22, .7, .3, 1) | 무한 |
| 시동 버튼 누름 | 0.22초 | cubic-bezier(.32, .72, 0, 1) | — |

- 시스템 "움직임 줄이기"가 켜져 있으면 위 넷 모두 멈춘다.
- Compose 옮기는 법: `rememberInfiniteTransition` + `CubicBezierEasing(...)`. 끊기면 v2 4절대로 다듬는다(빼지 않는다).

## 7. 확인하는 법

- 시안 보기: `design/`에서 `python -m http.server` → `preview.html` · `screens/*.html` · `prototype/index.html`
  - 프로토타입 위 막대에서 주간 · 야간과 가로 · 분할을 바꿔 본다.
- 에뮬레이터 확인 목록
  - [ ] 주간 · 야간 둘 다: 바탕 · 카드 · 글자 · 선택 · 주요 버튼 색이 시안과 같음
  - [ ] 840 분할: 기록 카드 칩이 아이콘만, 시동 버튼 200, 미리보기 그늘이 위 → 아래
  - [ ] 시동 버튼 · 홈 로고 움직임이 끊기지 않음, 움직임 줄이기에서 멈춤
  - [ ] 계산 연출에 연기 없음
  - [ ] 장소 사진 5곳 + 사진 없는 목적지(딥 잉크 바탕)
  - [ ] 아이콘 색이 글자색을 따라감(야간에서 특히)

## 8. 확인 필요 (June)

- 노고 카 · 로고 심볼 SVG에 그라데이션 · clipPath가 있다. VectorDrawable로 옮겨지는지 봐 주세요. 안 되면 Compose `Canvas`로 그리거나, 마지막 수단으로 PNG(2배)로 대신하고 PR에 적어 주세요.
- 역할 토큰을 `ChagokColors`에 필드로 다 넣을지, 화면에서 쓰는 것만 넣을지는 June이 정한다. 시안에서 쓰는 이름은 1절 표와 같다.
- PR #75와의 관계: #118에 #75 커밋이 함께 들어 있다. #118 하나로 입히는 것을 기본값으로 한다.
