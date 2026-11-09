# 권한 신청 목록 (AC-N-7)

차곡 앱 `AndroidManifest.xml`의 `uses-permission`은 **이 목록과 정확히 같아야 한다**. Vehicle SDK 퍼미션은 App Market과 사전 협의·승인된 것만 선언할 수 있고, 미승인 선언은 심사 탈락이다(기능 명세서 8절).
목록을 바꾸면 같은 PR에서 `DECISIONS.md`에 한 줄 남긴다.
승인 열: 공모전 범위에서는 사전 승인을 문의하지 않고(DECISIONS '기술지원 질문') 에뮬레이터에 `adb install -g`로 부여한다. 실차·App Market 승인은 출시 단계에서 확인한다.

접두어: **P** = `pleos.car.permission.*`, **A** = `android.car.permission.*`

| 권한 | 쓰는 값 | 기능 | 승인(공모전) |
|---|---|---|---|
| P `CAR_DRIVE` | 기어 | X1 주행 중 숨김·F22 도착 | `adb -g` |
| P `CAR_SEATS` | 착석 | F20 인원 | `adb -g` |
| P `CAR_DOORS` | 운전석 문 위치 | F22 도착 | `adb -g` |
| P `NAVI_ROUTE` | 현재 위치·최근 목적지·즐겨찾기·경로 안내 시작·안내 상태 | F20 출발·F22 도착 | `adb -g` |
| P `NAVI_ROUTE_SEARCH` | `requestTrip` 경로 미리보기(ETA·정체) | F20 | **필요 여부 미확인**(June, 빼고 시험) · `adb -g` |
| P `CAR_LIGHTS` | 앰비언트 라이트 스위치·색 읽기(켜기 전 원래 값) | F28 앰비언트 라이트 | `adb -g`(protectionLevel normal) |
| P `CONTROL_CAR_LIGHTS` | 앰비언트 라이트 스위치·색 켜고 되돌리기(`setAmbientLightSwitch`·`setAmbientLightBrightnessColor`만) | F28 앰비언트 라이트 | `adb -g`(protectionLevel normal). 문서는 `CAR_LIGHTS`로 쓰기가 된다고 적었지만 에뮬에서 `SecurityException`이라 필요(June #109 실측) |
| `android.permission.INTERNET` | `/v1/weather`·`/v1/weather/trip`·`/v1/places` 호출 | F20·F23 | 일반 권한 |

## 선언하지 않는 것
- 피봇 전 기능(잔고·한 장·장부)에만 쓰던 것(11/8 노고 정리): P `CAR_ENERGY`(SOC·연료 %·연비·전비·충전 스위치), A `CAR_ENERGY`(`RANGE_REMAINING`·`EV_CHARGE_SWITCH`), A `CAR_INFO`(배터리·연료 용량·연료 종류). 노고 흐름은 이 값을 읽지 않는다.
- 제어 퍼미션 전부(`CONTROL_*`). 예외는 F28의 P `CONTROL_CAR_LIGHTS` 하나(앰비언트 스위치·색만, 효성 11/9). `setAmbientColorAutoByDriveModeEnabled` 같은 차 설정 변경 API와 `Odometer.setRangeRemainingDistance`는 사용 금지 API.
- A `CAR_MILEAGE`(AAOS `PERF_ODOMETER`) — 부여 불가, 주행거리는 P `CAR_DRIVE`의 `Odometer`로.
- H_Mate 프로토타입에만 있던 것: P `CAR_CLIMATE`·`CONTROL_CAR_CLIMATE`·`CAR_WINDOWS`·`CONTROL_CAR_WINDOWS`·`CAR_INFO`(게임·차량 제어 시험 화면용). 차곡 앱에는 선언하지 않는다.

## 확인 방법
`python scripts/check_permissions.py app/android/app/src/main/AndroidManifest.xml` — 이 표의 권한과 매니페스트가 같은지 비교한다. CI `app-android` job이 PR마다 돌린다.
