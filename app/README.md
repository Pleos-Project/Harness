# app/ — 차 앱

```
app/android/                                                안드로이드 프로젝트(Kotlin). 앱 기능은 여기에 짓는다
app/android/app/src/main/java/com/mist/pleos_app/engine/    Kotlin 엔진 (server/chagok/engine.py·cards.py 포팅)
app/android/app/src/test/.../engine/FixturesTest.kt         fixtures/ 대조 JUnit — cd app/android && ./gradlew test
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
| 주유 직후 '가득' 금액 | `fullTankAmount(시작 연료 %, 탱크 L, 단가)` → 리터·원·"약 8만 4천 원" | F9(PRD v1.3 '가득'), fixture D11 |
| 정산·도착·핀·월 합계(로컬 장부) | `ledgerStep(state, event)` · `monthSummary` | F8·F10·F17 |
| 일러스트 변형 | `illustrationName(theme, placeId, n)` → `"<theme>-<n>"` | design/RULES.md 3절 |

장부 이벤트는 3개다: `LedgerEvent.Open`(앱 실행·복귀, 기어 P) / `Depart`(출발 1탭) / `Arrive`(기어 P + 운전석 문, `onDestinationArrived`). state는 `toJson()`/`parseLedgerState()`로 문자열 저장한다. 저장소(Room·DataStore)는 장부 화면을 지을 때 정한다(아직 없음).

## 2-1. Compose 화면 규칙
- 테마는 `ui/theme/ChagokTheme.kt`. 색은 `design/tokens.css`를 옮긴 임시본이고, 토큰 관리 방식은 `design/RULES.md` 개정(열린 결정 12)에서 정한다. 그 전까지 색을 바꾸면 tokens.css도 같이 바꾼다.
- 주간/야간은 `isSystemInDarkTheme()`(기본 야간, 시스템이 주간이면 주간).
- 디자인 가이드 크기는 디스플레이 실제 픽셀이다 → `48.gpx`(Dp)·`26.gsp`(TextUnit). 터치 영역 48처럼 dp 기준 값은 그냥 `48.dp`.
- 화면 문구는 `res/values/strings.xml`에 둔다(`scripts/lint_copy.py`가 검사). 엔진이 만든 문구(`balanceText`·`display.*`)는 그대로 보여 준다.
- 레이아웃 확인은 `@Preview`로 가로·세로(1:1 분할)를 같이 둔다(`MainActivity.kt`의 `EngineCheckPreview` 참고).

## 3. 서버 호출 — Kotlin에서 직접 (열린 결정 2 해결)
- 요청은 `cardsRequest(...).toJson()`, 응답은 `parseCardsResponse()`. 원시 차량값은 보내지 않는다(`budget` 파생값만, 서버가 422로 막음).
- 개발: 에뮬에서 `http://10.0.2.2:8000`(`make dev`). **debug 빌드만** `src/debug/res/xml/network_security_config.xml`이 `10.0.2.2` cleartext를 허용한다. release는 cleartext가 기본 차단이라 HTTPS 도메인 하나만(AC-N-3).
- 배포(release): **`https://api.rangeleft.com`**(Let's Encrypt, 체인 끝 ISRG Root X1 → 안드로이드 7.1.1+). `/v1/cards`·`/v1/cards/swap`·`/v1/prices`. 서버 운영은 `deploy/README.md`(효성).
- 타임아웃 3초, 실패하면 `DemoServer(candidates)`로 fallback(AC-F5-7·AC-F5-8). 호출은 화면을 연 동안만 — 폴링·백그라운드 호출 금지.
- PT(12/3)는 네트워크 없이 돌아가야 하므로(AC-C-3) 데모 고정 모드를 기본으로 둔다.
- HTTP 클라이언트(`HttpURLConnection`·OkHttp 등)는 첫 서버 연동 PR에서 정한다.

## 4. SDK·에뮬 동작 메모 (H_Mate README, June 11/4~5 실측)
명세서 8절 신호 표에 없는 것만 적는다.
- `requestTrip`을 동시에 보내면 응답이 섞이고 늦은 응답도 온다 → 요청 순서가 아니라 응답 `summary.routePoints` 중 DESTINATION의 `poiName`으로 목적지를 맞춘다. 곳당 0.3~0.8초.
- 경유지가 있으면 `getDestinationInfo`의 남은 거리·통행료가 첫 구간(경유지까지) 기준이다.
- `RouteInfo.routeOption`은 내비까지 전달되지 않고, `changeRouteOption`은 받기만 하고 무시된다.
- 착석은 SDK가 반대로 읽는다(SDK 1 = 앉음, AAOS·콘솔 1 = 빈 좌석, 2 = 앉음) → 설정 `seat_invert`.
- 기어 P = 규제 없음, D = 속도 0이어도 규제. 규제 중 시스템이 앱을 가린다. adb 설치본은 `distractionOptimized`를 선언해도 가려진다.

## 5. 앱을 지을 때 지킬 것
- 차량 값은 리스너로 받는다. 타이머 반복 읽기 금지, 화면이 가려지면 내비·규제 리스너까지 해제(AC-C-2). H_Mate의 2초 폴링은 가져오지 않는다.
- 권한을 바꾸면 `contract/permissions.md`도 같은 PR에서(CI `check_permissions.py`).
- 화면 코드에 금액·거리 수식을 따로 적지 않는다. 계산은 `engine` 패키지 함수만.
- 안드로이드 단위 테스트(착석 반전·연비 단위 환산 등 Kotlin 쪽)는 `app/android/app/src/test/`에 JUnit으로 두고 CI `./gradlew test`에 걸린다.
