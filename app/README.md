# app/ — 차 앱

```
app/android/                    안드로이드 프로젝트. 지금은 빌드 환경 + 빈 화면뿐, 앱 기능은 여기에 짓는다
app/shared/chagok-engine.js     엔진 JS (server/chagok/engine.py·cards.py 포팅). 브라우저 전역 ChagokEngine / Node require
app/shared/test/*.test.js       fixtures/ 대조 테스트 — node --test "app/shared/test/*.test.js"
```

H_Mate(github.com/june0103/H_Mate)는 June의 실험 레포로, 참고용이다. 레포를 통째로 옮기지 않는다. 그 안의 차곡 데모 화면·브리지(`ChagokBridge.kt`)를 가져다 쓸지는 June이 판단한다. 빌드 환경·디자인 가이드 토큰·콘솔 기어 경로·SDK 동작 메모만 이 레포로 가져왔다(아래 1·4절, `design/tokens.css`, `scripts/replay.sh`).

## 1. 빌드 환경 (H_Mate 11/4 설정과 같은 값)
| 항목 | 값 | 파일 |
|---|---|---|
| Pleos SDK 저장소 | `https://nexus-playground.pleos.ai/repository/maven-releases/` (인증 없이 열림, 11/5 확인) | `settings.gradle.kts` |
| Pleos SDK | `ai.pleos.playground:Vehicle:2.3.2`, `NaviHelper:3.0.5` | `gradle/libs.versions.toml` |
| 빌드 도구 | AGP 9.3.3 · Gradle 9.5.0(wrapper) · JDK 21(툴체인 자동 받기) | `gradle/` |
| SDK 레벨 | compileSdk 37 · targetSdk 36 · minSdk 28, `useLibrary("android.car")` | `app/build.gradle.kts` |
| WebView | androidx.webkit 1.12.1 — `WebViewAssetLoader`로 `https://appassets.androidplatform.net/assets/`에서 서빙 | `MainActivity.kt` |
| 권한 | `contract/permissions.md`와 정확히 같음. `distractionOptimized` 미선언(1차 정차 전용) | `AndroidManifest.xml` |

```bash
cd app/android
./gradlew assembleDebug                                            # 엔진 JS가 assets/chagok/engine.js로 복사된다
adb install -r -g app/build/outputs/apk/debug/app-debug.apk        # -g: 권한 미리 부여(에뮬)
```
**검증 상태:** 하네스를 만든 PC에는 JDK·Android SDK가 없어 빌드를 돌려 보지 못했다. 값은 June PC에서 빌드되던 H_Mate와 같다. 첫 확인은 CI `app-android` job이다.
`applicationId`는 June `App` 레포와 같은 `com.mist.pleos_app`이고, App Market 등록 전에 확정한다(DECISIONS 열린 결정 1). 빌드 버전을 App 레포 값(AGP 9.4.1 등)으로 올릴지는 열린 결정 21.

## 2. 엔진을 WebView에서 쓰기
빌드 때 `syncChagokEngine` 태스크가 `app/shared/chagok-engine.js`를 `assets/chagok/engine.js`로 복사한다(사본은 커밋하지 않음). 페이지에서 `<script src="chagok/engine.js"></script>` → `window.ChagokEngine`.
오프라인 데모 모드는 `fixtures/candidates_demo.json`의 `candidates`를 앱에 넣고 `ChagokEngine.createDemoServer({ candidates })`를 쓴다. 온라인 응답과 같은 문구가 나오는 건 node 테스트가 보장한다.

| 기능 | 함수 | 명세 |
|---|---|---|
| 잔고·범위(로컬) | `vehicleInputs` → `computeRange` | F1·F2, AC-F1-1·2·5, AC-F2-1·2·3 |
| 서버 요청 만들기 | `cardsRequest` (원시 차량값 대신 `budget` 파생값) | F5, `contract/openapi.yaml` |
| 오프라인 데모·서버 실패 fallback | `createDemoServer({candidates}).cards(req) / .swap(sid)` — 서버와 같은 응답 | AC-C-3·AC-F5-7·AC-F5-8 |
| 충전 직후 | `chargeAmount` | F9, AC-F9-1 |
| 정산·도착·핀·월 합계(로컬 장부) | `ledgerStep(state, event)` · `monthSummary` | F8·F10·F17 |
| 일러스트 변형 | `illustrationPath(theme, id, n)` | design/RULES.md 3절 |

장부 이벤트는 3개다: `open`(앱 실행·복귀, 기어 P) / `depart`(출발 1탭) / `arrive`(기어 P + 운전석 문, `onDestinationArrived`). state는 JSON이라 localStorage에 그대로 넣는다.
디자인 가이드 크기 단위(`--u`)는 페이지 시작 때 `1 / devicePixelRatio px`로 맞춘다(`assets/index.html` 참고).

## 3. 서버 호출 — June 결정 필요
페이지 origin이 `https://appassets.androidplatform.net`이라 JS `fetch('http://10.0.2.2:8000')`는 mixed content로 막힌다(targetSdk 36이라 cleartext도 기본 차단).

| 방식 | 내용 |
|---|---|
| **A. Kotlin 브리지로 호출** (권장) | `postCards(json)` → `HttpURLConnection` → 결과를 JS로. 개발 빌드만 `network_security_config`에 `10.0.2.2` cleartext 허용, 배포는 HTTPS 도메인 하나(AC-N-3). 타임아웃 3초 |
| B. JS `fetch` + HTTPS 서버 | 개발 중에도 HTTPS 서버가 있어야 함. CORS 설정 필요 |

PT(12/3)는 네트워크 없이 돌아가야 하므로(AC-C-3) 데모 고정 모드를 기본으로 둔다.

## 4. SDK·에뮬 동작 메모 (H_Mate README, June 11/4~5 실측)
명세서 8절 신호 표에 없는 것만 적는다.
- `requestTrip`을 동시에 보내면 응답이 섞이고 늦은 응답도 온다 → 요청 순서가 아니라 응답 `summary.routePoints` 중 DESTINATION의 `poiName`으로 목적지를 맞춘다. 곳당 0.3~0.8초.
- 경유지가 있으면 `getDestinationInfo`의 남은 거리·통행료가 첫 구간(경유지까지) 기준이다.
- `RouteInfo.routeOption`은 내비까지 전달되지 않고, `changeRouteOption`은 받기만 하고 무시된다.
- 착석은 SDK가 반대로 읽는다(SDK 1 = 앉음, AAOS·콘솔 1 = 빈 좌석, 2 = 앉음) → 설정 `seat_invert`.
- 기어 P = 규제 없음, D = 속도 0이어도 규제. 규제 중 시스템이 앱을 가린다. adb 설치본은 `distractionOptimized`를 선언해도 가려진다.
- 페이지 console 로그는 logcat 태그 `ChagokWeb`(H_Mate는 `CarAppWeb`).

## 5. 앱을 지을 때 지킬 것
- 차량 값은 리스너로 받는다. 타이머 반복 읽기 금지, 화면이 가려지면 내비·규제 리스너까지 해제(AC-C-2). H_Mate의 2초 폴링은 가져오지 않는다.
- 권한을 바꾸면 `contract/permissions.md`도 같은 PR에서(CI `check_permissions.py`).
- 앱 안에 수식을 따로 적지 않는다. 계산은 `ChagokEngine`.
- 안드로이드 단위 테스트(착석 반전·연비 단위 환산 등 Kotlin 쪽)는 `app/android/app/src/test/`에 JUnit으로 두고 CI `./gradlew test`에 걸린다.
