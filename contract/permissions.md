# 권한 신청 목록 (AC-N-7)

차곡 앱 `AndroidManifest.xml`의 `uses-permission`은 **이 목록과 정확히 같아야 한다**. Vehicle SDK 퍼미션은 App Market과 사전 협의·승인된 것만 선언할 수 있고, 미승인 선언은 심사 탈락이다(기능 명세서 8절).
목록을 바꾸면 같은 PR에서 `DECISIONS.md`에 한 줄 남기고, 기술지원 승인 상태를 아래 표에 적는다.

접두어: **P** = `pleos.car.permission.*`, **A** = `android.car.permission.*`

| 권한 | 쓰는 값 | 기능 | 승인 |
|---|---|---|---|
| P `CAR_ENERGY` | SOC·연료 %·연비·전비·충전 스위치 | F1·F9·F10 | 문의 전 |
| P `CAR_DRIVE` | 누적 주행거리, 기어 | F10·F17·X1 | 문의 전 |
| P `CAR_SEATS` | 착석 | F11·F10 | 문의 전 |
| P `CAR_DOORS` | 운전석 문 위치 | F17 도착 | 문의 전 |
| P `NAVI_ROUTE` | 현재 위치·최근 목적지·경로 안내·경유지·도착 | F5·F8·F10·F17 | 문의 전 |
| P `NAVI_ROUTE_SEARCH` | `requestTrip` 경로 미리보기 | F5 fallback | **필요 여부 미확인**(June, 11/7 전 빼고 시험) |
| A `CAR_ENERGY` | AAOS `RANGE_REMAINING` | F1·F2·F5 | 문의 전 |
| A `CAR_INFO` | AAOS `INFO_EV_BATTERY_CAPACITY`·`INFO_FUEL_CAPACITY`·`INFO_FUEL_TYPE` | F1·S1 | 문의 전 |
| `android.permission.INTERNET` | `/v1/cards` 호출 | F5 | 일반 권한 |

## 선언하지 않는 것
- 제어 퍼미션 전부(`CONTROL_*`). `Odometer.setRangeRemainingDistance`는 사용 금지 API.
- A `CAR_MILEAGE`(AAOS `PERF_ODOMETER`) — 부여 불가, 주행거리는 P `CAR_DRIVE`의 `Odometer`로.
- H_Mate 프로토타입에만 있던 것: P `CAR_LIGHTS`·`CONTROL_CAR_LIGHTS`·`CAR_CLIMATE`·`CONTROL_CAR_CLIMATE`·`CAR_WINDOWS`·`CONTROL_CAR_WINDOWS`·`CAR_INFO`(게임·차량 제어 시험 화면용). 차곡 앱으로 옮길 때 지운다.

## 확인 방법
`python scripts/check_permissions.py <AndroidManifest.xml 경로>` — 이 표의 권한과 매니페스트가 같은지 비교한다(앱 이관 뒤 CI에 붙인다).
