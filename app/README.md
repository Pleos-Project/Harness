# app/ — 차 앱

```
app/android/                                                안드로이드 프로젝트(Kotlin). 앱 기능은 여기에 짓는다
app/android/app/src/main/java/com/mist/pleos_app/
  engine/      Kotlin 엔진 (server/chagok/engine.py·cards.py 포팅) — 수식은 여기만
  vehicle/     차량값 읽기: PleosVehicleRepository(Pleos SDK + AAOS), VehicleSnapshot
  app/         화면 상태: AppViewModel(화면 스택·복원), HomeModel(차량값 → 엔진 입력)
  ui/          Compose: ChagokApp(뼈대), components/(공통 부품), theme/(색·글자)
  util/        ChagokLog(1줄 JSON 로그)
app/android/app/src/test/.../  FixturesTest(fixtures 대조)·HomeModelTest — cd app/android && ./gradlew test
```

H_Mate(github.com/june0103/H_Mate)는 June의 실험 레포로, 참고용이다. 레포를 통째로 옮기지 않는다. 그 안의 차곡 데모 화면·브리지(`ChagokBridge.kt`)는 WebView용이라 가져오지 않는다(화면은 Compose). 빌드 환경·디자인 가이드 토큰·콘솔 기어 경로·SDK 동작 메모만 이 레포로 가져왔다(아래 1·4절, `design/tokens.css`, `scripts/replay.sh`).

## 1. 빌드 환경 (H_Mate 11/4 설정과 같은 값)
| 항목 | 값 | 파일 |
|---|---|---|
| Pleos SDK 저장소 | `https://nexus-playground.pleos.ai/repository/maven-releases/` (인증 없이 열림, 11/5 확인) | `settings.gradle.kts` |
| Pleos SDK | `ai.pleos.playground:Vehicle:2.3.2`, `NaviHelper:3.0.5` | `gradle/libs.versions.toml` |
| 빌드 도구 | AGP 9.3.3 · Gradle 9.5.0(wrapper) · JDK 21(툴체인 자동 받기) | `gradle/` |
| SDK 레벨 | compileSdk 37 · targetSdk 36 · minSdk 28, `useLibrary("android.car")` | `app/build.gradle.kts` |
| 화면 | Jetpack Compose(BOM 2026.06.01, Material3, `activity-compose` 1.13.0). Compose 컴파일러 플러그인 = AGP 내장 Kotlin 버전(2.2.10) | `gradle/libs.versions.toml` |
| 권한 | `contract/permissions.md`와 정확히 같음. `distractionOptimized` 미선언(1차 정차 전용) | `AndroidManifest.xml` |

```bash
cd app/android
./gradlew assembleDebug test                                       # 상수·데모 후보가 assets/data/로 복사되고 엔진 fixture 대조
adb install -r -g app/build/outputs/apk/debug/app-debug.apk        # -g: 권한 미리 부여(에뮬)
```
**검증 상태:** CI `app-android` job(assembleDebug·test)이 통과한다. 로컬은 JDK 21 + Android SDK면 되고, 윈도우는 `gradlew.bat`(`make check-app GRADLEW=gradlew.bat`).
`applicationId`는 June `App` 레포와 같은 `com.mist.pleos_app`이고, App Market 등록 전에 확정한다(DECISIONS 열린 결정 1). 빌드 버전을 App 레포 값(AGP 9.4.1 등)으로 올릴지는 열린 결정 21.

## 2. Kotlin 엔진 쓰기 (`com.mist.pleos_app.engine`)
계산은 전부 엔진이 한다. 화면 코드에 금액·거리 수식을 적지 않는다. 엔진이 서버와 같은 숫자를 내는지는 `FixturesTest`가 `fixtures/`로 대조한다.
상수(차종 표·기본 단가)와 데모 후보는 빌드 때 `syncChagokData`가 `fixtures/`에서 `assets/data/constants.json`·`candidates_demo.json`으로 복사한다(사본은 커밋하지 않음). 앱에서는 `parseConstants(assets 문자열)`·`parseCandidates(…)`로 읽는다.

| 기능 | 함수 | 명세 |
|---|---|---|
| 잔고·범위(로컬) | `vehicleInputs` → `computeRange` | F1·F2, AC-F1-1·2·5, AC-F2-1·2·3 |
| 서버 요청 만들기 | `cardsRequest(...).toJson()` (원시 차량값 대신 `budget` 파생값) | F5, `contract/openapi.yaml` |
| 서버 응답 읽기 | `parseCardsResponse(json)` — 온라인에서는 `display` 문구 그대로 | F5 |
| 오프라인 데모·서버 실패 fallback | `DemoServer(candidates).cards(req)` · `.swap(sessionId)` — 서버와 같은 응답 | AC-C-3·AC-F5-7·AC-F5-8 |
| 충전 직후 | `chargeAmount` | F9, AC-F9-1 |
| 주유 직후 '가득' 금액 | `fullTankAmount(시작 연료 %, 탱크 L, 단가)` → 리터·원·"약 7만 원"(그랜저 HEV 50L·25%) | F9(PRD v1.3 '가득'), fixture D11 |
| 정산·도착·핀·월 합계(로컬 장부) | `ledgerStep(state, event)` · `monthSummary` | F8·F10·F17 |
| 일러스트 변형 | `illustrationName(theme, placeId, n)` → `"<theme>-<n>"` | design/RULES.md 3절 |

장부 이벤트는 3개다: `LedgerEvent.Open`(앱 실행·복귀, 기어 P) / `Depart`(출발 1탭) / `Arrive`(기어 P + 운전석 문, `onDestinationArrived`). state는 `toJson()`/`parseLedgerState()`로 문자열 저장한다. 저장소(Room·DataStore)는 장부 화면을 지을 때 정한다(아직 없음).

## 2-1. Compose 화면 규칙
- 테마는 `ui/theme/ChagokTheme.kt`. 색은 `design/tokens.css`를 옮긴 임시본이고, 토큰 관리 방식은 `design/RULES.md` 개정(열린 결정 12)에서 정한다. 그 전까지 색을 바꾸면 tokens.css도 같이 바꾼다.
- 주간/야간은 `isSystemInDarkTheme()`(기본 야간, 시스템이 주간이면 주간).
- 디자인 가이드 크기는 디스플레이 실제 픽셀이다 → `48.gpx`(Dp)·`26.gsp`(TextUnit). 터치 영역 48처럼 dp 기준 값은 그냥 `48.dp`.
- 화면 문구는 `res/values/strings.xml`에 둔다(`scripts/lint_copy.py`가 검사). 엔진이 만든 문구(`balanceText`·`display.*`)는 그대로 보여 준다.
- 색·글자는 `Chagok.colors.*`·`Chagok.type.*`(tokens.css 값, Pleos 가이드 위계)만 쓴다. 버튼·칩·카드·안내 문구·"시연용 값"은 `ui/components`의 `CgButton`·`CgChip`·`CgCard`·`CgNotice`·`DemoMark`. 명준 시안이 나오면 이 부품만 바꾼다.
- 창 폭이 900px 이하면 한 단(`LocalNarrow`). 레이아웃 확인은 `@Preview`로 가로·세로(1:1 분할)·주간을 같이 둔다(`ChagokApp.kt` 참고).
- **화면 상태는 `remember`가 아니라 ViewModel(SavedStateHandle)에 둔다.** 주행 중에는 화면 composable이 가림 화면으로 바뀌어 composition에서 빠지므로 `remember`·`rememberSaveable` 값이 버려진다. 카드·프리셋·인원·테마 선택처럼 정차 뒤 그대로 돌아와야 하는 값(명세 X1 "세션·입력 저장", AC-X1-1·AC-F5-6)은 ViewModel에. 펼침·스크롤처럼 사라져도 되는 것만 `remember`.
- 화면 이동은 `AppViewModel`의 화면 스택(SavedStateHandle, AC-C-11). CCP Back은 홈에서 `moveTaskToBack(true)`(finish 금지, Pleos 앱 개발 가이드).
- 버튼·칩·카드·아이콘 버튼은 누르면 95%로 줄고 배경이 바뀐다(가이드 Button interaction). 칩은 가이드 Toggle button, 교체는 Icon button(아이콘 SVG가 나오기 전까지 글자).
- 범위·한 장 흐름은 `app/CardModel.kt`(`CardFlow`·`presets`·`toggleTheme`, JUnit `CardModelTest`). 지금은 오프라인 `DemoServer`만, 서버 호출은 같은 자리에 붙인다.

## 3. 서버 호출 — Kotlin에서 직접 (열린 결정 2 해결)
- 앱이 부르는 건 `/v1/cards`·`/v1/cards/swap`·`/v1/prices` 셋(`contract/openapi.yaml`). 클라이언트는 `net/ChagokApi`(플랫폼 `HttpURLConnection`, 새 의존성 없음).
- 요청은 `cardsRequest(...).toJson()`, 응답은 `parseCardsResponse()`. 원시 차량값은 보내지 않는다(`budget` 파생값만, 서버가 422로 막음).
- 주소는 `BuildConfig.API_BASE_URL`.
  - release: **`https://api.rangeleft.com`**(Let's Encrypt, 체인 끝 ISRG Root X1 → 안드로이드 7.1.1+). 서버 운영은 `deploy/README.md`(효성).
  - debug: `http://10.0.2.2:8000`(`make dev`). **debug 빌드만** `src/debug/res/xml/network_security_config.xml`이 `10.0.2.2` cleartext를 허용한다. release는 cleartext가 기본 차단이라 HTTPS 도메인 하나만(AC-N-3).
  - 배포 서버로 시험할 때만 `./gradlew assembleDebug -PchagokApi=https://api.rangeleft.com`.
- **모드**(설정 S1 "데모 고정 모드", 기본 켜짐): 켜짐 = 네트워크 없이 `DemoServer`(PT는 네트워크 없이 돌아가야 함, AC-C-3). 끔 = 서버 실서비스(`demo_seed` 없음).
- 타임아웃 3초. 실패(연결·타임아웃·5xx·503 `candidates_not_ready`)면 `DemoServer(candidates)`로 fallback(AC-F5-7·AC-F5-8, `app/CardSource`).
  - 한 장 화면 스택 자리에 "잠시 후 다시 볼까요?" + [다시 보기](X2). 같은 뽑기 안에서는 서버를 다시 기다리지 않는다(오늘의 한 곳 +1만·+3만 자동 전환 포함).
  - fallback으로 만든 세션의 재호출·교체는 계속 `DemoServer`로. 서버 세션의 재호출·교체가 네트워크로 실패하면 앞면을 그대로 두고 같은 안내.
  - 명세 fallback ①(직전 응답 캐시)과 `requestTrip` 거리 보정은 아직 없다(내장 후보의 거리·시간 그대로).
- **출발점**(요청 `origin`, 명세 F5): 뽑기 전에 내비 `getCurrentLocationInfo`(P `NAVI_ROUTE`, 2초) → 못 받으면 직전 위치(메모리) → 집 위치(`constants.origin`, 에뮬 현재 위치와 같은 성남 시흥동). 기다리는 동안 "위치 확인 중"(X2). 재호출·교체는 뽑을 때의 출발점 그대로. 좌표는 저장·로그하지 않는다(로그는 `origin src`만). 설정에 집 위치 입력은 아직 없다.
- 호출은 화면을 연 동안만, IO 스레드 한 줄로(`DemoServer` 세션이 스레드 안전하지 않음). 화면은 바로 넘기고("갈 곳을 고르는 중") 응답이 오면 바꾼다. 폴링·백그라운드 호출 금지.
- **평균 단가 `GET /v1/prices`**(DECISIONS '계약: 단가'): 입력 없음, 응답 `{gasoline, diesel, trade_dt, source}`(원/L 정수).
  - 사용자 단가가 없을 때만 쓴다. 받은 휘발유 값으로 내장 기본값을 갈아 끼운다(`AppViewModel.constants` = `copy(gasolineKrwPerL = …)`). `price_source`는 `default` 그대로, 화면은 "평균 단가".
  - 화면이 보일 때(앱 실행·복귀)마다 1회, 타임아웃 3초. 실패·503이면 직전에 받은 값(SharedPreferences), 그것도 없으면 내장값. 데모 고정 모드에서는 부르지 않는다.
  - 첫 화면은 이 호출을 기다리지 않는다(AC-N-1). 내장값(또는 직전 값)으로 먼저 그리고 응답이 오면 바꾼다.
  - 전기 단가(원/kWh)는 오피넷에 없다 → EV는 설정·내장값.

## 4. SDK·에뮬 동작 메모 (H_Mate README, June 11/4~5 실측)
명세서 8절 신호 표에 없는 것만 적는다.
- `requestTrip`을 동시에 보내면 응답이 섞이고 늦은 응답도 온다 → 요청 순서가 아니라 응답 `summary.routePoints` 중 DESTINATION의 `poiName`으로 목적지를 맞춘다. 곳당 0.3~0.8초.
- 경유지가 있으면 `getDestinationInfo`의 남은 거리·통행료가 첫 구간(경유지까지) 기준이다.
- `RouteInfo.routeOption`은 내비까지 전달되지 않고, `changeRouteOption`은 받기만 하고 무시된다.
- 착석은 SDK가 반대로 읽는다(SDK 1 = 앉음, AAOS·콘솔 1 = 빈 좌석, 2 = 앉음) → 설정 `seat_invert`.
- 기어 P = 규제 없음, D = 속도 0이어도 규제. 규제 중 시스템이 앱을 가린다. adb 설치본은 `distractionOptimized`를 선언해도 가려진다.

## 4-1. 차량값 읽기 (`vehicle/PleosVehicleRepository`)
- Activity 수명주기 observer. 리스너는 두 묶음이다.
  - **관문**(기어·주행 규제): 화면이 보이는 동안 늘 듣는다.
  - **데이터**(운전석 문·착석): 정차일 때만.
  - 피봇 전 값(SOC·연료 %·연비·용량·누적 주행거리·충전 스위치·`RANGE_REMAINING`)은 11/8부터 읽지 않는다(권한도 뺌, `contract/permissions.md`).
  - `onStop`에 둘 다 해제(AC-C-2).
- **주행 중(X1)** = 규제 ON 또는 기어 ≠ P(`isDriving`, 기어를 아직 모르면 아님): 데이터 리스너를 끊고(AC-X1-2) 앱 안에서도 화면을 가린다(`DrivingCover`, 금액 없음). 화면 스택은 그대로라 정차하면 같은 화면으로 돌아온다(AC-X1-1).
- `onFailed`는 `SecurityException`(권한 없음)과 그 밖(미지원)을 나눠 `VehicleSnapshot.failures`에 남긴다. `VehicleException`은 공개 클래스가 아니라 못 쓴다.
- 기어는 Pleos `Gear` API(P `CAR_DRIVE`). SDK가 AAOS `GEAR_SELECTION`을 권하지만 그건 A `CAR_POWERTRAIN` 권한이라 쓰지 않는다.
- **충전 스위치는 Pleos SDK에 리스너가 없다**(AAR 2.3.2, Get만). 피봇 전에는 AAOS `EV_CHARGE_SWITCH` 콜백(A `CAR_ENERGY`)으로 들었다(11/8 권한 정리로 뺌). 콘솔 "Charge Switch"가 이 속성으로 들어온다(11/6 실측, OFF→카드 27ms).
- SDK 사용법·실측은 Pleos 문서 묶음(`docs/pleos` 사본, June PC)의 11-sdk-verification을 기준으로 했다.

- **F9 충전·주유 직후**(`app/RefuelFlow`): EV는 충전 스위치 OFF→ON 때 SOC 기억 → ON→OFF 때 엔진 `chargeAmount`로 홈 맨 위 카드, 탭 또는 3초 뒤 한 장(오늘의 한 곳 경로). 앱을 연 때 이미 충전 중이면 "충전 끝났어요"만. 내연은 정차 중 연료 %(없으면 설정 모의 값)가 3%p 이상 오르면 금액 시트(3만 / 5만 / 가득 + 1만 원 ±) → 한 장. 화면이 보이는 동안만 판단한다.

## 4-2. 출발(F8)·1인 몫(F11)
- `navi/NaviRepository`: 출발을 누를 때만 `NaviHelper.initialize` → `onConnected` → `requestRoute(RouteInfo)`. 성공 판정은 `onRouteStarted`(실측 약 9초 뒤, 그 사이 내비가 앞으로 와서 앱은 보통 `onStop`), 실패는 `onError`·초기화 실패 → "내비를 열 수 없어요" + 같은 버튼으로 재시도. `onStop`에 `removeListener`·`release`(AC-C-2). 주소는 계약 Card에 없어 장소명으로 채운다.
- 출발한 곳은 최근 방문(10곳, `recent_visits`)에 들어가 다음 뽑기에서 빠진다. **PT 리허설 뒤에는 기록 초기화(S1)가 필요하다**(설정 화면 P7 전까지는 앱 강제 종료로 비움).
- 차량값·착석이 바뀌면(SOC 1%p·주행가능거리 1km·착석 수) 마지막 변화 1초 뒤 같은 세션으로 다시 불러 앞면은 그대로, 배지·1인 몫만 갱신(`AppViewModel.onVehicle`, AC-F5-2·AC-F11-1). 1인 몫 문구는 엔진 `display.perPersonText` 그대로.
- 착석 3명 이상이면 홈에 "N명 · 1인 기준으로 볼까요?"(AC-F11-3). 콘솔 착석: `PUT /vehicle/seat?kind=occupied&area=driver|passenger|rear_left|rear_right&value=true`.

## 4-3. 장부(F10 정산 · F17 기록 · F8 trip)
- 규칙은 `engine/Ledger.kt`(fixtures/ledger L1~L5), 언제 넣나는 `app/LedgerFlow.kt`(JUnit `LedgerFlowTest`).
  - **Open**: 화면이 보이고 정차이며 누적 주행거리를 이번 방문에 새로 읽은 뒤 1번. 마지막 목적지는 내비 `getRecentDestinationInfo`(2초 안 없으면 목적지 없이). 홈 맨 위 정산 카드(닫기, 탭 → 기록)
  - **Depart**: 출발 1탭 → trip(장소·편도·출발 때 누적 주행거리)
  - **Arrive**: trip이 있고 정차 중 운전석 문 닫힘 → 열림. Δodo 0(에뮬)이면 편도 추정 + "시연용 값"
- 저장: `ledger/EncryptedLedgerStore` — 앱 내부 파일, Android Keystore AES-256-GCM(AC-N-4, 장소 좌표는 장소당 1개, 현재 위치·궤적 없음). 깨지면 빈 장부로 시작
- 복귀했는데 차량값이 그대로면 상태 흐름이 새 값을 내보내지 않는다 → 정산 확인은 `visit`(화면이 보인 횟수)도 키로 다시 돌리고, 문 기준값은 복귀 때 지우지 않는다. 누적 주행거리는 들어올 때마다 비우고 새로 읽는다(예전 값으로 0km 정산 방지)
- 콘솔: 문 `PUT /vehicle/body?door_fl=true|false`, 주행거리 올리기 `PUT /vehicle/odometer?traveled_distance_km=<지금+132>`(줄일 수 없음)
- 에뮬 앱은 사용자 10으로 돈다 → 데이터 비우기는 `adb uninstall` 뒤 `install -g`(그냥 `pm clear`는 안 지워짐). 앱 안 기록 초기화는 설정(S1, P7)

## 4-4. 시연 리플레이 (`scripts/demo_replay.py`)
노고 시연 순서(명세 v0.5 12절을 지금 앱에 맞춘 것, DECISIONS 11/8 '시연 순서')를 콘솔 REST + adb로 한 바퀴 돈다. 표준 라이브러리만 쓴다.
- 리허설: `python -I scripts/demo_replay.py` — 콘솔 신호는 스크립트가 넣고, 앱은 발표자가 누른다(단계마다 엔터).
- 점검: `python -I scripts/demo_replay.py --auto --reset` — 앱도 adb로 누르고 화면 글자를 확인한다. 실패하면 종료 코드 1.
- 순서: 준비(기어 P·문 닫힘·4석, 데모 고정) → 출발(미리보기 104km·정체 38km) → 기어 D(1초 안에 가림, AC-C-1) → 기어 P → 운전석 문 열기(도착) → 계산 연출 → 영수증(2시간 10분·1.57배) → 쉰 시간 '없음' → [좋아요]/항목 → [닫기] → 홈 최근 기록.
- 콘솔 연결: 페이지가 1초마다 보내는 `POST /browser/session`을 스크립트도 보낸다. 브라우저 창이 숨거나 내비가 뜨면 페이지 타이머가 느려져 `emulator_inactive`(503)로 떨어지기 때문이다(11/8 실측). 그래도 처음 한 번은 콘솔 페이지가 열려 있어야 한다.
- 시연 흐름은 서버를 부르지 않는다(날씨는 `fixtures/effort/demo.json`). 그래서 서버·네트워크 없이도 그대로 재현된다(AC-C-3). 에뮬 네트워크를 끄면 콘솔도 끊기니 끄지 않는다.
- 옛 `scripts/replay.sh`는 피봇 전 흐름(D1~D9·pt)용이라 정리 PR에서 지운다.

## 5. 앱을 지을 때 지킬 것
- 차량 값은 리스너로 받는다. 타이머 반복 읽기 금지, 화면이 가려지면 내비·규제 리스너까지 해제(AC-C-2). H_Mate의 2초 폴링은 가져오지 않는다.
- 권한을 바꾸면 `contract/permissions.md`도 같은 PR에서(CI `check_permissions.py`).
- 화면 코드에 금액·거리 수식을 따로 적지 않는다. 계산은 `engine` 패키지 함수만.
- 안드로이드 단위 테스트(착석 반전·연비 단위 환산 등 Kotlin 쪽)는 `app/android/app/src/test/`에 JUnit으로 두고 CI `./gradlew test`에 걸린다.
